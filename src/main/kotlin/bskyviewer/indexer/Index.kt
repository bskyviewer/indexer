package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import app.bsky.jetstream.SubscribeOperation
import bskyviewer.indexer.lucene.Analyser
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.util.collections.ConcurrentSet
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexableField
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.time.Duration.Companion.microseconds

private val logger = KotlinLogging.logger {}

@Component
class Index(
    val analyzer: Analyser,
    @Value("\${indexer.lucene-ram-limit}") val memoryLimit: Int,
    @Value("\${indexer.lucene-index-dir:/tmp/index}") val indexPath: Path
) : AutoCloseable {
    val langs = ConcurrentSet<String>()
    val dir: FSDirectory = FSDirectory.open(indexPath)
    val config = IndexWriterConfig(analyzer).also {
        it.ramPerThreadHardLimitMB = memoryLimit
    }
    var parser = QueryParser("text_en", analyzer)
    val writer = IndexWriter(dir, config)
    val searcherManager = SearcherManager(writer, SearcherFactory())
    val sortFields: Map<String, SortField> = mapOf(
        "desc" to SortedNumericSortField("time_ms", SortField.Type.LONG, true),
        "asc" to SortedNumericSortField("time_ms", SortField.Type.LONG, false),
        "relevance" to SortField.FIELD_SCORE,
    )

    init {
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
    }

    fun search(params: Web.IndexParams): HashMap<String, Any> {
        logger.info { params }
        val searcher = searcherManager.acquire()
        try {
            val sort = Sort(*params.sort.map { sortFields[it] }.toTypedArray())
            val builder = BooleanQuery.Builder()
            if (params.q.isNotBlank()) {
                builder.add(parser.parse(params.q), BooleanClause.Occur.MUST)
            } else {
                builder.add(MatchAllDocsQuery(), BooleanClause.Occur.SHOULD)
            }
            if (params.dids.isNotEmpty()) {
                builder.add(KeywordField.newSetQuery("did", params.dids.map(::BytesRef)), BooleanClause.Occur.MUST)
            }
            val response = HashMap<String, Any>()
            val query = builder.build()
            val weights = ArrayList<Any>()
            if (params.debug) {
                response["query"] = query.toString()
                response["weights"] = weights
            }
            val result = searcher.search(query, params.limit, sort)
            val storedFields = searcher.storedFields()
            response["totalHits"] = result.totalHits
            response["result"] = result.scoreDocs.map {
                val doc = storedFields.document(it.doc)
                val result: MutableMap<String, Any?> = doc.groupBy(IndexableField::name) { f ->
                    f.numericValue() ?: f.stringValue()
                }.toMutableMap()
                listOf("did", "rkey", "createdAt").forEach { k ->
                    result[k] = (result[k] as? List<*>)?.first()
                }
                if (params.debug) {
                    weights.add(searcher.explain(query, it.doc))
                }
                result
            }

            return response
        } finally {
            searcherManager.release(searcher)
        }
    }

    fun index(it: List<SubscribeMessage>) {
        logger.info { "indexing ${it.size} messages" }
        val created = HashMap<String, SubscribeMessage>()
        val deleted = ArrayList<String>()
        it.forEach {
            it.commit?.rkey?.rkey?.let { key ->
                when (it.commit?.operation) {
                    SubscribeOperation.Create, SubscribeOperation.Update -> created.put(key, it)
                    SubscribeOperation.Delete -> deleted.add(key)
                    else -> logger.info { "unknown operation: $it" }
                }
            }
        }
        created.keys.removeAll(deleted)

        val delete = KeywordField.newSetQuery("rkey", (deleted + created.keys).map(::BytesRef))
        writer.deleteDocuments(delete)
        created.forEach { (key, value) ->
            val doc = Document()

            try {
                doc.add(KeywordField("rkey", key, Field.Store.YES))
                doc.add(KeywordField("did", value.did.did, Field.Store.YES))

                val record = value.commit?.record?.value?.jsonObject ?: emptyMap()
                val has = HashSet<String>()

                val createdAt = record["createdAt"]?.jsonPrimitive?.content ?: Instant.fromEpochSeconds(0)
                    .plus(value.time_us.microseconds).toString()
                doc.add(KeywordField("createdAt", createdAt, Field.Store.YES))
                doc.add(SortedNumericDocValuesField("time_ms", Instant.parse(createdAt).toEpochMilliseconds()))

                val knownLangs = record["langs"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.content
                }?.mapNotNull {
                    doc.add(KeywordField("lang", it, Field.Store.YES))
                    analyzer.toCode(it)
                }?.toSet() ?: emptySet()
                if (this.langs.size + knownLangs.size < 500) {
                    this.langs.addAll(knownLangs)
                }
                knownLangs.forEach {
                    doc.add(KeywordField("known_lang", it, Field.Store.YES))
                }

                record["text"]?.jsonPrimitive?.content?.let { text ->
                    knownLangs.forEach { lang ->
                        doc.add(TextField("text_$lang", text, Field.Store.NO))
                    }
                }

                record["embed"]?.jsonObject["\$type"]?.jsonPrimitive?.content?.let {
                    doc.add(KeywordField("embed", it, Field.Store.NO))
                    has.add("embed")
                }

                if (record["reply"] is JsonObject) doc.add(KeywordField("is", "reply", Field.Store.NO))

                record["facets"]?.jsonArray?.forEach { facet ->
                    listOf("did", "uri", "tag").forEach { type ->
                        facet.jsonObject[type]?.jsonPrimitive?.content?.let { value ->
                            doc.add(KeywordField("facet_${type}", value, Field.Store.NO))
                            has.add("facet_${type}")
                            has.add("facet")
                        }
                    }
                }

                record["tags"]?.jsonArray?.forEach {
                    it.jsonPrimitive.content.let { value ->
                        doc.add(KeywordField("tag", value, Field.Store.NO))
                        has.add("tag")
                    }
                }

                record["labels"]?.jsonObject["values"]?.jsonArray?.forEach {
                    it.jsonObject["val"]?.jsonPrimitive?.content?.let { value ->
                        doc.add(KeywordField("label", value, Field.Store.NO))
                        has.add("label")
                    }
                }

                has.forEach {
                    doc.add(KeywordField("has", it, Field.Store.NO))
                }
            } catch (e: Exception) {
                logger.error(e) { "error indexing $value" }
                doc.add(KeywordField("error", "error", Field.Store.YES))
            }

            writer.addDocument(doc)
        }
        logger.info { "done indexing ${it.size} messages" }
        writer.commit()
        searcherManager.maybeRefresh()
        logger.info { "commit and refresh ${it.size} messages" }
    }

    override fun close() {
        writer.close()
        dir.close()
    }
}
