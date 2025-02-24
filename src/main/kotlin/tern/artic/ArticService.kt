package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse


@Service
class ArticService() {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private var channel: ManagedChannel = ManagedChannelBuilder.forTarget("antarctic.default.svc.cluster.local:30000")
        .usePlaintext()
        .build()
    private var blockingStub = TernServiceGrpc.newBlockingStub(channel)
    private var stub = TernServiceGrpc.newStub(channel)

    fun find(): List<String> {
        logger.info("Artic - Retrieving messages")
        val response = blockingStub.getMessage(Empty.getDefaultInstance())
        val list: MutableList<String> = mutableListOf()
        response.forEach { getResponse ->
            list.add(getResponse.text)
        }
        return list
    }

    fun save(message: String) {
        logger.info("Artic - Request message: $message")
        stub.saveMessage(
            SaveRequest.newBuilder().setText(message).build(), object : StreamObserver<SaveResponse> {
                override fun onNext(response: SaveResponse?) {
                    logger.warn("Artic - $response")
                }

                override fun onError(throwable: Throwable?) {
                    logger.error("Artic - Error ${throwable?.message}")
                }

                override fun onCompleted() {
                    logger.info("Artic - Completed")
                }
            }
        )
    }

}