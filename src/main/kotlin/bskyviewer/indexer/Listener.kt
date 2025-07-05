package bskyviewer.indexer

import app.bsky.jetstream.SubscribeQueryParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.catch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.jetstream.JetstreamApi

private val logger = KotlinLogging.logger {}
val params = SubscribeQueryParams(wantedCollections = listOf(Nsid("app.bsky.feed.post")))

@Component
@Profile("!test")
class Listener(val messageQueue: MessageQueue) {
    val client = JetstreamApi()

    @EventListener(ApplicationReadyEvent::class)
    suspend fun startup() {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun start() = logger.info(Exception("halt")) { "stack trace" }
        })

        client.subscribe(params).catch {
            logger.error(it) { "error in subscription" }
            startup()
        }.collect(messageQueue::emit)
    }
}
