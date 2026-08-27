package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.mockk.coEvery
import io.mockk.mockk
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
import tern.grpc.TernServiceOuterClass.UpdateLanguageRequest
import org.awaitility.Awaitility.await
import tern.translate.LanguageDetector
import java.time.Duration
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
    private val languageDetector = mockk<LanguageDetector>()

    @BeforeEach
    fun startServer() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(behaviour).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        coEvery { languageDetector.detect(any()) } returns ""
        service = ArticService(channel, languageDetector, Duration.ofSeconds(5))
    }

    @AfterEach
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `maps every message antarctic returns`() = runTest {
        behaviour.messages = listOf(
            wire("one", "Hello!", "en"),
            wire("two", "Bonjour!", ""),
        )

        val found = service.find()

        assertThat(found).containsExactly(
            Message("one", "Hello!", "en"),
            Message("two", "Bonjour!", ""),
        )
    }

    @Test
    fun `the save does not wait for language detection`() = runTest {
        coEvery { languageDetector.detect("Bonjour!") } returns "fr"

        val saved = service.save(Message(null, "Bonjour!"))

        assertThat(saved.id).isEqualTo("saved-id")
        // The write carries no language; it is filled in afterwards by UpdateLanguage.
        assertThat(behaviour.receivedLanguages).containsExactly("")
    }

    @Test
    fun `the detected language is stored by a later update`() = runTest {
        coEvery { languageDetector.detect("Bonjour!") } returns "fr"

        service.save(Message(null, "Bonjour!"))

        await().atMost(Duration.ofSeconds(5)).until { behaviour.updates.isNotEmpty() }
        assertThat(behaviour.updates).containsExactly("saved-id" to "fr")
    }

    @Test
    fun `a detector that is down does not stop the message being saved, and skips the update`() = runTest {
        coEvery { languageDetector.detect(any()) } returns ""

        val saved = service.save(Message(null, "Hello!"))

        assertThat(saved.id).isNotNull()
        Thread.sleep(500)
        assertThat(behaviour.updates).isEmpty()
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
        val service = ArticService(dead, languageDetector, Duration.ofMillis(500))
        try {
            val elapsed = measureTimeMillis {
                assertFailsWith<StatusException> { service.save(Message(null, "Hello!")) }
            }
            assertThat(elapsed).isLessThan(5_000)
        } finally {
            dead.shutdownNow()
        }
    }

    private fun wire(id: String, text: String, language: String) =
        tern.grpc.TernServiceOuterClass.Message.newBuilder()
            .setId(id).setText(text).setLanguage(language).build()

    private class FakeAntarctic : TernServiceGrpcKt.TernServiceCoroutineImplBase() {
        var messages: List<tern.grpc.TernServiceOuterClass.Message> = emptyList()
        var failWith: Status? = null
        val receivedLanguages = mutableListOf<String>()
        val updates = mutableListOf<Pair<String, String>>()

        override suspend fun getMessage(request: Empty): GetResponse {
            failWith?.let { throw it.asRuntimeException() }
            return GetResponse.newBuilder().addAllMessages(messages).build()
        }

        override suspend fun saveMessage(request: SaveRequest): SaveResponse {
            failWith?.let { throw it.asRuntimeException() }
            receivedLanguages += request.language
            return SaveResponse.newBuilder().setId("saved-id").build()
        }

        override suspend fun updateLanguage(request: UpdateLanguageRequest): Empty {
            updates += request.id to request.language
            return Empty.getDefaultInstance()
        }
    }
}
