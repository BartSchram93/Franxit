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
}
