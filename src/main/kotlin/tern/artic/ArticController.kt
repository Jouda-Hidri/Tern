package tern.artic

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Handlers suspend rather than block. Each body runs inside `withContext(MDCContext())`:
 * Spring starts the coroutine on the servlet thread, so constructing it here captures the MDC
 * that RequestIdFilter just populated and reinstates it after every dispatcher hop - otherwise
 * the request id would vanish from the log lines the moment anything switched threads.
 */
@RestController
class MessageResource(private val service: ArticService) {
    private val logger = LoggerFactory.getLogger(MessageResource::class.java)

    @GetMapping("/")
    suspend fun find(): List<MessageResponse> = withContext(MDCContext()) {
        logger.debug("GET / - Retrieving messages")
        service.find().map(MessageResponse::fromDomain)
    }

    @GetMapping("/stats")
    suspend fun stats(): MessageStats = withContext(MDCContext()) {
        logger.debug("GET /stats - Summarising messages")
        MessageStats.of(service.find())
    }

    @PostMapping("/")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun post(@RequestBody request: MessageRequest): MessageResponse = withContext(MDCContext()) {
        logger.debug("POST / - Posting message: ${request.text}")
        MessageResponse.fromDomain(service.save(request.toDomain()))
    }
}
