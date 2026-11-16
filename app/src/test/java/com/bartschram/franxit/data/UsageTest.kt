package com.bartschram.franxit.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {

    @Test
    fun countedOnceADay() {
        assertTrue(Usage.shouldReport(null, "2026-11-16"))
        assertTrue(Usage.shouldReport("2026-11-15", "2026-11-16"))
        assertFalse(Usage.shouldReport("2026-11-16", "2026-11-16"))
    }

    @Test
    fun onlyTheVersionIsSent() {
        val json = JSONObject(Usage.payload("1.0.14"))
        // No number that identifies the phone, nothing else.
        assertEquals(setOf("version"), json.keys().asSequence().toSet())
        assertEquals("1.0.14", json.getString("version"))
    }

    @Test
    fun deletingAnOldNumberSendsOnlyThatNumber() {
        val json = JSONObject(Usage.deletePayload("3f2b8c1e-0a4d-4f6a-9d3e-7c1b2a5e8f90"))
        assertEquals(setOf("id", "delete"), json.keys().asSequence().toSet())
    }
}
