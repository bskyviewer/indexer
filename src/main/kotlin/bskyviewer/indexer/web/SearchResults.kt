package bskyviewer.indexer.web

import org.apache.lucene.index.IndexableField
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopFieldDocs
import org.apache.lucene.search.TotalHits

data class SearchResults(
    val totalHits: TotalHits,
    val result: List<Map<String, Any?>>,
    var query: String? = null,
    var weights: List<Any>? = null
)

fun toSearchResult(
    searcher: IndexSearcher,
    result: TopFieldDocs,
    query: Query?
): SearchResults {
    val weights = ArrayList<Any>()
    val storedFields = searcher.storedFields()
    val response = SearchResults(result.totalHits, result.scoreDocs.map {
        val doc = storedFields.document(it.doc)
        val result: MutableMap<String, Any?> = doc.groupBy(IndexableField::name) { f ->
            f.numericValue() ?: f.stringValue()
        }.toMutableMap()
        listOf("did", "rkey", "createdAt", "timeDebug").forEach { k ->
            (result[k] as? List<*>)?.first()?.let { v ->
                result[k] = v
            }
        }
        query?.let { q ->
            weights.add(searcher.explain(q, it.doc))
        }
        result
    })

    query?.let { q ->
        response.query = q.toString()
        response.weights = weights
    }

    return response
}