package bskyviewer.indexer

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.core.publisher.Sinks
import work.socialhub.kbsky.model.share.RecordUnion
import work.socialhub.kbsky.stream.entity.com.atproto.callback.SyncEventCallback
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

@Service
class MessageQueue : SyncEventCallback {
    val queue = Sinks.many().unicast().onBackpressureBuffer<Message>()

    @EventListener(ApplicationReadyEvent::class)
    fun startup() {
        queue.asFlux().buffer(Duration.ofSeconds(10)).subscribe { println(it.size) }
    }

    override fun onEvent(
        cid: String?,
        uri: String?,
        record: RecordUnion
    ) {
        queue.tryEmitNext(Message(cid, uri, record))
    }
}

data class Message(
    val cid: String?,
    val uri: String?,
    val record: RecordUnion
)