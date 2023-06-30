package tern.artic

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import tern.artic.grpc.ProfileServiceGrpc
import tern.artic.grpc.TernService

@GrpcService
class GrpcProfileService(private val db: MessageRepository) : ProfileServiceGrpc.ProfileServiceImplBase() {
    private val log = LoggerFactory.getLogger(GrpcProfileService::class.java)
    override fun getMessage(request: Empty, responseObserver: StreamObserver<TernService.GetResponse>) {
        println("get messages")
        val messages = db.findMessages()
        for ((id, text) in messages) {
            responseObserver.onNext(
                TernService.GetResponse
                    .newBuilder()
                    .setText(text)
                    .build()
            )
        }
        responseObserver.onCompleted()
    }

    override fun saveMessage(
        request: TernService.SaveRequest,
        responseObserver: StreamObserver<TernService.SaveResponse>
    ) {
        println("save message $request")
        var result = db.save(Message(id = null, text = request.text))
        responseObserver.onNext(
            TernService.SaveResponse
                .newBuilder()
                .setId(result.id)
                .build()
        )
        responseObserver.onCompleted()
    }

}