#!/usr/bin/env python3
"""Upsert one plugin release of this repository into dist/nexus-plugins.v1.json.

Given a namespaced release tag (<id>-v<version>), downloads the release's APK, reads its
identity, signer and Nexus meta-data with aapt2/apksigner from $ANDROID_HOME, and writes the
plugin's registry entry. Listing fields come from the upstream registry's entry for the same id,
or from the plugin's README when upstream does not list it.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REPO = "alvarosw/Rokid-Nexus"
DEFAULT_REGISTRY = REPO_ROOT / "dist" / "nexus-plugins.v1.json"
UPSTREAM_REGISTRY_URL = (
    "https://raw.githubusercontent.com/Anezium/RokidBrew-Registry/main/dist/nexus-plugins.v1.json"
)
META_PREFIX = "com.anezium.rokidbus.plugin."
ANDROID_VALUE = ":value(0x01010024)="
ANDROID_NAME = ":name(0x01010003)="

# Same field order as the upstream registry, so diffs between the two stay readable.
ENTRY_KEYS = [
    "id", "kind", "name", "category", "summary", "description", "author", "sourceUrl",
    "publishedAt", "iconAsset", "screenshotAssets", "listing", "releases", "nexus", "artifact",
    "iconUrl", "screenshotUrls",
]


def fail(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def http_get(url, accept="application/json"):
    headers = {"Accept": accept, "User-Agent": "rokid-nexus-registry-tool"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return response.read()


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        fail("ANDROID_HOME is not set")
    build_tools = Path(sdk) / "build-tools"
    versions = sorted(
        (path for path in build_tools.glob("*") if (path / name).is_file()),
        key=lambda path: [int(part) if part.isdigit() else part for part in re.split(r"[.-]", path.name)],
    )
    if not versions:
        fail(f"no {name} under {build_tools}")
    return str(versions[-1] / name)


def run(*command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def read_badging(apk):
    badging = run(build_tool("aapt2"), "dump", "badging", apk)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging, re.M)
    if not match:
        fail("aapt2 badging has no package line")
    return match.group(1), int(match.group(2)), match.group(3)


def parse_attribute_value(raw):
    if raw.startswith('"'):
        return raw[1:raw.index('" (Raw:')] if '" (Raw:' in raw else raw.strip('"')
    return raw.split(" ", 1)[0]


def read_nexus_metadata(apk):
    tree = run(build_tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    metadata = {}
    name = None
    in_meta = False
    for line in tree.splitlines():
        stripped = line.strip()
        if stripped.startswith("E: "):
            in_meta = stripped.startswith("E: meta-data")
            name = None
        elif in_meta and ANDROID_NAME in stripped:
            name = parse_attribute_value(stripped.split(ANDROID_NAME, 1)[1])
        elif in_meta and ANDROID_VALUE in stripped and name and name.startswith(META_PREFIX):
            metadata[name[len(META_PREFIX):]] = parse_attribute_value(stripped.split(ANDROID_VALUE, 1)[1])
    return metadata


def read_signer(apk):
    output = run(build_tool("apksigner"), "verify", "--print-certs", apk)
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output, re.M)
    if len(digests) != 1:
        fail(f"expected exactly one APK signer, found {len(digests)}")
    return digests[0]


def split_list(value):
    return [item for item in re.split(r"[,;\s]+", value or "") if item]


def load_registry(path):
    if not path.exists():
        return {"version": 1, "plugins": []}
    registry = json.loads(path.read_text(encoding="utf-8"))
    if registry.get("version") != 1 or not isinstance(registry.get("plugins"), list):
        fail(f"{path} is not a version 1 registry")
    return registry


def readme_listing(plugin_id, release_name):
    candidates = [REPO_ROOT / "plugins" / plugin_id, REPO_ROOT / f"plugin-{plugin_id}"]
    readme = next((path / "README.md" for path in candidates if (path / "README.md").is_file()), None)
    if readme is None:
        fail(f"upstream does not list '{plugin_id}' and no README was found for it")
    lines = readme.read_text(encoding="utf-8").splitlines()
    heading = next((line[2:].strip() for line in lines if line.startswith("# ")), release_name)
    paragraph, started = [], False
    for line in lines:
        if line.startswith("#"):
            if started:
                break
            continue
        if line.strip():
            started = True
            paragraph.append(line.strip())
        elif started:
            break
    summary = " ".join(paragraph)
    return {
        "name": heading,
        "category": "Tools",
        "summary": summary.split(". ")[0].rstrip(".") + ".",
        "description": summary,
        "iconAsset": f"{plugin_id}-icon.png",
        "screenshotAssets": [],
        "listing": {"descriptionMarkdown": f"## About\n\n{summary}"},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tag", required=True, help="release tag, <id>-v<version>")
    parser.add_argument("--repo", default=DEFAULT_REPO, help=f"GitHub repository (default {DEFAULT_REPO})")
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY, help="registry JSON to update")
    parser.add_argument("--upstream-registry", default=UPSTREAM_REGISTRY_URL, help="listing source URL")
    parser.add_argument("--author", help="publisher shown in the Store (default: the repository owner)")
    parser.add_argument("--min-host-version-code", type=int, help="override nexus.minHostVersionCode")
    args = parser.parse_args()

    match = re.fullmatch(r"([a-z0-9-]+)-v(.+)", args.tag)
    if not match:
        fail(f"tag '{args.tag}' is not <id>-v<version>")
    plugin_id, version = match.groups()

    release = json.loads(http_get(f"https://api.github.com/repos/{args.repo}/releases/tags/{args.tag}"))
    apks = [asset for asset in release.get("assets", []) if asset["name"].endswith(".apk")]
    preferred = [asset for asset in apks if asset["name"] == f"{plugin_id}-phone-release.apk"]
    if len(preferred) == 1:
        asset = preferred[0]
    elif len(apks) == 1:
        asset = apks[0]
    else:
        fail(f"release {args.tag} has no single phone APK asset")

    with tempfile.TemporaryDirectory() as scratch:
        apk = os.path.join(scratch, asset["name"])
        Path(apk).write_bytes(http_get(asset["browser_download_url"], accept="application/octet-stream"))
        data = Path(apk).read_bytes()
        package_name, version_code, version_name = read_badging(apk)
        metadata = read_nexus_metadata(apk)
        signer = read_signer(apk)

    if version_name != version:
        fail(f"APK versionName '{version_name}' does not match tag version '{version}'")
    if metadata.get("ID") != plugin_id:
        fail(f"APK plugin id '{metadata.get('ID')}' does not match tag id '{plugin_id}'")

    registry = load_registry(args.registry)
    existing = next((plugin for plugin in registry["plugins"] if plugin["id"] == plugin_id), None)
    upstream = next(
        (
            plugin
            for plugin in json.loads(http_get(args.upstream_registry))["plugins"]
            if plugin["id"] == plugin_id
        ),
        None,
    )
    for source, label in ((existing, "this registry"), (upstream, "upstream")):
        if source and source["artifact"]["packageName"] != package_name:
            fail(f"APK package '{package_name}' differs from {label}: {source['artifact']['packageName']}")

    min_host = args.min_host_version_code
    if min_host is None:
        min_host = next(
            (source["nexus"]["minHostVersionCode"] for source in (existing, upstream) if source),
            None,
        )
    if min_host is None:
        fail("no minHostVersionCode known for this plugin; pass --min-host-version-code")

    listing_keys = [
        "name", "category", "summary", "description", "iconAsset", "screenshotAssets", "listing",
        "iconUrl", "screenshotUrls",
    ]
    listing_source = upstream or existing or readme_listing(plugin_id, release.get("name") or plugin_id)
    entry = {key: listing_source[key] for key in listing_keys if key in listing_source}

    release_entry = {"version": version, "date": release["published_at"], "notes": release.get("body") or ""}
    releases = [item for item in (existing or {}).get("releases", []) if item["version"] != version]
    releases = sorted(releases + [release_entry], key=lambda item: item["date"], reverse=True)

    artifact = {
        "target": "phone",
        "url": asset["browser_download_url"],
        "sha256": hashlib.sha256(data).hexdigest(),
        "signerSha256": signer,
        "sizeBytes": len(data),
        "packageName": package_name,
        "versionCode": version_code,
        "versionName": version_name,
    }
    nexus = {
        "pluginId": plugin_id,
        "apiVersion": int(metadata.get("API_VERSION", "0")),
        "capabilities": split_list(metadata.get("CAPABILITIES")),
        "launchable": metadata.get("LAUNCHABLE", "true").lower() != "false",
        "settingsActivity": metadata.get("SETTINGS_ACTIVITY") or None,
        "minHostVersionCode": min_host,
    }
    published_at = release["published_at"]
    # Backfilling an older tag records its notes without moving the served artifact backwards.
    if existing and existing["artifact"]["versionCode"] > version_code:
        artifact, nexus, published_at = existing["artifact"], existing["nexus"], existing["publishedAt"]

    entry.update(
        id=plugin_id,
        kind="nexus-plugin",
        author=args.author or args.repo.split("/")[0],
        sourceUrl=f"https://github.com/{args.repo}",
        publishedAt=published_at,
        releases=releases,
        nexus=nexus,
        artifact=artifact,
    )
    entry = {key: entry[key] for key in ENTRY_KEYS if key in entry}

    plugins = [plugin for plugin in registry["plugins"] if plugin["id"] != plugin_id] + [entry]
    registry = {"version": 1, "plugins": sorted(plugins, key=lambda plugin: plugin["id"])}
    args.registry.parent.mkdir(parents=True, exist_ok=True)
    args.registry.write_text(json.dumps(registry, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{plugin_id} {version_name} ({version_code}) signer {signer} -> {args.registry}")


if __name__ == "__main__":
    main()
