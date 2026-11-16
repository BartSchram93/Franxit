package com.bartschram.franxit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {

    private fun short(title: String) = Names.short(title)

    @Test
    fun keepsBrandAndModelAndDropsSpecs() {
        assertEquals(
            "Lenovo Chromebook Duet 10,95\"",
            short("Lenovo Laptop Lenovo Chromebook Duet 10,95\" Kompanio 838 8 GB RAM 128 GB SSD Qwerty Spaans"),
        )
        assertEquals("Apple iPad Air 13\"", short("Apple iPad Air 13\" 128GB Wifi"))
        assertEquals("iPhone 15 Pro Max", short("iPhone 15 Pro Max 256 GB"))
    }

    @Test
    fun cutsAtTheUsualSeparators() {
        assertEquals("TCL NXTPAPER 14", short("TCL NXTPAPER 14, 36,3 cm (14,3 inch), 2400 x 1600 pixels, 256 GB, 8 GB, Android 14, grijs"))
        assertEquals("TCL NXTPAPER 14", short("TCL NXTPAPER 14 + Flip Case + T-pen - Tablet Wi-Fi Display NXTPAPER 14 inch 16 GB RAM"))
    }

    @Test
    fun leavesShortNamesAlone() {
        assertEquals("Wisberg WBTTVR55DNFW", short("Wisberg WBTTVR55DNFW"))
    }

    @Test
    fun keepsAtMostSixWords() {
        assertEquals("Lenovo ThinkCentre Neo 50s Gen 4", short("Lenovo ThinkCentre Neo 50s Gen 4"))
        assertEquals("Samsung Galaxy Tab S9 FE Plus", short("Samsung Galaxy Tab S9 FE Plus Enterprise Edition Mint"))
    }

    @Test
    fun fallsBackWhenTooLittleIsLeft() {
        assertEquals("Lenovo 8GB", short("Lenovo 8GB"))
    }

    @Test
    fun dropsColours() {
        assertEquals("Sony WF-1000XM6", short("Sony WF-1000XM6 Zwart"))
        assertEquals("JBL Tour Pro 3 TWS", short("JBL Tour Pro 3 TWS Zwart"))
    }

    @Test
    fun namesFromShopLinks() {
        assertEquals("wisberg wbttvr55dnfw", Names.fromUrl("https://www.coolblue.be/nl/product/973396/wisberg-wbttvr55dnfw.html"))
        assertEquals("Apple AirPods Pro 3 Zwart", Names.fromUrl("https://www.amazon.com.be/Apple-AirPods-Pro-3-Zwart/dp/B0FQFN8Y2L"))
        assertEquals(null, Names.fromUrl("https://www.example.be/p/12345"))
    }
}
