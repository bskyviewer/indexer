package bskyviewer.indexer

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@RestController
class Web(val index: Index) {
    @ResponseBody
    @GetMapping("/")
    fun index(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "desc") sort: List<String>,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) dids: List<String>?,
    ) = index.search(q, limit, sort, dids ?: emptyList())

    @ResponseBody
    @PostMapping("/")
    fun indexer(
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "desc") sort: List<String>,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestBody dids: List<String>,
    ) = index.search(q, limit, sort, dids)
}
