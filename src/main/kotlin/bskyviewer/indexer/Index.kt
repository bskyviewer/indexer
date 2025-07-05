package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import app.bsky.jetstream.SubscribeOperation
import bskyviewer.indexer.lucene.Analyser
import bskyviewer.indexer.web.IndexParams
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.util.collections.*
import kotlinx.datetime.Instant
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.microseconds

private val logger = KotlinLogging.logger {}
private val format = DateTimeComponents.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    dayOfMonth()
    char('T')
    hour()
    char(':')
    minute()
    char(':')
    second()
    char('.')
    secondFraction(6)
    char('Z')
}

@Component
class Index(
    val analyzer: Analyser,
    @Value("\${indexer.lucene-ram-limit}") val memoryLimit: Int,
    @Value("\${indexer.lucene-index-dir:#{T(java.nio.file.Files).createTempDirectory('index')}}") val indexPath: Path,
    @Value("\${indexer.stored-fields}") val storedFields: List<String>
) : AutoCloseable {

    init {
        logger.info { "creating index in $indexPath" }
    }

    val langs = ConcurrentSet<String>()
    val dir: FSDirectory = FSDirectory.open(indexPath)
    val config = IndexWriterConfig(analyzer).also {
        it.ramPerThreadHardLimitMB = memoryLimit
    }
    var parser = QueryParser("text_en", analyzer)
    val writer = IndexWriter(dir, config)
    val searcherManager = SearcherManager(writer, SearcherFactory())
    val sortFields: Map<String, SortField> = mapOf(
        "desc" to SortedNumericSortField("time_us", SortField.Type.LONG, true),
        "asc" to SortedNumericSortField("time_us", SortField.Type.LONG, false),
        "relevance" to SortField.FIELD_SCORE,
    )

    init {
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
    }

    fun <T> search(
        params: IndexParams,
        resultMapper: (IndexSearcher, TopFieldDocs, Query?) -> T
    ): T {
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
            val query = builder.build()
            val result = searcher.search(query, params.limit, sort)
            return resultMapper(searcher, result, query)
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
                doc.add(KeywordField("rkey", key, storage("rkey")))
                doc.add(KeywordField("did", value.did.did, storage("did")))

                val record = value.commit?.record?.value?.jsonObject ?: emptyMap()
                val has = HashSet<String>()

                val createdAt = Instant.fromEpochSeconds(0).plus(value.time_us.microseconds)
                doc.add(KeywordField("createdAt", createdAt.format(format), storage("createdAt")))
                doc.add(SortedNumericDocValuesField("time_us", value.time_us))
                if ("timeDebug" in storedFields) doc.add(
                    StoredField("timeDebug", "${value.time_us} / ${record["createdAt"]?.jsonPrimitive?.content}")
                )

                val knownLangs = record["langs"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.content
                }?.mapNotNull {
                    doc.add(KeywordField("lang", it, storage("lang")))
                    analyzer.toCode(it)
                }?.toSet() ?: emptySet()
                if (this.langs.size + knownLangs.size < 1000) {
                    this.langs.addAll(knownLangs)
                }
                knownLangs.forEach {
                    doc.add(KeywordField("known_lang", it, storage("known_lang")))
                }

                record["text"]?.jsonPrimitive?.content?.let { text ->
                    knownLangs.forEach { lang ->
                        doc.add(TextField("text_$lang", text, storage("text", "text_$lang")))
                    }
                }

                record["embed"]?.jsonObject["\$type"]?.jsonPrimitive?.content?.let {
                    doc.add(KeywordField("embed_type", it, storage("embed_type")))
                    has.add("embed")
                }

                if (record["reply"] is JsonObject) doc.add(KeywordField("is", "reply", storage("is")))

                record["facets"]?.jsonArray?.forEach { facet ->
                    listOf("did", "uri", "tag").forEach { type ->
                        facet.jsonObject[type]?.jsonPrimitive?.content?.let { value ->
                            doc.add(KeywordField("facet_${type}", value, storage("facet_${type}")))
                            has.add("facet_${type}")
                            has.add("facet")
                        }
                    }
                }

                record["tags"]?.jsonArray?.forEach {
                    it.jsonPrimitive.content.let { value ->
                        doc.add(KeywordField("tag", value, storage("tag")))
                        has.add("tag")
                    }
                }

                record["labels"]?.jsonObject["values"]?.jsonArray?.forEach {
                    it.jsonObject["val"]?.jsonPrimitive?.content?.let { value ->
                        it.jsonObject["src"]?.jsonPrimitive?.content?.let { did ->
                            doc.add(KeywordField("label", "$did/$value", storage("label")))
                        } ?: run {
                            doc.add(KeywordField("label", value, storage("label")))
                        }
                        has.add("label")
                    }
                }

                has.forEach {
                    doc.add(KeywordField("has", it, storage("has")))
                }
            } catch (e: Exception) {
                logger.error(e) { "error indexing $value" }
                doc.add(KeywordField("error", "error", storage("error")))
            }

            writer.addDocument(doc)
        }
        logger.info { "done indexing ${it.size} messages" }
        writer.commit()
        searcherManager.maybeRefresh()
        logger.info { "commit and refresh ${it.size} messages" }
    }

    private fun storage(vararg fieldName: String): Field.Store {
        return if (fieldName.any { it in storedFields }) Field.Store.YES else Field.Store.NO
    }

    fun size(): String {
        val bytes = indexPath.toFile()
            .walkTopDown()
            .filter { it.isFile }
            .sumOf(File::length)

        return when {
            bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
            bytes >= 1 shl 10 -> "%.0f kB".format(bytes.toDouble() / (1 shl 10))
            else -> "$bytes bytes"
        }
    }

    override fun close() {
        writer.close()
        dir.close()
    }
}
