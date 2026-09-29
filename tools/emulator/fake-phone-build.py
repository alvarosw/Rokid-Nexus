#!/usr/bin/env python3
"""Resolve a fake-phone scenario into one self-contained JSON document on stdout.

  "@file:rel/path"   a string value is replaced by that file's text (paths are relative to the scenario)
  "binaryFile": ...  an envelope key; the file becomes "binaryBase64", and any payload string
                     equal to "@sha256" becomes the lowercase SHA-256 hex of those bytes
"""
import base64, hashlib, json, os, sys


def resolve(node, base):
    if isinstance(node, dict):
        out = {k: resolve(v, base) for k, v in node.items() if k != "binaryFile"}
        if "binaryFile" in node:
            data = open(os.path.join(base, node["binaryFile"]), "rb").read()
            out["binaryBase64"] = base64.b64encode(data).decode()
            digest = hashlib.sha256(data).hexdigest()
            payload = out.get("payload", {})
            for key, value in payload.items():
                if value == "@sha256":
                    payload[key] = digest
        return out
    if isinstance(node, list):
        return [resolve(item, base) for item in node]
    if isinstance(node, str) and node.startswith("@file:"):
        return open(os.path.join(base, node[len("@file:"):]), encoding="utf-8").read()
    return node


if __name__ == "__main__":
    path = os.path.abspath(sys.argv[1])
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    json.dump(resolve(document, os.path.dirname(path)), sys.stdout, separators=(",", ":"))
