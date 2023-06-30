package tern.artic

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*


@SpringBootApplication
class ArticApplication

fun main(args: Array<String>) {
    runApplication<ArticApplication>(*args)
}

@RestController
class MessageResource(private val service: ArticService) {
    private val logger = LoggerFactory.getLogger(MessageResource::class.java)
    @GetMapping("/")
    fun find(): List<Message> {
        logger.info("GET / - Retrieving messages")
        return service.find()
    }

    @PostMapping("/")
    fun post(@RequestBody message: Message) {
        logger.info("POST / - Posting message: $message")
        service.save(message)
    }
}

@Service
class MessageService(private val db: MessageRepository) {
    private val logger = LoggerFactory.getLogger(MessageService::class.java)

    fun findMessages(): List<Message> {
        logger.info("Finding messages")
        return db.findMessages()
    }

    fun post(message: Message) {
        logger.info("Saving message: $message")
        db.save(message)
    }
}

@Table("messages")
data class Message(@Id val id: String?, val text: String)

interface MessageRepository : CrudRepository<Message, String> {
    @Query("SELECT * FROM messages")
    fun findMessages(): List<Message>
}