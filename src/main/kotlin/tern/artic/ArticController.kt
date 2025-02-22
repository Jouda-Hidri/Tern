package tern.artic

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tern.antarctic.Message

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