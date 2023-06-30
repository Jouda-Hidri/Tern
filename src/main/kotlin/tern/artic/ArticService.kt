package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tern.artic.grpc.TernServiceGrpc
import tern.artic.grpc.TernServiceOuterClass.SaveRequest

@Service
class ArticService() {
    private val logger = LoggerFactory.getLogger(ArticService::class.java)
    private var channel: ManagedChannel = ManagedChannelBuilder.forAddress("localhost", 9090)
        .usePlaintext()
        .build()
    private var stub = TernServiceGrpc.newBlockingStub(channel)

    fun find(): List<Message> {
        logger.info("Artic - Retrieving messages")
        val response = stub.getMessage(Empty.getDefaultInstance())
        val list: MutableList<Message> = mutableListOf()
        response.forEach { getResponse ->
            list.add(Message(id = null, text = getResponse.text))
        }
        return list
    }

    fun save(message: Message) {
        logger.info("Artic - Request message: $message")
        stub.saveMessage(
            SaveRequest.newBuilder()
                .setText(message.text)
                .build()
        )
    }
}