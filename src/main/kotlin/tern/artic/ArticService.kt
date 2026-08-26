package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import tern.domain.Message
import tern.domain.MessageId
import tern.grpc.TernServiceGrpc
import tern.tracing.RequestId
import tern.transport.toDomain
import tern.transport.toSaveRequest

@Service
class ArticService(
    channel: ManagedChannel,
    private val tapiClient: WebClient,
    @Value("\${tern.tapi.url}") private val tapiUrl: String,
) {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private val blockingStub = TernServiceGrpc.newBlockingStub(channel)

    fun find(): List<Message> {
        logger.info("Artic - Retrieving messages")
        val found = blockingStub.getMessage(Empty.getDefaultInstance())
            .asSequence()
            .map { it.toDomain() }
            .toList()
        logger.info("Artic - Received ${found.size} message(s) from antarctic")
        return found
    }

    /**
     * Saves through antarctic and returns the persisted message. This blocks on purpose: the
     * previous async version let the HTTP layer answer 200 before the write had happened, so a
     * failure downstream was reported to the caller as success.
     */
    fun save(message: Message): Message {
        logger.info("Artic - Saving message: ${message.text}")
        val response = blockingStub.saveMessage(message.toSaveRequest())
        val saved = message.copy(id = MessageId.of(response.id))
        logger.info("Artic - Antarctic saved message ${saved.id}")

        streamFromTapi()
        return saved
    }

    /**
     * Fires the tapi download without blocking the caller: it is a side effect of saving, not
     * part of the answer. tapi is a separate deployment and may not be running at all (it is
     * absent from docker-compose), so failures are logged rather than propagated.
     *
     * `bodyToFlux(String)` splits the response on newlines as it arrives, which is the point of
     * the exercise - the CSV is never held in memory in one piece.
     */
    private fun streamFromTapi() {
        val requestId = RequestId.current()
        tapiClient.get()
            .retrieve()
            .bodyToFlux(String::class.java)
            .doOnNext { line -> RequestId.withRequestId(requestId) { logger.info("Tapi - $line") } }
            .doOnComplete { RequestId.withRequestId(requestId) { logger.info("Tapi - Download completed.") } }
            .doOnError { e ->
                RequestId.withRequestId(requestId) {
                    logger.warn("Tapi - Unreachable at $tapiUrl, skipping download: ${e.message}")
                }
            }
            .onErrorResume { Flux.empty() }
            .subscribe()
    }
}
