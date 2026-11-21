package com.bartschram.franxit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesTest {

    @Test
    fun showsOnlyWhatTheShortNameLeavesOut() {
        assertEquals("128GB Wifi", extraName("Apple iPad Air 13\"", "Apple iPad Air 13\" 128GB Wifi"))
        assertEquals("36,3 cm, grijs", extraName("TCL NXTPAPER 14", "TCL NXTPAPER 14, 36,3 cm, grijs"))
    }

    @Test
    fun showsTheSiteNameUnderACustomName() {
        assertEquals("TCL NXTPAPER 14, grijs", extraName("Tablet", "TCL NXTPAPER 14, grijs"))
    }

    @Test
    fun nothingWhenItAddsNothing() {
        assertNull(extraName("Wisberg WBTTVR55DNFW", "Wisberg WBTTVR55DNFW"))
        assertNull(extraName("Wisberg", "Wisberg ,"))
    }

    @Test
    fun aSiteNameThatOnlyRepeatsTheNamesWordsAddsNothing() {
        assertNull(extraName("Chiaogoo Twist", "Chiaogoo Chiaogoo Twist"))
        assertNull(extraName("Chiaogoo Twist", "chiaogoo twist"))
        // A word more is worth showing.
        assertEquals("Lace 13 cm", extraName("Chiaogoo Twist", "Chiaogoo Twist Lace 13 cm"))
    }
}
