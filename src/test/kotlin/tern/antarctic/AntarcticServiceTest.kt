package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import java.util.UUID

/**
 * Exercises the gRPC adapter directly with a stubbed observer - no server needed, because the
 * only logic here is mapping and error translation.
 */
class AntarcticServiceTest {

    private val messages = mockk<MessageService>()
    private val service = AntarcticService(messages)

    @Test
    fun `streams every stored message back, id included`() {
        val id = UUID.randomUUID()
        every { messages.findAll() } returns listOf(Message(MessageId(id), MessageText("Hello!")))
        val observer = mockk<StreamObserver<GetResponse>>(relaxed = true)
        val sent = slot<GetResponse>()

        service.getMessage(Empty.getDefaultInstance(), observer)

        verify { observer.onNext(capture(sent)) }
        assertThat(sent.captured.text).isEqualTo("Hello!")
        assertThat(sent.captured.id).isEqualTo(id.toString())
        verify(exactly = 1) { observer.onCompleted() }
    }

    @Test
    fun `answers a save with the assigned id`() {
        val id = UUID.randomUUID()
        every { messages.save(any()) } returns Message(MessageId(id), MessageText("Bonjour!"))
        val observer = mockk<StreamObserver<SaveResponse>>(relaxed = true)
        val sent = slot<SaveResponse>()

        service.saveMessage(SaveRequest.newBuilder().setText("Bonjour!").build(), observer)

        verify { observer.onNext(capture(sent)) }
        assertThat(sent.captured.id).isEqualTo(id.toString())
        verify(exactly = 1) { observer.onCompleted() }
    }

    @Test
    fun `rejects a blank message as INVALID_ARGUMENT rather than letting it become UNKNOWN`() {
        val observer = mockk<StreamObserver<SaveResponse>>(relaxed = true)
        val error = slot<Throwable>()

        service.saveMessage(SaveRequest.newBuilder().setText("  ").build(), observer)

        verify { observer.onError(capture(error)) }
        assertThat(Status.fromThrowable(error.captured).code).isEqualTo(Status.Code.INVALID_ARGUMENT)
        verify(exactly = 0) { observer.onCompleted() }
    }

    @Test
    fun `a database failure becomes INTERNAL, not a leaked exception`() {
        every { messages.findAll() } throws IllegalStateException("connection reset")
        val observer = mockk<StreamObserver<GetResponse>>(relaxed = true)
        val error = slot<Throwable>()

        service.getMessage(Empty.getDefaultInstance(), observer)

        verify { observer.onError(capture(error)) }
        val status = (error.captured as StatusRuntimeException).status
        assertThat(status.code).isEqualTo(Status.Code.INTERNAL)
        assertThat(status.description).isEqualTo("connection reset")
    }
}
