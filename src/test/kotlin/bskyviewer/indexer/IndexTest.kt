package bskyviewer.indexer

import bskyviewer.indexer.lucene.Analyser
import bskyviewer.indexer.web.IndexParams
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Paths

// Create a single shared Index instance for all tests
val index = Index(
    Analyser(),
    1000,
    Paths.get("src/test/resources/sample-index/"),
    listOf("rkey", "did", "createdAt", "known_lang", "error")
)

class IndexTest {


    @ParameterizedTest
    @CsvSource(
        "shower,3lt6rr4s4uc2q",
        "tag:\":blobcat_melt:\",3lt6rr2tql4q2",
        "+embed_type:\"app.bsky.embed.images\" +label:nudity,3lt6rqxbuac22",
        "is:reply,3lt6rr6x5ts2v",
    )
    fun testBasicSearch(q: String, rkey: String) {
        // Test basic search with a query string
        val params = IndexParams(q, sort = listOf("desc"), limit = -1)
        val results = index.search(params) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Print debug information
        println("[DEBUG_LOG] Query: $q")
        println("[DEBUG_LOG] Expected rkey: $rkey")
        println("[DEBUG_LOG] Total hits: ${results.totalHits.value}")
        println("[DEBUG_LOG] Result count: ${results.result.size}")

        // Print the first few results for debugging
        results.result.take(5).forEachIndexed { index, result ->
            println("[DEBUG_LOG] Result $index rkey: ${result["rkey"]}")
        }

        // Verify the results contain the expected rkey
        val foundMatch = results.result.any { result ->
            result["rkey"] == rkey
        }
        assertTrue(foundMatch, "Did not find expected result for query: $q")
    }

    @Test
    fun testSortOrder() {
        // Test descending sort order (newest first)
        val paramsDesc = IndexParams(q = "", sort = listOf("desc"), limit = 5)
        val resultsDesc = index.search(paramsDesc) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Test ascending sort order (oldest first)
        val paramsAsc = IndexParams(q = "", sort = listOf("asc"), limit = 5)
        val resultsAsc = index.search(paramsAsc) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Verify the sort orders are different
        assertNotEquals(
            resultsDesc.result.firstOrNull()?.get("createdAt"),
            resultsAsc.result.firstOrNull()?.get("createdAt"),
            "Descending and ascending sort should return different first results"
        )
    }

    @Test
    fun testLimitParameter() {
        // Test with different limit values
        val params10 = IndexParams(q = "", limit = 10)
        val results10 = index.search(params10) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        val params5 = IndexParams(q = "", limit = 5)
        val results5 = index.search(params5) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Verify the limit is respected
        assertTrue(results10.result.size <= 10, "Should return at most 10 results")
        assertTrue(results5.result.size <= 5, "Should return at most 5 results")

        // If we have enough data, the smaller limit should return fewer results
        if (results10.result.size > 5) {
            assertTrue(
                results5.result.size < results10.result.size,
                "Smaller limit should return fewer results when enough data is available"
            )
        }
    }

    @Test
    fun testDidFilter() {
        val did = "did:plc:7enllrpbravxibbjrl72lo4b"
        val paramsWithDid = IndexParams(q = "", dids = listOf(did), limit = -1)
        val resultsWithDid = index.search(paramsWithDid) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Verify all results have the specified DID
        assertTrue(resultsWithDid.result.isNotEmpty(), "Should find results for the specified DID")
        val allMatchDid = resultsWithDid.result.all { result ->
            result["did"] == did
        }
        assertTrue(allMatchDid, "All results should have the specified DID")
    }

    @Test
    fun testEmptyQuery() {
        // Test search with empty query (should match all documents)
        val params = IndexParams(q = "", limit = -1)
        val results = index.search(params) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Verify results
        assertNotNull(results)
        assertEquals(results.totalHits.value, 323, "Should find results with empty query")
        assertEquals(results.result.size, 323, "Result list should not be empty")
    }

    @Test
    fun testLanguageSpecificSearch() {
        // Test search for Japanese content - using a valid query that will match Japanese text
        // Instead of wildcard, search for a common Japanese character
        val params = IndexParams(q = "text_ja:ペンライト", limit = 100)
        val results = index.search(params) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        assertNotEquals(results.totalHits.value, 0, "Should have found results")
        // Verify results contain Japanese content
        val hasJapaneseContent = results.result.all { result ->
            (result["known_lang"] as? List<*>)?.contains("ja") ?: false
        }
        assertTrue(hasJapaneseContent, "Results should contain Japanese content")
    }

    @Test
    fun testDebugParameter() {
        // Test with debug parameter set to false (default)
        val paramsNoDebug = IndexParams(q = "shower", sort = listOf("desc"), limit = -1)
        val resultsNoDebug = index.search(paramsNoDebug) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Test with debug parameter set to true
        val paramsWithDebug = IndexParams(q = "shower", sort = listOf("desc"), limit = -1, debug = true)
        val resultsWithDebug = index.search(paramsWithDebug) { searcher, topDocs, query ->
            bskyviewer.indexer.web.toSearchResult(searcher, topDocs, query)
        }

        // Verify that debug=false doesn't include query and weights
        assertNull(resultsNoDebug.query, "Query should be null when debug is false")
        assertNull(resultsNoDebug.weights, "Weights should be null when debug is false")

        // Verify that debug=true includes query and weights
        assertNotNull(resultsWithDebug.query, "Query should not be null when debug is true")
        assertNotNull(resultsWithDebug.weights, "Weights should not be null when debug is true")

        // Verify that the query string contains the search term
        assertTrue(resultsWithDebug.query!!.contains("shower"), "Query string should contain the search term")

        // Verify that weights list is not empty
        assertTrue(resultsWithDebug.weights!!.isNotEmpty(), "Weights list should not be empty when debug is true")
    }
}
