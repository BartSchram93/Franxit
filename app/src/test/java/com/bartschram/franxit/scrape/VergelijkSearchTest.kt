package com.bartschram.franxit.scrape

import org.junit.Assert.assertEquals
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
}
