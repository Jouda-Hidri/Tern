package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tern.antarctic.Message
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis
import kotlin.test.assertFailsWith

/**
 * Runs against a real gRPC server over the in-process transport, so the stub and the status
 * codes are genuinely exercised - only antarctic's implementation is faked.
 */
class ArticServiceTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var service: ArticService

    private val behaviour = FakeAntarctic()

    @BeforeEach
    fun startServer() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(behaviour).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        service = ArticService(channel, Duration.ofSeconds(5))
    }

    @AfterEach
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `maps every message antarctic returns`() = runTest {
        behaviour.messages = listOf(
            wire("Hello!", "en"),
            wire("Bonjour!", ""),
        )

        val found = service.find()

        assertThat(found).containsExactly(
            Message(null, "Hello!", "en"),
            Message(null, "Bonjour!", ""),
        )
    }

    @Test
    fun `the save sends the text and nothing else`() = runTest {
        service.save(Message(null, "Bonjour!"))

        assertThat(behaviour.saved).containsExactly("Bonjour!")
    }

    @Test
    fun `the language antarctic detected comes back on the saved message`() = runTest {
        behaviour.detected = "fr"

        val saved = service.save(Message(null, "Bonjour!"))

        assertThat(saved.language).isEqualTo("fr")
    }

    @Test
    fun `a message the detector could not name comes back without a language`() = runTest {
        behaviour.detected = ""

        val saved = service.save(Message(null, "Hello!"))

        assertThat(saved.language).isEmpty()
    }

    @Test
    fun `a failing save is surfaced to the caller instead of being reported as success`() = runTest {
        behaviour.failWith = Status.UNAVAILABLE

        val thrown = assertFailsWith<StatusException> { service.save(Message(null, "Hello!")) }

        assertThat(thrown.status.code).isEqualTo(Status.Code.UNAVAILABLE)
    }

    /** Without the deadline this waits for the channel instead of failing, and hangs. */
    @Test
    @Timeout(15)
    fun `an unreachable antarctic fails inside the deadline rather than waiting for the channel`() = runTest {
        val dead = ManagedChannelBuilder.forTarget("localhost:1").usePlaintext().build()
        val service = ArticService(dead, Duration.ofMillis(500))
        try {
            val elapsed = measureTimeMillis {
                assertFailsWith<StatusException> { service.save(Message(null, "Hello!")) }
            }
            assertThat(elapsed).isLessThan(5_000)
        } finally {
            dead.shutdownNow()
        }
    }

    private fun wire(text: String, language: String) =
        tern.grpc.TernServiceOuterClass.Message.newBuilder()
            .setText(text).setLanguage(language).build()

    private class FakeAntarctic : TernServiceGrpcKt.TernServiceCoroutineImplBase() {
        var messages: List<tern.grpc.TernServiceOuterClass.Message> = emptyList()
        var failWith: Status? = null
        var detected: String = ""
        val saved: MutableList<String> = CopyOnWriteArrayList()

        override suspend fun getMessage(request: Empty): GetResponse {
            failWith?.let { throw it.asRuntimeException() }
            return GetResponse.newBuilder().addAllMessages(messages).build()
        }

        override suspend fun saveMessage(request: SaveRequest): SaveResponse {
            failWith?.let { throw it.asRuntimeException() }
            saved += request.text
            return SaveResponse.newBuilder().setLanguage(detected).build()
        }
    }
}
