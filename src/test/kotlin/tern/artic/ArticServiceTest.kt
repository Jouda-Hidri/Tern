package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import tern.grpc.TernServiceGrpc
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import java.util.UUID

/**
 * Runs against a real gRPC server over the in-process transport, so the stub, the status codes
 * and the mapping are all genuinely exercised - only the remote implementation is faked.
 *
 * tapi is pointed at a dead port on purpose: the download is a fire-and-forget side effect and
 * must never affect the outcome of a save.
 */
class ArticServiceTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var service: ArticService

    private var behaviour: FakeAntarctic = FakeAntarctic()

    @BeforeEach
    fun startServer() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(behaviour)
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        service = ArticService(channel, WebClient.create(DEAD_TAPI), DEAD_TAPI)
    }

    @AfterEach
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `maps every message antarctic streams back into the domain`() {
        val id = UUID.randomUUID()
        behaviour.messages = listOf(
            GetResponse.newBuilder().setId(id.toString()).setText("Hello!").build(),
            GetResponse.newBuilder().setId(UUID.randomUUID().toString()).setText("Bonjour!").build(),
        )

        val found = service.find()

        assertThat(found).hasSize(2)
        assertThat(found.first()).isEqualTo(Message(MessageId(id), MessageText("Hello!")))
    }

    @Test
    fun `returns the saved message carrying the id antarctic assigned`() {
        val id = UUID.randomUUID()
        behaviour.assignedId = id

        val saved = service.save(Message(MessageText("Privet!")))

        assertThat(saved.id).isEqualTo(MessageId(id))
        assertThat(saved.text).isEqualTo(MessageText("Privet!"))
        assertThat(behaviour.received).containsExactly("Privet!")
    }

    @Test
    fun `a failing save is surfaced to the caller instead of being reported as success`() {
        behaviour.failWith = Status.UNAVAILABLE

        assertThatThrownBy { service.save(Message(MessageText("Privet!"))) }
            .isInstanceOf(StatusRuntimeException::class.java)
            .extracting { Status.fromThrowable(it as Throwable).code }
            .isEqualTo(Status.Code.UNAVAILABLE)
    }

    private class FakeAntarctic : TernServiceGrpc.TernServiceImplBase() {
        var messages: List<GetResponse> = emptyList()
        var assignedId: UUID = UUID.randomUUID()
        var failWith: Status? = null
        val received = mutableListOf<String>()

        override fun getMessage(request: Empty, responseObserver: StreamObserver<GetResponse>) {
            failWith?.let { return responseObserver.onError(it.asRuntimeException()) }
            messages.forEach(responseObserver::onNext)
            responseObserver.onCompleted()
        }

        override fun saveMessage(request: SaveRequest, responseObserver: StreamObserver<SaveResponse>) {
            failWith?.let { return responseObserver.onError(it.asRuntimeException()) }
            received += request.text
            responseObserver.onNext(SaveResponse.newBuilder().setId(assignedId.toString()).build())
            responseObserver.onCompleted()
        }
    }

    private companion object {
        /** Nothing listens here; any attempt to reach tapi fails fast. */
        const val DEAD_TAPI = "http://localhost:1"
    }
}
