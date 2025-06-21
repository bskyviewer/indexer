package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import reactor.core.publisher.Sinks
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class MessageQueue {
    val queue = Sinks.many().unicast().onBackpressureBuffer<SubscribeMessage>()

    @EventListener(ApplicationReadyEvent::class)
    fun startup() {
        queue.asFlux().buffer(Duration.ofSeconds(10)).subscribe { println(it.size) }
        logger.info { "subscribed" }
    }

    fun emit(message: SubscribeMessage) {
        queue.tryEmitNext(message)
    }
}
