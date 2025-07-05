package bskyviewer.indexer.web

import app.bsky.feed.GetFeedSkeletonResponse
import app.bsky.feed.SkeletonFeedPost
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopFieldDocs
import sh.christian.ozone.api.AtUri

fun toFeedSkeleton(searcher: IndexSearcher, topDocs: TopFieldDocs, query: Query?): GetFeedSkeletonResponse {
    var cursor: String? = null
    val storedFields = searcher.storedFields()
    val feed = topDocs.scoreDocs.map {
        val doc = storedFields.document(it.doc)
        cursor = doc.getField("createdAt").stringValue()
        val rkey = doc.getField("rkey").stringValue()
        val did = doc.getField("did").stringValue()
        SkeletonFeedPost(AtUri("at://${did}/app.bsky.feed.post/${rkey}"))
    }

    return GetFeedSkeletonResponse(cursor, feed)
}