package bskyviewer.indexer

import bskyviewer.indexer.lucene.Analyser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
class Web(val index: Index, val analyser: Analyser) {

    @GetMapping("/langs", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun langs() = mapOf("seen" to index.langs, "known" to analyser.byLang.keys)

    @GetMapping("/")
    fun index(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "desc") sort: List<String>,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "false") debug: Boolean,
        @RequestParam(required = false) dids: List<String>?,
    ) = index.search(q, limit, sort, dids ?: emptyList(), debug)

    @PostMapping("/")
    fun indexer(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "desc") sort: List<String>,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "false") debug: Boolean,
        @RequestBody dids: List<String>,
    ) = index.search(q, limit, sort, dids, debug)
}
