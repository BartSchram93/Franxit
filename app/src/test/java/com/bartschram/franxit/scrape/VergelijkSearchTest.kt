package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VergelijkSearchTest {

    @Test
    fun parsesSuggestions() {
        val json = """{"items":[
            {"id":1150180227,"title":"AirPods Pro 3","uri":"/hoofdtelefoon/apple/airpods_pro_3_1150180227","cheapestPrice":212,"suggestionType":"PRODUCT_SUGGESTION"},
            {"title":"Hoofdtelefoons","uri":"/hoofdtelefoon/","suggestionType":"CATEGORY_SUGGESTION"}]}"""
        val hits = VergelijkSearch.parseSuggestions(json)
        assertEquals(1, hits.size)
        assertEquals("https://www.vergelijk.be/hoofdtelefoon/apple/airpods_pro_3_1150180227/", hits[0].url)
        assertEquals(212.0, hits[0].price!!, 0.001)
    }

    @Test
    fun parsesResults() {
        val json = """{"searchResult":{"result":{"size":1,"items":[{"productOfferSummary":{"productSummary":{
            "name":"Tablethoes TCL NXTPAPER 14","cheapestPriceIncludingDeals":19.95,
            "path":{"categoryUrl":"tablet_hoes","brandUrl":"etuitab","productUrl":"tablethoes_tcl_nxtpaper_14_123456789"}}}}]}}}"""
        val hits = VergelijkSearch.parseResults(json)
        assertEquals("https://www.vergelijk.be/tablet_hoes/etuitab/tablethoes_tcl_nxtpaper_14_123456789/", hits.single().url)
        assertEquals(19.95, hits.single().price!!, 0.001)
    }

    @Test
    fun theSameTitleWrittenDifferentlyIsListedOnce() {
        val hits = listOf(
            VergelijkSearch.Hit("Nxtpaper 14", "https://www.vergelijk.be/a/b/c_111111111/", 364.6),
            VergelijkSearch.Hit("NXTPAPER  14", "https://www.vergelijk.be/a/b/d_222222222/", 360.0),
            VergelijkSearch.Hit("Nxtpaper 14 Pro", "https://www.vergelijk.be/a/b/e_333333333/", 400.0),
            VergelijkSearch.Hit("Nxtpaper 70 Pro - 256 Gb Blauw", "https://www.vergelijk.be/a/b/f_444444444/", 258.89),
            VergelijkSearch.Hit("Nxtpaper 70 Pro - 256 Gb Grijs", "https://www.vergelijk.be/a/b/g_555555555/", 258.89),
        )
        val kept = VergelijkSearch.withoutDuplicates(hits).map { it.title }
        // The duplicate goes; other models, colours and sizes stay.
        assertEquals(listOf("Nxtpaper 14", "Nxtpaper 14 Pro", "Nxtpaper 70 Pro - 256 Gb Blauw", "Nxtpaper 70 Pro - 256 Gb Grijs"), kept)
    }

    @Test
    fun aSearchResultCarriesItsPhoto() {
        val suggestions = """{"items":[{"id":1,"title":"Ragers Mini Conical Rood PHD 1.2 mm - 6 stuks","imageUrl":"//pc.vergelijk.be/{{size}}/360953729.webp","uri":"/ragers/interprox/ragers_mini_conical_1_0mm_6st_71098986","cheapestPrice":5.2,"suggestionType":"PRODUCT_SUGGESTION"}]}"""
        assertEquals("https://pc.vergelijk.be/500x500/360953729.webp", VergelijkSearch.parseSuggestions(suggestions).single().image)
    }

    @Test
    fun aResultWithoutAPhotoHasNone() {
        assertEquals(null, VergelijkSearch.photo(null))
        assertEquals(null, VergelijkSearch.photo(""))
        assertEquals(null, VergelijkSearch.photo("null"))
    }

    @Test
    fun aNameThatFoundNothingLikeItIsTriedLooser() {
        // Bol.com sells the "Rowenta RO3134EA" of a Krëfel tag as "Power XXL RO3134".
        assertEquals(listOf("Rowenta RO3134", "RO3134"), VergelijkSearch.looser("Rowenta RO3134EA"))
        // "DREAME-AQUA 10 ULTRAROL BLANC" as read on a tag: vergelijk.be calls it "Aqua 10 Roller White".
        assertEquals(listOf("DREAME AQUA 10", "AQUA 10 Ultrarol Blanc"), VergelijkSearch.looser("DREAME-AQUA 10 Ultrarol Blanc"))
        assertEquals(
            listOf("Philips Airfryer XXL HD9650", "Airfryer XXL HD9650/90", "HD9650"),
            VergelijkSearch.looser("Philips Airfryer XXL HD9650/90"),
        )
        // Nothing to loosen: no extra searches.
        assertEquals(emptyList<String>(), VergelijkSearch.looser("Singer M3405"))
        assertEquals(emptyList<String>(), VergelijkSearch.looser("Sony WF-1000XM5"))
        assertEquals(emptyList<String>(), VergelijkSearch.looser("dyson v15"))
    }

    private fun hit(title: String) = VergelijkSearch.Hit(title, "https://www.vergelijk.be/x/", null)

    @Test
    fun onlyASharedColourIsNoFit() {
        val beds = listOf(hit("dekbedovertrek Divine Blanc 260x240 cm"), hit("Le corbeau blanc - Andrzej Stasiuk"))
        assertFalse(VergelijkSearch.fits(beds, "DREAME-AQUA 10 Ultrarol Blanc"))
        assertTrue(VergelijkSearch.fits(listOf(hit("Aqua 10 Roller White")), "AQUA 10 Ultrarol Blanc"))
        // A shared model code is enough.
        assertTrue(VergelijkSearch.fits(listOf(hit("Power XXL RO3134 Stofzuiger met zak")), "Rowenta RO3134"))
        assertFalse(VergelijkSearch.fits(emptyList(), "Rowenta RO3134EA"))
        assertTrue(VergelijkSearch.fits(listOf(hit("Dyson V15 Detect")), "dyson"))
    }
}
