package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import app.bsky.jetstream.SubscribeOperation
import bskyviewer.indexer.lucene.Analyser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KeywordField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import org.springframework.stereotype.Component
import java.io.Serializable
import kotlin.io.path.Path

private val logger = KotlinLogging.logger {}

@Component
class Index {
    val prettyJson = Json { // this returns the JsonBuilder
        prettyPrint = true
    }

    val analyzer = Analyser()
    val dir: FSDirectory = FSDirectory.open(Path("index"))
    val config = IndexWriterConfig(analyzer)
    var parser = QueryParser("text", analyzer)
    val writer = IndexWriter(dir, config)
    val searcherManager = SearcherManager(writer, SearcherFactory())

    init {
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
    }

    fun search(term: String): List<Map<String?, List<Serializable>?>> {
        val searcher = searcherManager.acquire()
        try {
            val result = searcher.search(parser.parse(term), 10)
            val storedFields = searcher.storedFields()
            return result.scoreDocs.map {
                val doc = storedFields.document(it.doc)
                doc.groupBy({ f -> f.name() }, { f -> f.numericValue() ?: f.stringValue() })
            }
        } finally {
            searcherManager.release(searcher)
        }
    }

    fun index(it: List<SubscribeMessage>) {
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
            doc.add(KeywordField("rkey", key, Field.Store.YES))
            doc.add(KeywordField("did", value.did.did, Field.Store.NO))
            doc.add(KeywordField("json", prettyJson.encodeToString(value), Field.Store.YES))
            val langs = value.commit?.record?.value?.jsonObject["langs"]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.content
            }?.mapNotNull {
                doc.add(KeywordField("lang", it, Field.Store.YES))
                analyzer.toCode(it)
            }?.toSet() ?: emptySet()
            langs.forEach {
                doc.add(KeywordField("known_lang", it, Field.Store.YES))
            }
            value.commit?.record?.value?.jsonObject["text"]?.jsonPrimitive?.content?.let { text ->
                doc.add(TextField("text", text, Field.Store.NO))
                langs.forEach { lang ->
                    doc.add(TextField("text_$lang", text, Field.Store.NO))
                }
            }
            writer.addDocument(doc)
        }
        writer.commit()
        searcherManager.maybeRefresh()
    }
}
