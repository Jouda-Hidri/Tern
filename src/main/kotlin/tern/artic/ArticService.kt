package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tern.domain.Message
import tern.domain.MessageId
import tern.grpc.TernServiceGrpcKt
import tern.tapi.TapiDownloader
import tern.translate.Detection
import tern.translate.LanguageDetector
import tern.transport.toDomain
import tern.transport.toSaveRequest

@Service
class ArticService(
    channel: ManagedChannel,
    private val languageDetector: LanguageDetector,
    private val tapiDownloader: TapiDownloader,
) {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)

    /** Built lazily, so the channel is only dialled once something actually needs it. */
    private val antarctic by lazy { TernServiceGrpcKt.TernServiceCoroutineStub(channel) }

    /**
     * GetMessage is server-streaming, so the natural return type is a [Flow]: messages are
     * mapped as they arrive rather than after the last one has landed.
     */
    fun findAsFlow(): Flow<Message> {
        logger.debug("Artic - Retrieving messages")
        return antarctic.getMessage(Empty.getDefaultInstance()).map { it.toDomain() }
    }

    suspend fun find(): List<Message> = findAsFlow().toList()
        .also { logger.debug("Artic - Received ${it.size} message(s) from antarctic") }

    /**
     * Saves through antarctic and returns the persisted message. Suspends rather than blocking,
     * but still waits for the outcome: an earlier version answered the caller before the write
     * had happened, so a failure downstream was reported to them as success.
     */
    suspend fun save(message: Message): Message {
        logger.debug("Artic - Saving message: ${message.text}")

        // Detected here rather than in antarctic so the third-party call stays on the edge, and
        // travels on with the message. Absent when the detector is down - by design.
        val enriched = when (val detection = languageDetector.detect(message.text)) {
            is Detection.Detected -> message.copy(language = detection.code)
            Detection.Unrecognised, Detection.Unavailable -> message
        }

        val response = antarctic.saveMessage(enriched.toSaveRequest())
        val saved = enriched.copy(id = MessageId.of(response.id))
        logger.debug("Artic - Antarctic saved message ${saved.id}")

        tapiDownloader.download()
        return saved
    }
}
