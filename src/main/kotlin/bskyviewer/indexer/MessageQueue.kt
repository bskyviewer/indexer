package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType.Application.Json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Sinks
import java.io.File
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Component
class MessageQueue(val index: Index, @Value("\${indexer.buffer-duration}") val bufferDuration: Duration) :
    AutoCloseable {
    val queue = Sinks.many().unicast().onBackpressureBuffer<SubscribeMessage>()
    val messages = ArrayList<SubscribeMessage>();

    init {
        queue.asFlux().buffer(bufferDuration).subscribe { index.index(it) }
    }

    fun emit(message: SubscribeMessage) {
        messages.add(message)
        val result = queue.tryEmitNext(message)
        if (result.isFailure) {
            logger.warn { "failed to emit: $result - message was $message" }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun close() {
        Json {}.encodeToStream(messages, File("src/test/resources/test-data.json").outputStream())
    }
}
