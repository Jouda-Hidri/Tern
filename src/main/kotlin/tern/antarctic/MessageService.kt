package tern.antarctic

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tern.domain.Message

/**
 * Antarctic's application service: the only thing that talks to the database. [AntarcticService]
 * is a thin gRPC adapter on top of it, which keeps this testable without a server.
 */
@Service
class MessageService(private val messages: MessageRepository) {
    private val logger = LoggerFactory.getLogger(MessageService::class.java)

    fun findAll(): List<Message> {
        val found = messages.findMessages().map { it.toDomain() }
        logger.info("Antarctic - Read ${found.size} message(s) from the database")
        return found
    }

    fun save(message: Message): Message {
        val saved = messages.save(MessageEntity.fromDomain(message)).toDomain()
        logger.info("Antarctic - Saved message ${saved.id} to the database")
        return saved
    }
}
