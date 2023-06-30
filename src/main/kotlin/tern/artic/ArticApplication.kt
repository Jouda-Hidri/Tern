package tern.artic

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import tern.artic.grpc.ProfileDescriptorOuterClass.ProfileDescriptor
import tern.artic.grpc.ProfileServiceGrpc.ProfileServiceImplBase

@SpringBootApplication
class ArticApplication

fun main(args: Array<String>) {
	runApplication<ArticApplication>(*args)
}

@RestController
class MessageResource(private val service: MessageService) {
	private val logger = LoggerFactory.getLogger(MessageResource::class.java)
// todo call gRPC service, and then the gRPC calls the persistence
	// todo replace the MessageService with the gRPC service
	@GetMapping("/")
	fun index(): List<Message> {
		logger.info("GET / - Retrieving messages")
		return service.findMessages()
	}

	@PostMapping("/")
	fun post(@RequestBody message: Message) {
		logger.info("POST / - Posting message: $message")
		service.post(message)
	}
}


@GrpcService
class GrpcProfileService(private val db: MessageRepository) : ProfileServiceImplBase() {
	private val log = LoggerFactory.getLogger(GrpcProfileService::class.java)
	override fun getMessage(request: Empty, responseObserver: StreamObserver<ProfileDescriptor>) {
		println("getCurrentProfile")
		val messages = db.findMessages()
		for ((id, text) in messages) {
			responseObserver.onNext(
				ProfileDescriptor
					.newBuilder()
					.setProfileId(id)
					.setText(text)
					.build()
			)
		}
		responseObserver.onCompleted()
	} // todo saveMessage

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