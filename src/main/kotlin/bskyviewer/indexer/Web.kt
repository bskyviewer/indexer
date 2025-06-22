package bskyviewer.indexer

import bskyviewer.indexer.lucene.Analyser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class Web(val index: Index, val analyser: Analyser) {

    @GetMapping("/langs", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun langs() = mapOf("seen" to index.langs, "known" to analyser.byLang.keys)

    @GetMapping("/size")
    fun size() = index.size()

    data class IndexParams(
        val q: String = "",
        var sort: List<String> = listOf("desc"),
        var limit: Int = 100,
        var debug: Boolean = false,
        var dids: List<String> = emptyList()
    )

    @GetMapping("/")
    fun index(params: IndexParams) = index.search(params)

    @PostMapping("/")
    fun indexer(@RequestBody params: IndexParams) = index.search(params)
}
