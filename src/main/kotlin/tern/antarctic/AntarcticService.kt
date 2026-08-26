package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.domain.InvalidMessageException
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import tern.transport.toDomain
import tern.transport.toGetResponse
import tern.transport.toSaveResponse

/**
 * gRPC adapter over [MessageService]. Its only jobs are mapping to and from the wire types and
 * turning failures into a meaningful [Status] instead of letting them leak as UNKNOWN.
 */
@GrpcService
class AntarcticService(private val messages: MessageService) : TernServiceGrpc.TernServiceImplBase() {
    private val logger = LoggerFactory.getLogger(AntarcticService::class.java)

    override fun getMessage(request: Empty, responseObserver: StreamObserver<GetResponse>) {
        logger.info("Antarctic - Retrieving messages")
        respond(responseObserver) {
            messages.findAll().forEach { responseObserver.onNext(it.toGetResponse()) }
        }
    }

    override fun saveMessage(request: SaveRequest, responseObserver: StreamObserver<SaveResponse>) {
        logger.info("Antarctic - Saving message")
        respond(responseObserver) {
            val saved = messages.save(request.toDomain())
            val id = saved.id ?: throw IllegalStateException("Saved message came back without an id")
            responseObserver.onNext(id.toSaveResponse())
        }
    }

    /** Runs [block], completing the call on success and mapping any failure onto a gRPC status. */
    private fun <T> respond(responseObserver: StreamObserver<T>, block: () -> Unit) {
        try {
            block()
            responseObserver.onCompleted()
        } catch (e: InvalidMessageException) {
            logger.warn("Antarctic - Rejected invalid request: ${e.message}")
            responseObserver.onError(
                Status.INVALID_ARGUMENT.withDescription(e.message).withCause(e).asRuntimeException()
            )
        } catch (e: StatusRuntimeException) {
            logger.error("Antarctic - Call failed with ${e.status.code}", e)
            responseObserver.onError(e)
        } catch (e: Exception) {
            logger.error("Antarctic - Unexpected failure", e)
            responseObserver.onError(
                Status.INTERNAL.withDescription(e.message).withCause(e).asRuntimeException()
            )
        }
    }
}
