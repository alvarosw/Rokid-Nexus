package com.anezium.rokidbus.phone

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreRegistryTest {
    private class MemoryCache(var record: RegistryCacheRecord? = null) : RegistryCache {
        override fun read(): RegistryCacheRecord? = record
        override fun write(record: RegistryCacheRecord) { this.record = record }
    }

    @Test
    fun `fork entries win and every other plugin comes from upstream`() {
        val registry = registry(
            fork = online(feed(entry("media", FORK_SIGNER))),
            upstream = online(feed(entry("media", UPSTREAM_SIGNER), entry("lyrics", UPSTREAM_SIGNER))),
        )

        val plugins = registry.loadedPlugins()

        assertEquals(setOf("media", "lyrics"), plugins.keys)
        assertEquals(FORK_SIGNER, plugins.getValue("media").artifact.signerSha256)
        assertEquals(RegistryFeedOrigin.FORK, plugins.getValue("media").feed)
        assertEquals(UPSTREAM_SIGNER, plugins.getValue("lyrics").artifact.signerSha256)
        assertEquals(RegistryFeedOrigin.UPSTREAM, plugins.getValue("lyrics").feed)
    }

    @Test
    fun `unreachable fork is served from its cache, never replaced by upstream`() {
        val registry = registry(
            fork = offline(cached = feed(entry("media", FORK_SIGNER))),
            upstream = online(feed(entry("media", UPSTREAM_SIGNER), entry("lyrics", UPSTREAM_SIGNER))),
        )

        val plugins = registry.loadedPlugins()

        assertEquals(FORK_SIGNER, plugins.getValue("media").artifact.signerSha256)
        assertEquals(RegistryFeedOrigin.FORK, plugins.getValue("media").feed)
        assertEquals(UPSTREAM_SIGNER, plugins.getValue("lyrics").artifact.signerSha256)
    }

    @Test
    fun `malformed fork feed falls back to its cache`() {
        val fork = RegistryClient(
            transport = { RegistryHttpResponse(200, "{\"version\": 1, \"plugins\": [{}]}", "\"new\"") },
            cache = MemoryCache(RegistryCacheRecord(feed(entry("media", FORK_SIGNER)), "\"old\"", 1L)),
        )
        val registry = StoreRegistry(fork, online(feed(entry("media", UPSTREAM_SIGNER))))

        assertEquals(FORK_SIGNER, registry.loadedPlugins().getValue("media").artifact.signerSha256)
    }

    @Test
    fun `fork that never loaded leaves upstream alone`() {
        val registry = registry(
            fork = offline(cached = null),
            upstream = online(feed(entry("media", UPSTREAM_SIGNER), entry("lyrics", UPSTREAM_SIGNER))),
        )

        val plugins = registry.loadedPlugins()

        assertEquals(setOf("media", "lyrics"), plugins.keys)
        assertEquals(UPSTREAM_SIGNER, plugins.getValue("media").artifact.signerSha256)
        assertEquals(RegistryFeedOrigin.UPSTREAM, plugins.getValue("media").feed)
    }

    @Test
    fun `failed upstream does not hide fork entries`() {
        val registry = registry(
            fork = online(feed(entry("media", FORK_SIGNER))),
            upstream = offline(cached = null),
        )

        val plugins = registry.loadedPlugins()

        assertEquals(setOf("media"), plugins.keys)
        assertEquals(FORK_SIGNER, plugins.getValue("media").artifact.signerSha256)
    }

    @Test
    fun `both feeds failing without caches is a failure`() {
        val registry = registry(fork = offline(cached = null), upstream = offline(cached = null))

        val result = registry.load()

        assertTrue(result is RegistryLoadResult.Failure)
        assertNull(registry.cachedSnapshot())
    }

    @Test
    fun `cached snapshot merges both caches without network`() {
        val registry = registry(
            fork = offline(cached = feed(entry("media", FORK_SIGNER))),
            upstream = offline(cached = feed(entry("media", UPSTREAM_SIGNER), entry("lyrics", UPSTREAM_SIGNER))),
        )

        val snapshot = registry.cachedSnapshot()!!

        assertEquals(RegistrySource.CACHE, snapshot.source)
        assertEquals(listOf("media", "lyrics"), snapshot.feed.plugins.map(RegistryPlugin::id))
        assertEquals(FORK_SIGNER, snapshot.feed.plugins.first().artifact.signerSha256)
    }

    @Test
    fun `merged snapshot is from the network when either feed is and keeps the newest fetch time`() {
        val fork = snapshot(feed(entry("media", FORK_SIGNER)), RegistrySource.CACHE, fetchedAt = 5L)
        val upstream = snapshot(feed(entry("lyrics", UPSTREAM_SIGNER)), RegistrySource.NETWORK, fetchedAt = 9L)

        val merged = RegistryMerge.merge(fork, upstream)!!

        assertEquals(RegistrySource.NETWORK, merged.source)
        assertEquals(9L, merged.lastFetchEpochMillis)
        assertNull(RegistryMerge.merge(null, null))
    }

    @Test
    fun `fork claims an upstream plugin that shares its package under another id`() {
        val fork = snapshot(feed(entry("media", FORK_SIGNER)))
        val upstream = snapshot(
            feed(entry("mediadeck", UPSTREAM_SIGNER, packageName = packageOf("media")), entry("lyrics", UPSTREAM_SIGNER)),
        )

        val merged = RegistryMerge.merge(fork, upstream)!!

        assertEquals(listOf("media", "lyrics"), merged.feed.plugins.map(RegistryPlugin::id))
    }

    @Test
    fun `committed fork registry parses`() {
        val committed = listOf(File("../dist/nexus-plugins.v1.json"), File("dist/nexus-plugins.v1.json"))
            .first(File::isFile)

        val feed = RegistryClient.parse(committed.readText())

        assertEquals(RegistryClient.SUPPORTED_VERSION, feed.version)
        assertEquals(feed.plugins.map(RegistryPlugin::id).sorted(), feed.plugins.map(RegistryPlugin::id))
    }

    private fun StoreRegistry.loadedPlugins(): Map<String, RegistryPlugin> {
        val result = load()
        assertTrue(result is RegistryLoadResult.Success)
        return (result as RegistryLoadResult.Success).snapshot.feed.plugins.associateBy(RegistryPlugin::id)
    }

    private fun registry(fork: RegistryClient, upstream: RegistryClient) = StoreRegistry(fork, upstream)

    private fun online(body: String) = RegistryClient(
        transport = { RegistryHttpResponse(200, body, "\"etag\"") },
        cache = MemoryCache(),
    )

    private fun offline(cached: String?) = RegistryClient(
        transport = { throw IOException("offline") },
        cache = MemoryCache(cached?.let { RegistryCacheRecord(it, "\"etag\"", 1L) }),
    )

    private fun snapshot(
        body: String,
        source: RegistrySource = RegistrySource.NETWORK,
        fetchedAt: Long = 1L,
    ) = RegistrySnapshot(RegistryClient.parse(body), source, null, fetchedAt)

    private fun feed(vararg entries: String) = """{"version": 1, "plugins": [${entries.joinToString(",")}]}"""

    private fun packageOf(id: String) = "com.anezium.rokidbus.plugin.$id"

    private fun entry(id: String, signer: String, packageName: String = packageOf(id)) = """
        {
          "id": "$id",
          "kind": "nexus-plugin",
          "name": "$id",
          "category": "Music",
          "summary": "Summary.",
          "description": "Description.",
          "author": "Publisher",
          "sourceUrl": "https://github.com/alvarosw/Rokid-Nexus",
          "publishedAt": "2026-09-01T00:00:00Z",
          "iconAsset": "$id-icon.png",
          "screenshotAssets": [],
          "listing": {"descriptionMarkdown": ""},
          "releases": [],
          "nexus": {
            "pluginId": "$id",
            "apiVersion": 3,
            "capabilities": ["surfaces"],
            "launchable": true,
            "settingsActivity": null,
            "minHostVersionCode": 6
          },
          "artifact": {
            "target": "phone",
            "url": "https://github.com/alvarosw/Rokid-Nexus/releases/download/$id-v1.0.0/$id-phone-release.apk",
            "sha256": "${"ab".repeat(32)}",
            "signerSha256": "$signer",
            "sizeBytes": 1234,
            "packageName": "$packageName",
            "versionCode": 1,
            "versionName": "1.0.0"
          }
        }
    """.trimIndent()

    companion object {
        private val FORK_SIGNER = "78a1be1045ba3b2614baa46e306a353a8a6bc3e5d07ba5d587490931d5f56d60"
        private val UPSTREAM_SIGNER = "f5e938e2e79b0526b31e40d36c8c19098450c1636b7e14a306681b4effddf81c"
    }
}
