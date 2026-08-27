package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.*

@GrpcService
class AntarcticService(private val db: MessageRepository) : TernServiceGrpcKt.TernServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(AntarcticService::class.java)

    override suspend fun getMessage(request: Empty): GetResponse = try {
        logger.info("Antarctic - Retrieving messages")
        val messages = withContext(Dispatchers.IO) { db.findMessages() }
        GetResponse.newBuilder()
            .addAllMessages(messages.map {
                tern.grpc.TernServiceOuterClass.Message.newBuilder()
                    .setId(it.id ?: "")
                    .setText(it.text)
                    .setLanguage(it.language)
                    .build()
            })
            .build()
    } catch (e: Throwable) {
        throw e.asStatusException()
    }

    override suspend fun saveMessage(request: SaveRequest): SaveResponse = try {
        logger.info("Antarctic - Request messages")
        val result = withContext(Dispatchers.IO) {
            db.save(Message(id = null, text = request.text, language = request.language))
        }
        SaveResponse.newBuilder().setId(result.id).build()
    } catch (e: Throwable) {
        throw e.asStatusException()
    }

    override suspend fun updateLanguage(request: UpdateLanguageRequest): Empty = try {
        logger.info("Antarctic - Updating language of ${request.id}")
        withContext(Dispatchers.IO) { db.updateLanguage(request.id, request.language) }
        Empty.getDefaultInstance()
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
