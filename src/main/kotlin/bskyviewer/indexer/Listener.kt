package bskyviewer.indexer

import app.bsky.jetstream.SubscribeQueryParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.jetstream.JetstreamApi

private val logger = KotlinLogging.logger {}
val params = SubscribeQueryParams(wantedCollections = listOf(Nsid("app.bsky.feed.post")))

@Component
class Listener(val messageQueue: MessageQueue) {
    val client = JetstreamApi()

    @EventListener(ApplicationReadyEvent::class)
    fun startup() {
        mono {
            client.subscribe(params).catch {
                logger.error(it) { "error in subscription" }
                startup()
            }.collect(messageQueue::emit)
        }.subscribe()
    }
}
