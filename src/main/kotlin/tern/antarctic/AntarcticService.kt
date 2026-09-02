package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.*
import tern.translate.LanguageDetector

@GrpcService
class AntarcticService(
    private val db: MessageRepository,
    private val languageDetector: LanguageDetector,
) : TernServiceGrpcKt.TernServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(AntarcticService::class.java)

    override fun getMessage(request: Empty): Flow<tern.grpc.TernServiceOuterClass.Message> = flow {
        logger.info("Antarctic - Retrieving messages")
        db.findMessages().forEach {
            emit(
                tern.grpc.TernServiceOuterClass.Message.newBuilder()
                    .setText(it.text)
                    .setLanguage(it.language)
                    .build()
            )
        }
    }
        .flowOn(Dispatchers.IO)
        .catch { throw it.asStatusException() }

    override suspend fun saveMessage(request: SaveRequest): SaveResponse = try {
        logger.info("Antarctic - Request messages")
        val language = languageDetector.detect(request.text)
        withContext(Dispatchers.IO) {
            db.save(Message(id = null, text = request.text, language = language))
        }
        SaveResponse.newBuilder().setLanguage(language).build()
    } catch (e: Throwable) {
        throw e.asStatusException()
    }

    private fun Throwable.asStatusException(): StatusException = when (this) {
        is StatusException -> this
        else -> {
            logger.error("Antarctic - Failed", this)
            Status.INTERNAL.withDescription(message).withCause(this).asException()
        }
    }
}
