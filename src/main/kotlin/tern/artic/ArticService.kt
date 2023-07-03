package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tern.antarctic.Message
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
    var client: WebClient = WebClient.create("http://tapi.default.svc.cluster.local:5000")

    fun find(): List<Message> {
        logger.info("Artic - Retrieving messages")
        val response = blockingStub.getMessage(Empty.getDefaultInstance())
        val list: MutableList<Message> = mutableListOf()
        response.forEach { getResponse ->
            list.add(Message(id = null, text = getResponse.text))
        }
        return list
    }

    fun save(message: Message) {
        logger.info("Artic - Request message: $message")
        stub.saveMessage(
            SaveRequest.newBuilder().setText(message.text).build(), object: StreamObserver<SaveResponse> {
                override fun onNext(response: SaveResponse?) {
                    // todo possible to use response as path param
                    logger.warn("Artic - $response")
                }

                override fun onError(throwable: Throwable?) {
                    logger.error("Artic - Error ${throwable?.message}")
                }

                override fun onCompleted() {
                    val status = client.get()
                        .uri("/")
                        .exchangeToMono { it.toEntity(Void::class.java) }
                        .map { it.statusCode }
                        .block()
                        ?.value()
                    logger.info("Artic - status $status")
                }
            }
        )
    }
}