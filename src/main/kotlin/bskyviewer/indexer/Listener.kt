package bskyviewer.indexer

import app.bsky.jetstream.SubscribeQueryParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import reactor.core.scheduler.Schedulers
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.jetstream.JetstreamApi

private val logger = KotlinLogging.logger {}

@Component
class Listener(val messageQueue: MessageQueue) {
    val client = JetstreamApi()

    @EventListener(ApplicationReadyEvent::class)
    fun startup() {
        mono {
            val subscription =
                client.subscribe(SubscribeQueryParams(wantedCollections = listOf(Nsid("app.bsky.feed.post"))))
            subscription.collect { messageQueue.emit(it) }
            subscription
        }.subscribeOn(Schedulers.parallel()).subscribe()
    }
}
