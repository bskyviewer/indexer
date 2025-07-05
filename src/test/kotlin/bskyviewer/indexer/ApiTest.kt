package bskyviewer.indexer

import bskyviewer.indexer.web.Api
import bskyviewer.indexer.web.IndexParams
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@ActiveProfiles("test")
class ApiTest {

    @Autowired
    lateinit var api: Api

    @Test
    fun `test data is properly indexed and can be queried`() = runBlocking {
        // Wait for indexing to complete
        Thread.sleep(1.seconds.inWholeMilliseconds)

        // Query all posts
        val result = api.index(IndexParams(q = "", limit = 1000))

        val posts = result.result

        // Verify we have results
        assertTrue(posts.isNotEmpty(), "Should have indexed posts")

        // Verify total hits
        assertTrue(result.totalHits.value > 0, "Should have found posts")

        println("Found ${result.totalHits.value} posts")

        // Test querying for a specific post by text
        val queryResult = api.index(IndexParams(q = "shower", limit = 10))

        val queryPosts = queryResult.result

        assertTrue(queryPosts.isNotEmpty(), "Should find posts containing 'shower'")

        // Verify tweet content
        val post = queryPosts.first()
        assertTrue(post.containsKey("did"), "Post should have a DID")

        println("Found post with DID: ${post["did"]}")
    }

    @Test
    fun `test querying by language`() = runBlocking {
        // Wait for indexing to complete
        Thread.sleep(1.seconds.inWholeMilliseconds)

        // Get available languages
        val langs = api.langs()

        val seen = langs["seen"]

        assertNotNull(seen)
        assertTrue("ja" in seen, "Should have detected japanese posts")

        // Query for Japanese posts if available
        val queryResult = api.index(IndexParams(q = "text_ja:聞いてへん", limit = 10))

        val queryPosts = queryResult.result

        assertTrue(queryPosts.isNotEmpty(), "Should find Japanese posts")

        println("Found ${queryPosts.size} Japanese posts")
    }
}
