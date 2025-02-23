package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.*

@GrpcService
class AntarcticService() : TernServiceGrpc.TernServiceImplBase() {
    private val logger = LoggerFactory.getLogger(AntarcticService::class.java)
    private val list = ArrayList<String>()
    override fun getMessage(request: Empty, responseObserver: StreamObserver<GetResponse>) {
        logger.info("Antartic - Retrieving messages")
        for (message in list) {
            responseObserver.onNext(
                GetResponse
                    .newBuilder()
                    .setText(message)
                    .build()
            )
        }
        responseObserver.onCompleted()
    }

    override fun saveMessage(
        request: SaveRequest,
        responseObserver: StreamObserver<SaveResponse>
    ) {
        logger.info("Antartic - Save message {}", request.text)
        list.add(request.text)
        responseObserver.onNext(
            SaveResponse
                .newBuilder()
                .setId(list.lastIndex.toString())
                .build()
        )
        responseObserver.onCompleted()
    }

}