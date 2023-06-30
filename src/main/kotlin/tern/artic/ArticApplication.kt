package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import tern.artic.grpc.ProfileDescriptorOuterClass
import tern.artic.grpc.ProfileDescriptorOuterClass.SaveRequest
import tern.artic.grpc.ProfileServiceGrpc
import tern.artic.grpc.ProfileServiceGrpc.ProfileServiceImplBase


@SpringBootApplication
class ArticApplication

fun main(args: Array<String>) {
    runApplication<ArticApplication>(*args)
}

@RestController
class MessageResource(private val service: MessageService) { // todo replace with ArticService
    private val logger = LoggerFactory.getLogger(MessageResource::class.java)
    private var channel: ManagedChannel = ManagedChannelBuilder.forAddress("localhost", 9090)
        .usePlaintext()
        .build()
    private var stub: ProfileServiceGrpc.ProfileServiceBlockingStub = ProfileServiceGrpc.newBlockingStub(channel)

    @GetMapping("/")
    fun index(): List<Message> {
        logger.info("GET / - Retrieving messages")
        val response = stub.getMessage(Empty.getDefaultInstance())
        val list: MutableList<Message> = mutableListOf()
        response.forEach { getResponse ->
            list.add(Message(id = null, text = getResponse.text))
        }
        return list
    }

    @PostMapping("/")
    fun post(@RequestBody message: Message) {
        logger.info("POST / - Posting message: $message")
        stub.saveMessage(
            SaveRequest.newBuilder()
                .setText(message.text)
                .build()
        )
    }
}


@GrpcService
class GrpcProfileService(private val db: MessageRepository) : ProfileServiceImplBase() {
    private val log = LoggerFactory.getLogger(GrpcProfileService::class.java)
    override fun getMessage(request: Empty, responseObserver: StreamObserver<ProfileDescriptorOuterClass.GetResponse>) {
        println("get messages")
        val messages = db.findMessages()
        for ((id, text) in messages) {
            responseObserver.onNext(
                ProfileDescriptorOuterClass.GetResponse
                    .newBuilder()
                    .setText(text)
                    .build()
            )
        }
        responseObserver.onCompleted()
    }

    override fun saveMessage(
        request: ProfileDescriptorOuterClass.SaveRequest,
        responseObserver: StreamObserver<ProfileDescriptorOuterClass.SaveResponse>
    ) {
        println("save message $request")
        var result = db.save(Message(id = null, text = request.text))
        responseObserver.onNext(
            ProfileDescriptorOuterClass.SaveResponse
                .newBuilder()
                .setId(result.id)
                .build()
        )
        responseObserver.onCompleted()
    }

}


@Service
class MessageService(private val db: MessageRepository) {
    private val logger = LoggerFactory.getLogger(MessageService::class.java)

    fun findMessages(): List<Message> {
        logger.info("Finding messages")
        return db.findMessages()
    }

    fun post(message: Message) {
        logger.info("Saving message: $message")
        db.save(message)
    }
}

@Table("messages")
data class Message(@Id val id: String?, val text: String)

interface MessageRepository : CrudRepository<Message, String> {
    @Query("SELECT * FROM messages")
    fun findMessages(): List<Message>
}