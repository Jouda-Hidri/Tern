package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import tern.domain.LanguageCode
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import tern.grpc.TernServiceOuterClass.SaveRequest
import java.util.UUID

/**
 * On the coroutine base class there is no observer to stub: the streaming call is a Flow to
 * collect and the unary one a suspend function to await, so the assertions are on values.
 */
class AntarcticServiceTest {

    private val messages = mockk<MessageService>()
    private val service = AntarcticService(messages)

    @Test
    fun `streams every stored message back, id and language included`() = runTest {
        val id = UUID.randomUUID()
        every { messages.findAll() } returns
            flow { emit(Message(MessageId(id), MessageText("Hello!"), LanguageCode("en"))) }

        val sent = service.getMessage(Empty.getDefaultInstance()).toList()

        assertThat(sent).hasSize(1)
        assertThat(sent.first().text).isEqualTo("Hello!")
        assertThat(sent.first().id).isEqualTo(id.toString())
        assertThat(sent.first().language).isEqualTo("en")
    }

    @Test
    fun `answers a save with the assigned id`() = runTest {
        val id = UUID.randomUUID()
        coEvery { messages.save(any()) } returns Message(MessageId(id), MessageText("Bonjour!"))

        val response = service.saveMessage(SaveRequest.newBuilder().setText("Bonjour!").build())

        assertThat(response.id).isEqualTo(id.toString())
    }

    @Test
    fun `rejects a blank message as INVALID_ARGUMENT rather than letting it become UNKNOWN`() = runTest {
        val thrown = assertFailsWith<StatusException> {
            service.saveMessage(SaveRequest.newBuilder().setText("  ").build())
        }

        assertThat(thrown.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `a database failure on save becomes INTERNAL, not a leaked exception`() = runTest {
        coEvery { messages.save(any()) } throws IllegalStateException("connection reset")

        val thrown = assertFailsWith<StatusException> {
            service.saveMessage(SaveRequest.newBuilder().setText("Hello!").build())
        }

        assertThat(thrown.status.code).isEqualTo(Status.Code.INTERNAL)
        assertThat(thrown.status.description).isEqualTo("connection reset")
    }

    @Test
    fun `a failure part way through the stream becomes INTERNAL too`() = runTest {
        every { messages.findAll() } returns flow { throw IllegalStateException("connection reset") }

        val thrown = assertFailsWith<StatusException> {
            service.getMessage(Empty.getDefaultInstance()).toList()
        }

        assertThat(thrown.status.code).isEqualTo(Status.Code.INTERNAL)
    }
}
