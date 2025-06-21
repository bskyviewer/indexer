package bskyviewer.indexer

import app.bsky.jetstream.SubscribeQueryParams
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.jetstream.JetstreamApi

@Component
class Listener(val messageQueue: MessageQueue) {
    val client = JetstreamApi()

    @EventListener(ApplicationReadyEvent::class)
    fun startup() {
        mono {
            client.subscribe(SubscribeQueryParams(wantedCollections = listOf(Nsid("app.bsky.feed.post"))))
                .also { it.collect(messageQueue::emit) }
        }.subscribe()
    }
}
