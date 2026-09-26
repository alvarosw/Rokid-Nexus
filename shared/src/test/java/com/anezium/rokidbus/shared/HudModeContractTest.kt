package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudModeContractTest {
    @Test
    fun `config round-trips through json`() {
        assertEquals(true, HudModeContract.gridModeFromConfig(HudModeContract.configToJson(true)))
        assertEquals(false, HudModeContract.gridModeFromConfig(HudModeContract.configToJson(false)))
    }

    @Test
    fun `unrecognized or missing payload reads as no answer`() {
        assertNull(HudModeContract.gridModeFromConfig(null))
        assertNull(HudModeContract.gridModeFromConfig(JSONObject().put("version", 0).put("mode", "grid")))
        assertNull(HudModeContract.gridModeFromConfig(JSONObject().put("version", 1).put("mode", "unknown")))
    }
}
