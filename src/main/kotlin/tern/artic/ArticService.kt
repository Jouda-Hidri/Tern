package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tern.antarctic.Message
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.UpdateLanguageRequest
import tern.tracing.RequestId
import tern.translate.LanguageDetector
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service
class ArticService(
    channel: ManagedChannel,
    private val languageDetector: LanguageDetector,
    @Value("\${tern.antarctic.deadline:2s}") private val deadline: Duration,
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private val antarctic = TernServiceGrpcKt.TernServiceCoroutineStub(channel)

    // Detection runs after the response has been sent, so it needs a scope that outlives the
    // request but not the application. SupervisorJob keeps one failure from cancelling others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("detect"))

    // Without a deadline an unreachable antarctic is waited on rather than failing.
    private fun stub() = antarctic.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)

    suspend fun find(): List<Message> {
        logger.info("Artic - Retrieving messages")
        return stub().getMessage(Empty.getDefaultInstance())
            .messagesList
            .map { Message(id = it.id.ifEmpty { null }, text = it.text, language = it.language) }
    }

    suspend fun save(message: Message): Message {
        logger.info("Artic - Request message: ${message.text}")
        val response = stub().saveMessage(SaveRequest.newBuilder().setText(message.text).build())
        val saved = message.copy(id = response.id)

        detectLanguageInBackground(saved)
        return saved
    }

    private fun detectLanguageInBackground(message: Message) {
        val id = message.id ?: return
        // Carried explicitly: the launched coroutine starts with an empty MDC, so without this
        // the update would be logged - and traced to antarctic - under a new request id.
        val mdc = MDCContext(mapOf(RequestId.MDC_KEY to (RequestId.current() ?: "")))
        scope.launch(mdc) {
            val language = languageDetector.detect(message.text)
            if (language.isEmpty()) return@launch
            try {
                stub().updateLanguage(
                    UpdateLanguageRequest.newBuilder().setId(id).setLanguage(language).build()
                )
                logger.info("Artic - Language of $id is $language")
            } catch (e: Exception) {
                logger.warn("Artic - Could not store language: ${e.message}")
            }
        }
    }

    override fun destroy() = scope.cancel("Application is shutting down")
}
