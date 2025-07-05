package bskyviewer.indexer

import app.bsky.jetstream.SubscribeMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.File

private val logger = KotlinLogging.logger {}

@Component
@Primary
@Profile("test")
class MockListener(val messageQueue: MessageQueue) {
    
    @EventListener(ApplicationReadyEvent::class)
    suspend fun startup() {
        logger.info { "Starting mock listener" }
        
        // Read test data from file
        val testDataFile = File("src/test/resources/test-data.json")
        val testDataJson = testDataFile.readText()
        
        // Parse JSON to list of SubscribeMessage
        val json = Json { ignoreUnknownKeys = true }
        val messages = json.decodeFromString<List<SubscribeMessage>>(testDataJson)
        
        logger.info { "Loaded ${messages.size} messages from test data" }
        
        // Create a flow from the messages and collect them to messageQueue::emit
        createMessageFlow(messages).collect(messageQueue::emit)
        
        logger.info { "Finished processing test data" }
    }
    
    private fun createMessageFlow(messages: List<SubscribeMessage>): Flow<SubscribeMessage> = flow {
        for (message in messages) {
            emit(message)
        }
    }
}