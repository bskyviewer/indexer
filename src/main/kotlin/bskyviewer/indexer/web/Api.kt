package bskyviewer.indexer.web

import bskyviewer.indexer.Index
import bskyviewer.indexer.lucene.Analyser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
class Api(val index: Index, val analyser: Analyser) {

    @GetMapping("/langs")
    fun langs() = mapOf("seen" to index.langs, "known" to analyser.byLang.keys)

    @GetMapping("/size")
    fun size() = index.size()

    @GetMapping("/")
    fun index(params: IndexParams) = index.search(params, ::toSearchResult)

    @PostMapping("/")
    fun indexer(@RequestBody params: IndexParams) = index.search(params, ::toSearchResult)

    @GetMapping("/feed")
    fun feed(params: IndexParams) = index.search(params, ::toFeedSkeleton)

    @PostMapping("/feed")
    fun feeder(@RequestBody params: IndexParams) = index.search(params, ::toFeedSkeleton)
}
