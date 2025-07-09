package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import app.bsky.jetstream.SubscribeQueryParams
import bskyviewer.indexer.util.micros
import bskyviewer.indexer.util.toMicros
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.time.delay
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.jetstream.JetstreamApi
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger = KotlinLogging.logger {}
const val offset = 1000L
val wantedCollections = listOf(Nsid("app.bsky.feed.post"))

@Component
@Profile("!test")
@OptIn(ExperimentalAtomicApi::class)
class Listener(
    @Value("\${indexer.jetstream-compression}") val compress: Boolean,
    @Value("\${indexer.jetstream-history}") val history: Duration?,
    @Value("\${indexer.buffer-duration}") val bufferDuration: Duration,
    @Value("\${indexer.buffer-capacity}") val bufferSize: Int,
    @Value("\${indexer.buffer-wakeness}") val bufferWake: Int,
    val index: Index,
) :
    AutoCloseable {
    val client = JetstreamApi()
    var buffer = ArrayList<SubscribeMessage>(bufferSize)
    var cursor: Long? = history?.let { Instant.now().minus(it).toMicros() } ?: offset
    var sleeping: Job? = null
    var running = true

    @EventListener(ApplicationStartedEvent::class)
    suspend fun startup() = coroutineScope {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun start() = logger.info(Exception("halt")) { "stack trace" }
        })

        var indexing: Job? = null
        var i = 0

        while (running) try {
            val loop = i
            val subscription = launch(Dispatchers.IO) {
                logger.trace { "starting subscription ${i++}" }
                subscribe()
                logger.trace { "ending subscription $loop" }
            }

            // This inner indexing loop repeats as long as indexing takes less time than filling the message buffer
            // If the buffer fills up, the subscription will end, breaking out of this inner loop and restarting the outer loop.
            // The next buffer will start loading, to be processed after the currently running indexing task ends.
            var nextRun = Instant.now().plus(bufferDuration)
            while (running && subscription.isActive) {
                try {
                    indexing?.join()
                } catch (e: CancellationException) {
                    logger.info(e) { "ignoring cancellation" }
                }
                indexing = null
                val wait = Duration.between(Instant.now(), nextRun)
                if (wait.isPositive && syncBuffer { it.size } < bufferWake) {
                    sleeping = launch { delay(wait) }
                    select {
                        sleeping?.onJoin { logger.trace { "slept $wait" } }
                        subscription.onJoin { logger.trace { "subscription ended, stopping sleep" } }
                    }
                }
                val buf = syncBuffer {
                    buffer = ArrayList(bufferSize)
                    it
                }
                if (buf.isNotEmpty()) {
                    indexing = launch(Dispatchers.Default) {
                        logger.trace { "indexing $loop" }
                        try {
                            index.index(buf, true)
                        } catch (e: Throwable) {
                            logger.warn(e) {
                                "retrying without full text. messages: ${
                                    buf.joinToString("\n") { "${it.did}/${it.commit?.rkey}" }
                                }"
                            }
                            index.index(buf, false)
                        }
                        logger.trace { "indexing done $loop" }
                        val time = buf.maxOf { message -> message.time_us }.micros()
                        logger.info { "indexed ${buf.size} messages to $time" }
                    }
                }
                nextRun = Instant.now().plus(bufferDuration)
            }
            try {
                subscription.cancelAndJoin()
            } catch (e: CancellationException) {
                logger.trace(e) { "subscription canceled" }
            }
        } catch (e: Throwable) {
            logger.error(e) { "error in listener loop" }
        }
    }

    private suspend fun subscribe() {
        var skipped = 0
        connect().takeWhile { hasBufferSpace(it) }.collect {
            if (it.time_us >= (cursor ?: 0)) {
                if (skipped >= 0) {
                    logger.info { "skipped $skipped messages" }
                    skipped = -1
                }
                syncBuffer { buf -> buf.add(it) }
            } else if (skipped++ < 0) {
                skipped = 1
            }
        }
    }

    private suspend fun connect(): Flow<SubscribeMessage> {
        var backOff = Duration.ofMillis(500)
        while (true) {
            try {
                return client.subscribe(
                    SubscribeQueryParams(
                        wantedCollections = wantedCollections,
                        compress = compress,
                        cursor = cursor?.minus(offset),
                        maxMessageSizeBytes = 50000
                    )
                )
            } catch (e: Exception) {
                logger.error(e) { "error connecting to jetstream" }
                delay(backOff)
                backOff = backOff.plus(backOff)
            }
        }
    }

    fun hasBufferSpace(message: SubscribeMessage): Boolean {
        val count = syncBuffer { it.size }
        if (count < bufferSize) {
            if (count >= bufferWake) this.sleeping?.cancel()
            return true
        }
        cursor = message.time_us
        logger.info { "buffer full ($count), cursor ${message.time_us.micros()}" }
        return false
    }

    fun <T> syncBuffer(block: (buf: ArrayList<SubscribeMessage>) -> T): T = synchronized(this) {
        block(buffer)
    }


    override fun close() {
        running = false
    }
}
