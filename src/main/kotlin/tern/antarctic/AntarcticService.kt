package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.domain.InvalidMessageException
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import tern.transport.toDomain
import tern.transport.toGetResponse
import tern.transport.toSaveResponse

/**
 * gRPC adapter over [MessageService], on the generated coroutine base class. Server-streaming
 * is a [Flow] and the unary call is a `suspend fun`, so there is no StreamObserver plumbing to
 * get wrong - a returned value completes the call and a thrown [StatusException] fails it.
 *
 * Its only jobs are mapping to and from the wire types and turning failures into a meaningful
 * [Status] instead of letting them leak as UNKNOWN.
 */
@GrpcService
class AntarcticService(
    private val messages: MessageService,
) : TernServiceGrpcKt.TernServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(AntarcticService::class.java)

    override fun getMessage(request: Empty): Flow<GetResponse> {
        logger.debug("Antarctic - Retrieving messages")
        return messages.findAll()
            .map { it.toGetResponse() }
            .catch { throw it.asStatusException() }
    }

    override suspend fun saveMessage(request: SaveRequest): SaveResponse {
        logger.debug("Antarctic - Saving message")
        return try {
            val saved = messages.save(request.toDomain())
            val id = checkNotNull(saved.id) { "Saved message came back without an id" }
            id.toSaveResponse()
        } catch (e: Throwable) {
            throw e.asStatusException()
        }
    }

    /** Maps anything that escapes onto the closest gRPC status, so callers can act on it. */
    private fun Throwable.asStatusException(): StatusException = when (this) {
        is StatusException -> this
        is StatusRuntimeException -> StatusException(status, trailers)
        is InvalidMessageException -> {
            logger.warn("Antarctic - Rejected invalid request: $message")
            Status.INVALID_ARGUMENT.withDescription(message).withCause(this).asException()
        }
        else -> {
            logger.error("Antarctic - Unexpected failure", this)
            Status.INTERNAL.withDescription(message).withCause(this).asException()
        }
    }
}
