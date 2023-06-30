package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.*

@GrpcService
class AntarcticService(private val db: MessageRepository) : TernServiceGrpc.TernServiceImplBase() {
    private val logger = LoggerFactory.getLogger(AntarcticService::class.java)
    override fun getMessage(request: Empty, responseObserver: StreamObserver<GetResponse>) {
        logger.info("Antartic - Retrieving messages")
        val messages = db.findMessages()
        for ((id, text) in messages) {
            responseObserver.onNext(
                GetResponse
                    .newBuilder()
                    .setText(text)
                    .build()
            )
        }
        responseObserver.onCompleted()
    }

    override fun saveMessage(
        request: SaveRequest,
        responseObserver: StreamObserver<SaveResponse>
    ) {
        logger.info("Antartic - Request messages")
        var result = db.save(Message(id = null, text = request.text))
        responseObserver.onNext(
            SaveResponse
                .newBuilder()
                .setId(result.id)
                .build()
        )
        responseObserver.onCompleted()
    }

}