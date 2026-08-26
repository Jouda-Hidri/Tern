package tern.artic

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class MessageResource(private val service: ArticService) {
    private val logger = LoggerFactory.getLogger(MessageResource::class.java)

    @GetMapping("/")
    fun find(): List<MessageResponse> {
        logger.info("GET / - Retrieving messages")
        return service.find().map(MessageResponse::fromDomain)
    }

    @PostMapping("/")
    @ResponseStatus(HttpStatus.CREATED)
    fun post(@RequestBody request: MessageRequest): MessageResponse {
        logger.info("POST / - Posting message: ${request.text}")
        return MessageResponse.fromDomain(service.save(request.toDomain()))
    }
}
