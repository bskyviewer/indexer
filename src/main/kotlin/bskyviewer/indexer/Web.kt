package bskyviewer.indexer

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

@RestController
class Web(val index: Index) {
    @ResponseBody
    @GetMapping("/")
    fun index(
        @RequestParam(defaultValue = "[* TO *]") q: String,
        @RequestParam(defaultValue = "desc") sort: List<String>
    ) = index.search(q, sort)
}
