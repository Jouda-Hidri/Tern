package tern.artic

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import tern.antarctic.Message

@RestController
class MessageResource(private val service: ArticService) {
    private val logger = LoggerFactory.getLogger(MessageResource::class.java)

    @GetMapping("/")
    suspend fun find(): List<MessageResponse> = withContext(MDCContext()) {
        logger.info("GET / - Retrieving messages")
        service.find().map(MessageResponse::from)
    }

    @GetMapping("/stats")
    suspend fun stats(): MessageStats = withContext(MDCContext()) {
        logger.info("GET /stats - Summarising messages")
        MessageStats.of(service.find())
    }

    @PostMapping("/")
    suspend fun post(@Valid @RequestBody request: MessageRequest): ResponseEntity<MessageResponse> =
        withContext(MDCContext()) {
            logger.info("POST / - Posting message: ${request.text}")
            val message = request.toMessage()
            try {
                val saved = service.save(message)
                ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(saved))
            } catch (e: StatusException) {
                if (e.status.code != Status.Code.DEADLINE_EXCEEDED) throw e
                logger.warn("POST / - antarctic timed out, outcome unknown")
                ResponseEntity.accepted().body(MessageResponse.from(message))
            }
        }
}

data class MessageStats(val total: Int, val byLanguage: Map<String, Int>) {
    companion object {
        fun of(messages: List<Message>): MessageStats = MessageStats(
            total = messages.size,
            byLanguage = messages
                .groupingBy { it.language.ifEmpty { UNKNOWN_LANGUAGE } }
                .eachCount()
                .toSortedMap(compareBy({ it == UNKNOWN_LANGUAGE }, { it })),
        )
    }
}
