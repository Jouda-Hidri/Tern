package tern.antarctic

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tern.grpc.TernServiceOuterClass.SaveRequest
import tern.translate.LanguageDetector
import kotlin.test.assertFailsWith

class AntarcticServiceTest {

    private val db = mockk<MessageRepository>()
    private val languageDetector = mockk<LanguageDetector>()
    private val service = AntarcticService(db, languageDetector)

    @Test
    fun `returns every stored message, with its language`() = runTest {
        every { db.findMessages() } returns listOf(Message("an-id", "Hello!", "en"))

        val response = service.getMessage(Empty.getDefaultInstance())

        assertThat(response.messagesList).hasSize(1)
        assertThat(response.getMessages(0).text).isEqualTo("Hello!")
        assertThat(response.getMessages(0).language).isEqualTo("en")
    }

    @Test
    fun `a message with no detected language is sent as an empty string`() = runTest {
        every { db.findMessages() } returns listOf(Message("an-id", "Hello!", ""))

        assertThat(service.getMessage(Empty.getDefaultInstance()).getMessages(0).language).isEqualTo("")
    }

    @Test
    fun `an empty database is not an error`() = runTest {
        every { db.findMessages() } returns emptyList()

        assertThat(service.getMessage(Empty.getDefaultInstance()).messagesList).isEmpty()
    }

    @Test
    fun `detects the language and stores it with the message, in one write`() = runTest {
        coEvery { languageDetector.detect("Bonjour!") } returns "fr"
        val saved = slot<Message>()
        every { db.save(capture(saved)) } answers { saved.captured.copy(id = "new-id") }

        val response = service.saveMessage(SaveRequest.newBuilder().setText("Bonjour!").build())

        assertThat(saved.captured.id).isNull()
        assertThat(saved.captured.text).isEqualTo("Bonjour!")
        assertThat(saved.captured.language).isEqualTo("fr")
        assertThat(response.language).isEqualTo("fr")
    }

    @Test
    fun `a message is still stored when the detector cannot answer`() = runTest {
        coEvery { languageDetector.detect(any()) } returns ""
        val saved = slot<Message>()
        every { db.save(capture(saved)) } answers { saved.captured.copy(id = "new-id") }

        val response = service.saveMessage(SaveRequest.newBuilder().setText("Hello!").build())

        assertThat(saved.captured.text).isEqualTo("Hello!")
        assertThat(saved.captured.language).isEmpty()
        assertThat(response.language).isEmpty()
    }

    @Test
    fun `a database failure becomes INTERNAL, not a leaked exception`() = runTest {
        coEvery { languageDetector.detect(any()) } returns "en"
        every { db.save(any()) } throws IllegalStateException("connection reset")

        val thrown = assertFailsWith<StatusException> {
            service.saveMessage(SaveRequest.newBuilder().setText("Hello!").build())
        }

        assertThat(thrown.status.code).isEqualTo(Status.Code.INTERNAL)
    }
}
