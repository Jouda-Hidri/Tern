package tern.antarctic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tern.domain.Message
import java.util.concurrent.atomic.AtomicInteger

/**
 * Antarctic's application service: the only thing that talks to the database. [AntarcticService]
 * is a thin gRPC adapter on top of it, which keeps this testable without a server.
 *
 * The repository is blocking JDBC, so every call to it is confined to [Dispatchers.IO] rather
 * than being allowed to occupy a gRPC event-loop thread.
 */
@Service
class MessageService(private val messages: MessageRepository) {
    private val logger = LoggerFactory.getLogger(MessageService::class.java)

    fun findAll(): Flow<Message> {
        val count = AtomicInteger()
        return flow { messages.findMessages().forEach { emit(it.toDomain()) } }
            .onEach { count.incrementAndGet() }
            .flowOn(Dispatchers.IO)
            .onCompletion { logger.debug("Antarctic - Read ${count.get()} message(s) from the database") }
    }

    suspend fun save(message: Message): Message = withContext(Dispatchers.IO) {
        messages.save(MessageEntity.fromDomain(message)).toDomain()
    }.also { logger.debug("Antarctic - Saved message ${it.id} to the database") }
}
