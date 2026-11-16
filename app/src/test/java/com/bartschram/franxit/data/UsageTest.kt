package com.bartschram.franxit.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {

    @Test
    fun comingBackWithinHalfAnHourIsTheSameUse() {
        val t = 1_000_000_000L
        assertTrue(Usage.isNewUse(0L, t))
        assertFalse(Usage.isNewUse(t, t + 10 * 60_000))
        assertTrue(Usage.isNewUse(t, t + 31 * 60_000))
    }

    @Test
    fun onlyTheAnonymousNumberVersionAndCountsAreSent() {
        val json = JSONObject(Usage.payload("3f2b8c1e-0a4d-4f6a-9d3e-7c1b2a5e8f90", "1.0.11", 7, 2))
        assertEquals(setOf("id", "version", "opens", "bol_clicks"), json.keys().asSequence().toSet())
        assertEquals("1.0.11", json.getString("version"))
        assertEquals(7, json.getInt("opens"))
        assertEquals(2, json.getInt("bol_clicks"))
    }

    @Test
    fun deletingSendsOnlyTheNumber() {
        val json = JSONObject(Usage.deletePayload("3f2b8c1e-0a4d-4f6a-9d3e-7c1b2a5e8f90"))
        assertEquals(setOf("id", "delete"), json.keys().asSequence().toSet())
        assertEquals(true, json.getBoolean("delete"))
    }
}
