package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tern.antarctic.Message
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.SaveRequest
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service
class ArticService(
    channel: ManagedChannel,
    @Value("\${tern.antarctic.deadline:2s}") private val deadline: Duration,
) {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private val antarctic = TernServiceGrpcKt.TernServiceCoroutineStub(channel)

    // Without a deadline an unreachable antarctic is waited on rather than failing.
    private fun stub() = antarctic.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)

    fun find(): Flow<Message> {
        logger.info("Artic - Retrieving messages")
        return stub().getMessage(Empty.getDefaultInstance())
            .map { Message(id = null, text = it.text, language = it.language) }
    }

    suspend fun save(message: Message): Message {
        logger.info("Artic - Request message: ${message.text}")
        val response = stub().saveMessage(SaveRequest.newBuilder().setText(message.text).build())
        return message.copy(language = response.language)
    }
}
