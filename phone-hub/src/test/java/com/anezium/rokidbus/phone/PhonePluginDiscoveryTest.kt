package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.os.Bundle
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.tile.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// Robolectric for real ComponentName equality; the JUnit android.jar stubs throw on it.
@RunWith(RobolectricTestRunner::class)
class PhonePluginDiscoveryTest {
    private fun record(
        packageName: String = "dev.example.hello",
        serviceName: String = "dev.example.hello.PluginService",
        uid: Int = 10001,
        pluginId: String = "hello",
        signer: ByteArray = byteArrayOf(1, 2, 3),
        extraMetadata: List<Pair<String, String?>> = emptyList(),
    ) = PhonePluginDiscovery.PackageRecord(
        packageName = packageName,
        serviceClassName = serviceName,
        uid = uid,
        exported = true,
        signingCertificates = listOf(signer),
        metadata = listOf(
            BusConstants.META_PLUGIN_ID to pluginId,
            BusConstants.META_PLUGIN_DISPLAY_NAME to pluginId.replaceFirstChar(Char::uppercase),
            BusConstants.META_PLUGIN_API_VERSION to "3",
            BusConstants.META_PLUGIN_CAPABILITIES to "surfaces",
            BusConstants.META_PLUGIN_RECEIVE_PREFIXES to "/system/plugin,/plugin/$pluginId",
        ) + extraMetadata,
    )

    @Test
    fun `valid record binds package uid component signer and descriptor`() {
        val result = PhonePluginDiscovery.evaluate(
            listOf(
                record(
                    extraMetadata = listOf(
                        BusConstants.META_PLUGIN_ICON to "STAR",
                        BusConstants.META_PLUGIN_ICON_DRAWABLE to "2131230890",
                        BusConstants.META_PLUGIN_GLYPHS to "2131230891",
                    ),
                ),
            ),
        ).single()
        assertTrue(result is PhonePluginCandidate.Valid)
        val principal = (result as PhonePluginCandidate.Valid).principal
        assertEquals("dev.example.hello", principal.packageName)
        assertEquals(10001, principal.uid)
        assertEquals("hello", principal.descriptor.id)
        assertEquals("star", principal.descriptor.iconKey)
        assertEquals(2131230890, principal.descriptor.iconDrawableResId)
        assertEquals(2131230891, principal.descriptor.glyphsResId)
        assertEquals(64, principal.signingDigestSha256.length)
    }

    @Test
    fun `guardian metadata resolves a relative service in the plugin package`() {
        val result = PhonePluginDiscovery.evaluate(
            listOf(
                record(
                    extraMetadata = listOf(
                        BusConstants.META_PLUGIN_GUARDIAN_SERVICE to ".RelayGuardianService",
                    ),
                ),
            ),
        ).single() as PhonePluginCandidate.Valid

        assertEquals(
            ComponentName("dev.example.hello", "dev.example.hello.RelayGuardianService"),
            result.principal.guardianServiceComponent,
        )
    }

    @Test
    fun `invalid guardian metadata does not create a bind target`() {
        val result = PhonePluginDiscovery.evaluate(
            listOf(
                record(
                    extraMetadata = listOf(
                        BusConstants.META_PLUGIN_GUARDIAN_SERVICE to "invalid/service",
                    ),
                ),
            ),
        ).single() as PhonePluginCandidate.Valid

        assertEquals(null, result.principal.guardianServiceComponent)
    }

    @Test
    fun `malformed metadata remains a stable invalid candidate`() {
        val result = PhonePluginDiscovery.evaluate(
            listOf(record(extraMetadata = listOf(BusConstants.META_PLUGIN_CAPABILITIES to "root"))),
        ).single() as PhonePluginCandidate.Invalid
        assertEquals("CONFLICTING_METADATA", result.reason)
    }

    @Test
    fun `duplicate plugin IDs are conflicts`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(
                record(packageName = "dev.one", serviceName = "dev.one.Service", uid = 10001),
                record(packageName = "dev.two", serviceName = "dev.two.Service", uid = 10002),
            ),
        )
        assertEquals(setOf("DUPLICATE_PLUGIN_ID"), results.map { (it as PhonePluginCandidate.Invalid).reason }.toSet())
    }

    @Test
    fun `multiple principals sharing one uid are unsupported`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(
                record(packageName = "dev.one", serviceName = "dev.one.Service", pluginId = "one", uid = 10001),
                record(packageName = "dev.two", serviceName = "dev.two.Service", pluginId = "two", uid = 10001),
            ),
        )
        assertEquals(setOf("SHARED_UID_UNSUPPORTED"), results.map { (it as PhonePluginCandidate.Invalid).reason }.toSet())
    }

    @Test
    fun `one package cannot publish multiple plugin services`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(record(), record(serviceName = "dev.example.hello.SecondService")),
        )
        assertEquals("MULTIPLE_PLUGIN_SERVICES", (results.single() as PhonePluginCandidate.Invalid).reason)
    }

    @Test
    fun `candidates sort by display name then package`() {
        val results = PhonePluginDiscovery.evaluate(
            listOf(
                record(packageName = "dev.z", serviceName = "dev.z.Service", pluginId = "zulu", uid = 3),
                record(packageName = "dev.a", serviceName = "dev.a.Service", pluginId = "alpha", uid = 2),
            ),
        )
        assertEquals(listOf("alpha", "zulu"), results.map { it.displayName.lowercase() })
    }

    @Test
    fun `installed plugin's declared tile sizes reach the descriptor and the editor`() {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "dev.example.tiles"
        val applicationInfo = ApplicationInfo().apply {
            this.packageName = packageName
            uid = 10042
        }
        val serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            name = "$packageName.PluginService"
            exported = true
            this.applicationInfo = applicationInfo
            metaData = Bundle().apply {
                putString(BusConstants.META_PLUGIN_ID, "tiles")
                putString(BusConstants.META_PLUGIN_DISPLAY_NAME, "Tiles")
                putInt(BusConstants.META_PLUGIN_API_VERSION, BusConstants.API_VERSION)
                putString(BusConstants.META_PLUGIN_CAPABILITIES, "surfaces,widget_tile")
                putString(BusConstants.META_PLUGIN_RECEIVE_PREFIXES, "/system/plugin,/plugin/tiles")
                putString(BusConstants.META_PLUGIN_TILE_SIZES, "1x1,2x2")
            }
        }
        val shadowPackageManager = shadowOf(context.packageManager)
        @Suppress("DEPRECATION")
        shadowPackageManager.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                this.applicationInfo = applicationInfo
                services = arrayOf(serviceInfo)
                signatures = arrayOf(Signature(byteArrayOf(1, 2, 3)))
            },
        )
        shadowPackageManager.addResolveInfoForIntent(
            Intent(BusConstants.ACTION_PLUGIN),
            ResolveInfo().apply { this.serviceInfo = serviceInfo },
        )

        val candidate = PhonePluginDiscovery(context.packageManager).discover()
            .single { it.packageName == packageName }

        val descriptor = (candidate as PhonePluginCandidate.Valid).principal.descriptor
        assertEquals(setOf(TileSize.SMALL, TileSize.LARGE), descriptor.supportedTileSizes)
        assertEquals(
            listOf(TileSize.SMALL, TileSize.LARGE),
            TileSizeOptions.forPlugin(descriptor.supportedTileSizes),
        )
    }
}
