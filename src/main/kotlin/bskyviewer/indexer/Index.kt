package bskyviewer.indexer

import app.bsky.feed.Post
import app.bsky.feed.PostLabelsUnion
import app.bsky.jetstream.SubscribeMessage
import app.bsky.jetstream.SubscribeOperation
import bskyviewer.indexer.lucene.Analyser
import bskyviewer.indexer.util.micros
import bskyviewer.indexer.util.toMicros
import bskyviewer.indexer.web.IndexParams
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.util.collections.*
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.decodeFromJsonElement
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.*
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import sh.christian.ozone.BlueskyJson
import java.io.File
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.full.findAnnotation

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
    var parser = QueryParser("text_en", analyzer)
    var config = IndexWriterConfig(analyzer).also {
        it.ramPerThreadHardLimitMB = memoryLimit
    }
    var dir: FSDirectory = FSDirectory.open(indexPath)
    var writer = IndexWriter(dir, config)
    var searcherManager = SearcherManager(writer, SearcherFactory())
    var sortFields: Map<String, SortField> = mapOf(
        "desc" to SortedNumericSortField("time_us", SortField.Type.LONG, true),
        "asc" to SortedNumericSortField("time_us", SortField.Type.LONG, false),
        "relevance" to SortField.FIELD_SCORE,
    )

    init {
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
    }

    private fun reinit(limit: Int) {
        config = IndexWriterConfig(analyzer).also {
            it.ramPerThreadHardLimitMB = limit
        }
        dir = FSDirectory.open(indexPath)
        writer = IndexWriter(dir, config)
        searcherManager = SearcherManager(writer, SearcherFactory())
        sortFields = mapOf(
            "desc" to SortedNumericSortField("time_us", SortField.Type.LONG, true),
            "asc" to SortedNumericSortField("time_us", SortField.Type.LONG, false),
            "relevance" to SortField.FIELD_SCORE,
        )
    }

    fun <T> search(
        params: IndexParams, resultMapper: (IndexSearcher, TopFieldDocs, Query?) -> T
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
            if (params.before != null || params.after != null) {
                var min = params.after?.toMicros() ?: Long.MIN_VALUE
                var max = params.before?.toMicros() ?: Long.MAX_VALUE
                if (min > Long.MIN_VALUE) min -= 1
                if (max < Long.MAX_VALUE) max += 1
                builder.add(
                    IndexOrDocValuesQuery(
                        LongPoint.newRangeQuery("created", min, max),
                        SortedNumericDocValuesField.newSlowRangeQuery("time_us", min, max)
                    ),
                    BooleanClause.Occur.MUST
                )
            }
            if (params.dids.isNotEmpty()) {
                val terms = object : AbstractCollection<BytesRef>() {
                    override val size = params.dids.size
                    override fun iterator() = params.dids.asSequence().map(::BytesRef).iterator()
                }
                builder.add(TermInSetQuery("did", terms), BooleanClause.Occur.MUST)
            }
            val query = builder.build()
            val limit = if (params.limit > 0) params.limit else searcher.indexReader.maxDoc()
            val result = searcher.search(query, limit, sort)
            return resultMapper(searcher, result, if (params.debug) query else null)
        } catch (e: OutOfMemoryError) {
            config.ramPerThreadHardLimitMB = config.ramPerThreadHardLimitMB * 100 / 9
            logger.error(e) { "out of memory, reducing ram limit to ${config.ramPerThreadHardLimitMB}MB" }
            throw e
        } finally {
            searcherManager.release(searcher)
        }
    }

    fun index(it: List<SubscribeMessage>, fulltext: Boolean) = try {
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

                val record = value.commit?.record?.value?.let { BlueskyJson.decodeFromJsonElement<Post>(it) }
                val has = HashSet<String>()

                val created = min(value.time_us, record?.createdAt?.toMicros() ?: Long.MAX_VALUE)
                doc.add(SortedNumericDocValuesField("time_us", created))
                doc.add(LongPoint("created", created))
                if (storage("createdAt") == Field.Store.YES) doc.add(
                    StoredField("createdAt", created.micros().format(format))
                )

                val knownLangs = record?.langs?.mapNotNull {
                    doc.add(KeywordField("lang", it.tag, storage("lang")))
                    analyzer.toCode(it.tag)
                }?.toSet() ?: emptySet()
                if (this.langs.size + knownLangs.size < 1000) {
                    this.langs.addAll(knownLangs)
                }
                knownLangs.forEach {
                    doc.add(KeywordField("known_lang", it, storage("known_lang")))
                }

                if (fulltext) record?.text?.let { text ->
                    knownLangs.forEach { lang ->
                        doc.add(TextField("text_$lang", text, storage("text", "text_$lang")))
                    }
                }

                record?.embed?.let { it::class.findAnnotation<SerialName>()?.value }?.let {
                    doc.add(KeywordField("embed_type", it, storage("embed_type")))
                    has.add("embed")
                }

                val type = if (record?.reply != null) "reply" else "post"
                doc.add(KeywordField("is", type, storage("is")))

                record?.tags?.forEach { value ->
                    doc.add(KeywordField("tag", value, storage("tag")))
                    has.add("tag")
                }

                (record?.labels as? PostLabelsUnion.SelfLabels)?.value?.values?.forEach {
                    doc.add(KeywordField("label", it.`val`, storage("label")))
                    has.add("label")
                }

                has.forEach {
                    doc.add(KeywordField("has", it, storage("has")))
                }
            } catch (e: Exception) {
                logger.error(e) { "error indexing $value" }
                doc.add(KeywordField("has", "error", storage("has")))
            }

            try {
                writer.addDocument(doc)
            } catch (e: Throwable) {
                if (writer.isOpen) {
                    logger.error(e) { "recoverable error indexing $value" }
                } else {
                    throw e
                }
            }
        }
        logger.trace { "done indexing ${it.size} messages" }
        writer.commit()
        searcherManager.maybeRefresh()
        logger.trace { "commit and refresh ${it.size} messages" }
    } catch (e: OutOfMemoryError) {
        val limit = max(config.ramPerThreadHardLimitMB * 10 / 9, 10)
        logger.info { "out of memory, reducing ram limit to ${limit}MB" }
        writer.rollback()
        reinit(limit)
        throw e
    } catch (e: Throwable) {
        writer.rollback()
        reinit(config.ramPerThreadHardLimitMB)
        throw e
    }

    private fun storage(vararg fieldName: String): Field.Store {
        return if (fieldName.any { it in storedFields }) Field.Store.YES else Field.Store.NO
    }

    fun size(): String {
        val bytes = indexPath.toFile().walkTopDown().filter { it.isFile }.sumOf(File::length)

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
