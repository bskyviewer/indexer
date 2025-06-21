package bskyviewer.indexer

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import work.socialhub.kbsky.model.share.RecordUnion
import work.socialhub.kbsky.stream.ATProtocolStreamFactory
import work.socialhub.kbsky.stream.api.entity.com.atproto.SyncSubscribeReposRequest
import work.socialhub.kbsky.stream.entity.com.atproto.callback.SyncEventCallback

@Component
class Listener(val messageQueue: MessageQueue) {

    @EventListener(ApplicationReadyEvent::class)
    fun startup() {
        Mono.fromCallable {
            val stream = ATProtocolStreamFactory.instance().sync().subscribeRepos(SyncSubscribeReposRequest())
            stream.eventCallback(messageQueue)
        }.subscribeOn(Schedulers.boundedElastic()).subscribe()
    }
}