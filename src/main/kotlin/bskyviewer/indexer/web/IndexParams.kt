package bskyviewer.indexer.web

import java.time.Instant

data class IndexParams(
    val q: String = "",
    var sort: List<String> = listOf("desc"),
    var limit: Int = 100,
    var debug: Boolean = false,
    var dids: List<String> = emptyList(),
    var before: Instant? = null,
    var after: Instant? = null,
)