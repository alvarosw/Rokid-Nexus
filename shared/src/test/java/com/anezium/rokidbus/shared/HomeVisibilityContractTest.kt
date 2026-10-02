package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeVisibilityContractTest {
    @Test
    fun `payload round trips`() {
        listOf(
            HomeVisibility(screenOn = true, homeVisible = true),
            HomeVisibility(screenOn = true, homeVisible = false),
            HomeVisibility(screenOn = false, homeVisible = false),
        ).forEach { value ->
            assertEquals(value, HomeVisibilityContract.parse(HomeVisibilityContract.toPayload(value)))
        }
    }

    @Test
    fun `home visible on a dark screen is normalized to hidden`() {
        val payload = JSONObject().put("version", 1).put("screenOn", false).put("homeVisible", true)
        assertEquals(HomeVisibility(false, false), HomeVisibilityContract.parse(payload))
    }

    @Test
    fun `missing invalid or foreign version fields are rejected`() {
        assertNull(HomeVisibilityContract.parse(JSONObject()))
        assertNull(HomeVisibilityContract.parse(JSONObject().put("version", 1).put("screenOn", true)))
        assertNull(HomeVisibilityContract.parse(JSONObject().put("version", 1).put("homeVisible", true)))
        assertNull(
            HomeVisibilityContract.parse(
                JSONObject().put("version", 2).put("screenOn", true).put("homeVisible", true),
            ),
        )
        assertNull(
            HomeVisibilityContract.parse(
                JSONObject().put("version", 1).put("screenOn", "true").put("homeVisible", true),
            ),
        )
        assertNull(
            HomeVisibilityContract.parse(
                JSONObject().put("version", "1").put("screenOn", true).put("homeVisible", true),
            ),
        )
    }
}
