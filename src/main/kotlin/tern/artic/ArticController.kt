package tern.artic

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class MessageResource(private val service: ArticService) {
    private val logger = LoggerFactory.getLogger(MessageResource::class.java)
    @GetMapping("/")
    fun find(): List<String> {
        logger.info("GET / - Retrieving messages")
        return service.find()
    }

    @PostMapping("/")
    fun post(@RequestBody message: String) {
        logger.info("POST / - Posting message: $message")
        service.save(message)
    }
}