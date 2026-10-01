package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCatalogTest {
    @Test
    fun `feed-only plugin is available`() {
        val catalog = build(remote = listOf(plugin()))

        assertEquals(StoreEntryState.AVAILABLE, catalog.entry("feeds")?.state)
    }

    @Test
    fun `newer registry version is update available`() {
        val catalog = build(
            remote = listOf(plugin(versionCode = 8)),
            local = listOf(local()),
            versions = mapOf(PACKAGE to 7L),
        )

        assertEquals(StoreEntryState.UPDATE_AVAILABLE, catalog.entry("feeds")?.state)
        assertEquals(PluginCatalogState.ENABLED, catalog.entry("feeds")?.localGrantState)
        assertEquals(PluginProvenance.REGISTRY, catalog.entry("feeds")?.provenance)
        assertEquals("Anezium", catalog.entry("feeds")?.registryAuthor)
    }

    @Test
    fun `available updates expose installed and registry version names`() {
        val installedVersions = mapOf(PACKAGE to InstalledPluginVersion(7L, "0.1.0"))
        val catalog = build(
            remote = listOf(plugin(versionCode = 8, versionName = "0.2.0")),
            local = listOf(local()),
            versions = installedVersions.mapValues { it.value.versionCode },
        )

        assertEquals(
            listOf(PluginUpdateInfo("feeds", "Feeds", "0.1.0", "0.2.0")),
            catalog.availableUpdates(installedVersions),
        )
    }

    @Test
    fun `equal or older registry version is installed and carries every grant state`() {
        val grantStates = listOf(
            PluginCatalogState.ENABLED,
            PluginCatalogState.PENDING,
            PluginCatalogState.DISABLED,
            PluginCatalogState.DENIED,
        )
        grantStates.forEach { grantState ->
            val catalog = build(
                remote = listOf(plugin(versionCode = 7)),
                local = listOf(local(state = grantState)),
                versions = mapOf(PACKAGE to 7L),
            )
            assertEquals(StoreEntryState.INSTALLED, catalog.entry("feeds")?.state)
            assertEquals(grantState, catalog.entry("feeds")?.localGrantState)
        }
    }

    @Test
    fun `installed copy signed by another key is a signer change, never an update`() {
        listOf(7L, 8L).forEach { registryVersion ->
            val installedVersions = mapOf(PACKAGE to InstalledPluginVersion(7L, "0.1.0"))
            val catalog = build(
                remote = listOf(plugin(versionCode = registryVersion)),
                local = listOf(local()),
                versions = installedVersions.mapValues { it.value.versionCode },
                signers = mapOf(PACKAGE to OTHER_SIGNER),
            )

            assertEquals(StoreEntryState.SIGNER_CHANGE, catalog.entry("feeds")?.state)
            assertTrue(catalog.availableUpdates(installedVersions).isEmpty())
        }
    }

    @Test
    fun `unknown installed signer offers neither update nor switch`() {
        val installedVersions = mapOf(PACKAGE to InstalledPluginVersion(7L, "0.1.0"))
        val catalog = build(
            remote = listOf(plugin(versionCode = 8)),
            local = listOf(local()),
            versions = installedVersions.mapValues { it.value.versionCode },
            signers = emptyMap(),
        )

        assertEquals(StoreEntryState.INSTALLED, catalog.entry("feeds")?.state)
        assertTrue(catalog.availableUpdates(installedVersions).isEmpty())
    }

    @Test
    fun `signer change that needs a newer host stays installed and held`() {
        val catalog = build(
            remote = listOf(plugin(versionCode = 8, minHostVersionCode = 7)),
            local = listOf(local()),
            versions = mapOf(PACKAGE to 7L),
            signers = mapOf(PACKAGE to OTHER_SIGNER),
            hostVersionCode = 6,
        )

        assertEquals(StoreEntryState.INSTALLED, catalog.entry("feeds")?.state)
        assertEquals(true, catalog.entry("feeds")?.updateBlockedByHost)
    }

    @Test
    fun `signer switch installs once the package is gone and gives up when it stays`() {
        val remote = listOf(plugin(versionCode = 8))
        val installed = build(
            remote = remote,
            local = listOf(local()),
            versions = mapOf(PACKAGE to 7L),
            signers = mapOf(PACKAGE to OTHER_SIGNER),
        ).entry("feeds")
        val catalogueCatchingUp = build(remote = remote, local = listOf(local())).entry("feeds")
        val uninstalled = build(remote = remote).entry("feeds")

        assertEquals(SignerSwitch.Next.ABANDON, SignerSwitch.afterUninstallPrompt(installed))
        assertEquals(SignerSwitch.Next.WAIT, SignerSwitch.afterUninstallPrompt(catalogueCatchingUp))
        assertEquals(SignerSwitch.Next.INSTALL, SignerSwitch.afterUninstallPrompt(uninstalled))
        assertEquals(SignerSwitch.Next.ABANDON, SignerSwitch.afterUninstallPrompt(null))
    }

    @Test
    fun `local-only plugin is sideloaded`() {
        val catalog = build(local = listOf(local()), versions = mapOf(PACKAGE to 7L))

        assertEquals(StoreEntryState.SIDELOADED, catalog.entry("feeds")?.state)
        assertEquals(7L, catalog.entry("feeds")?.installedVersionCode)
        assertEquals(PluginProvenance.LOCAL, catalog.entry("feeds")?.provenance)
    }

    @Test
    fun `feed-only host requirement disables install`() {
        val catalog = build(
            remote = listOf(plugin(minHostVersionCode = 7)),
            hostVersionCode = 6,
        )

        assertEquals(StoreEntryState.REQUIRES_HOST, catalog.entry("feeds")?.state)
    }

    @Test
    fun `host-incompatible update keeps installed plugin accessible`() {
        val catalog = build(
            remote = listOf(plugin(versionCode = 8, minHostVersionCode = 7)),
            local = listOf(local()),
            versions = mapOf(PACKAGE to 7L),
            hostVersionCode = 6,
        )

        assertEquals(StoreEntryState.INSTALLED, catalog.entry("feeds")?.state)
        assertEquals(true, catalog.entry("feeds")?.updateBlockedByHost)
        assertEquals(PluginCatalogState.ENABLED, catalog.entry("feeds")?.localGrantState)
    }

    @Test
    fun `lens is available from the registry when no built-ins or APK are present`() {
        val catalog = build(
            remote = listOf(
                plugin(
                    pluginId = "lens",
                    id = "lens",
                    packageName = "com.anezium.rokidbus.plugin.lens",
                ),
            ),
        )

        assertEquals(StoreEntryState.AVAILABLE, catalog.entry("lens")?.state)
        assertNull(catalog.entry("lens")?.localEntry)
    }
    @Test
    fun `duplicate registry plugin ids are all dropped and logged`() {
        val logs = mutableListOf<String>()
        val catalog = build(
            remote = listOf(plugin(), plugin(id = "other", packageName = "dev.other")),
            logger = logs::add,
        )

        assertTrue(catalog.entries.isEmpty())
        assertEquals(listOf("Dropping duplicate registry plugin id=feeds"), logs)
    }

    @Test
    fun `registry id and package must both match local identity`() {
        val logs = mutableListOf<String>()
        val local = local(packageName = "dev.sideloaded.feeds")
        val catalog = build(remote = listOf(plugin()), local = listOf(local), logger = logs::add)

        assertEquals(StoreEntryState.SIDELOADED, catalog.entry("feeds")?.state)
        assertNull(catalog.entry("feeds")?.registryPlugin)
        assertEquals(listOf("Dropping registry identity mismatch id=feeds"), logs)
    }

    @Test
    fun `descriptor id mismatch and non-phone artifacts are dropped`() {
        val logs = mutableListOf<String>()
        val mismatchedId = plugin(id = "wrong")
        val nonPhone = plugin(pluginId = "transit", id = "transit", target = "glasses", packageName = "dev.transit")

        val catalog = build(remote = listOf(mismatchedId, nonPhone), logger = logs::add)

        assertTrue(catalog.entries.isEmpty())
        assertEquals(2, logs.size)
    }

    private fun build(
        remote: List<RegistryPlugin> = emptyList(),
        local: List<PluginCatalogEntry> = emptyList(),
        versions: Map<String, Long> = emptyMap(),
        signers: Map<String, String> = versions.mapValues { SIGNER },
        hostVersionCode: Long = 6,
        logger: (String) -> Unit = {},
    ) = StoreCatalog.build(
        feed = RegistryFeed(1, remote),
        localCatalog = PluginCatalog(local),
        installedVersionCodes = versions,
        installedSignerSha256 = signers,
        hostVersionCode = hostVersionCode,
        logger = logger,
    )

    private fun plugin(
        pluginId: String = "feeds",
        id: String = pluginId,
        packageName: String = PACKAGE,
        versionCode: Long = 7,
        versionName: String = "0.1.0",
        minHostVersionCode: Long = 6,
        target: String = "phone",
    ) = RegistryPlugin(
        id = id,
        name = "Feeds",
        category = "Social",
        summary = "Read feeds.",
        description = "Read social feeds on your glasses.",
        author = "Anezium",
        sourceUrl = "https://github.com/Anezium/Rokid-Nexus",
        publishedAt = "2026-07-12",
        iconAsset = "feeds.png",
        screenshotAssets = emptyList(),
        listingDescriptionMarkdown = "Feeds",
        releases = emptyList(),
        nexus = RegistryNexus(pluginId, 3, listOf("surfaces"), true, null, minHostVersionCode),
        artifact = RegistryArtifact(
            target = target,
            url = "https://github.com/Anezium/Rokid-Nexus/releases/download/feeds-v0.1.0/feeds-phone-release.apk",
            sha256 = "ab".repeat(32),
            signerSha256 = SIGNER,
            sizeBytes = 123L,
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
        ),
    )

    private fun local(
        packageName: String = PACKAGE,
        state: PluginCatalogState = PluginCatalogState.ENABLED,
    ): PluginCatalogEntry {
        val descriptor = PluginDescriptor(
            id = "feeds",
            displayName = "Feeds local",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/feeds"),
            settingsActivity = null,
            launchable = true,
        )
        val principal = PhonePluginPrincipal(
            packageName = packageName,
            serviceComponent = ComponentName(packageName, "$packageName.Service"),
            uid = 10001,
            signingDigestSha256 = "digest",
            descriptor = descriptor,
        )
        return PluginCatalogEntry(
            catalogKey = "external:$packageName:feeds",
            id = "feeds",
            displayName = "Feeds local",
            state = state,
            launchable = state == PluginCatalogState.ENABLED,
            principal = principal,
        )
    }

    companion object {
        private const val PACKAGE = "com.anezium.rokidbus.plugin.feeds"
        private val SIGNER = "cd".repeat(32)
        private val OTHER_SIGNER = "ef".repeat(32)
    }
}
