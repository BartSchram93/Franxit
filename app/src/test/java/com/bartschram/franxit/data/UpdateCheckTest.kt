package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {

    @Test
    fun readsTheBuildNumberFromATag() {
        assertEquals(12, UpdateCheck.buildOf("v1.0.12"))
        assertEquals(3, UpdateCheck.buildOf("1.0.3"))
        assertNull(UpdateCheck.buildOf("latest"))
        assertNull(UpdateCheck.buildOf(null))
    }

    @Test
    fun readsGithubsAnswer() {
        assertEquals(9, UpdateCheck.latestBuild("""{"tag_name": "v1.0.9", "name": "Franxit 1.0.9", "assets": []}"""))
        assertNull(UpdateCheck.latestBuild("""{"message": "Not Found"}"""))
        assertNull(UpdateCheck.latestBuild("not json"))
    }

    @Test
    fun onlyANewerVersionCounts() {
        assertTrue(UpdateCheck.isNewer(9, "1.0.8"))
        assertFalse(UpdateCheck.isNewer(8, "1.0.8"))
        assertFalse(UpdateCheck.isNewer(7, "1.0.8"))
        assertFalse(UpdateCheck.isNewer(null, "1.0.8"))
        assertFalse(UpdateCheck.isNewer(9, null))
    }
}
