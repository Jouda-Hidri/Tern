package tern.artic

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.mockk.coEvery
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import tern.domain.LanguageCode
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import tern.grpc.TernServiceGrpcKt
import tern.grpc.TernServiceOuterClass.GetResponse
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.grpc.TernServiceOuterClass.SaveResponse
import tern.tapi.TapiDownloader
import tern.translate.Detection
import tern.translate.LanguageDetector
import java.util.UUID

/**
 * Runs against a real gRPC server over the in-process transport, so the stub, the status codes
 * and the mapping are all genuinely exercised - only the remote implementation is faked.
 *
 * The tapi download is stubbed out: it is a fire-and-forget side effect with its own tests in
 * TapiDownloaderTest, and must never affect the outcome of a save.
 */
class ArticServiceTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var service: ArticService

    private var behaviour: FakeAntarctic = FakeAntarctic()
    private val languageDetector = mockk<LanguageDetector>()
    private val tapiDownloader = mockk<TapiDownloader>()

    @BeforeEach
    fun startServer() {
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(behaviour)
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        coEvery { languageDetector.detect(any()) } returns Detection.Unavailable
        justRun { tapiDownloader.download() }
        service = ArticService(channel, languageDetector, tapiDownloader)
    }

    @AfterEach
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `maps every message antarctic streams back into the domain`() = runTest {
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
    fun `returns the saved message carrying the id antarctic assigned`() = runTest {
        val id = UUID.randomUUID()
        behaviour.assignedId = id

        val saved = service.save(Message(MessageText("Privet!")))

        assertThat(saved.id).isEqualTo(MessageId(id))
        assertThat(saved.text).isEqualTo(MessageText("Privet!"))
        assertThat(behaviour.received).containsExactly("Privet!")
    }

    @Test
    fun `the detected language travels with the message and comes back on it`() = runTest {
        coEvery { languageDetector.detect(MessageText("Bonjour!")) } returns Detection.Detected(LanguageCode("fr"))

        val saved = service.save(Message(MessageText("Bonjour!")))

        assertThat(saved.language).isEqualTo(LanguageCode("fr"))
        assertThat(behaviour.receivedLanguages).containsExactly("fr")
    }

    @Test
    fun `a detector that is down does not stop the message being saved`() = runTest {
        coEvery { languageDetector.detect(any()) } returns Detection.Unavailable

        val saved = service.save(Message(MessageText("Hello!")))

        assertThat(saved.id).isNotNull()
        assertThat(saved.language).isNull()
        assertThat(behaviour.receivedLanguages).containsExactly("")
    }

    @Test
    fun `a failing save is surfaced to the caller instead of being reported as success`() = runTest {
        behaviour.failWith = Status.UNAVAILABLE

        val thrown = assertFailsWith<StatusException> { service.save(Message(MessageText("Privet!"))) }

        assertThat(thrown.status.code).isEqualTo(Status.Code.UNAVAILABLE)
    }

    /** The fake speaks coroutines too, so the whole test is Flow and suspend end to end. */
    private class FakeAntarctic : TernServiceGrpcKt.TernServiceCoroutineImplBase() {
        var messages: List<GetResponse> = emptyList()
        var assignedId: UUID = UUID.randomUUID()
        var failWith: Status? = null
        val received = mutableListOf<String>()
        val receivedLanguages = mutableListOf<String>()

        override fun getMessage(request: Empty): Flow<GetResponse> = flow {
            failWith?.let { throw it.asRuntimeException() }
            messages.forEach { emit(it) }
        }

        override suspend fun saveMessage(request: SaveRequest): SaveResponse {
            failWith?.let { throw it.asRuntimeException() }
            received += request.text
            receivedLanguages += request.language
            return SaveResponse.newBuilder().setId(assignedId.toString()).build()
        }
    }
}
