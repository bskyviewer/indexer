package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Component
class MessageQueue(val index: Index) {
    val queue = Sinks.many().unicast().onBackpressureBuffer<SubscribeMessage>()

    init {
        queue.asFlux().buffer(Duration.ofSeconds(5)).subscribe { index.index(it) }
    }

    fun emit(message: SubscribeMessage) {
        val result = queue.tryEmitNext(message)
        if (result.isFailure) {
            logger.warn { "failed to emit: $result - message was $message" }
        }
    }
}
