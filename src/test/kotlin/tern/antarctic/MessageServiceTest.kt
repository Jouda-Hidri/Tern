package tern.antarctic

import io.mockk.every
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tern.domain.LanguageCode
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import java.util.UUID

class MessageServiceTest {

    private val repository = mockk<MessageRepository>()
    private val service = MessageService(repository)

    @Test
    fun `reads entities from the repository and hands back domain messages`() = runTest {
        val id = UUID.randomUUID()
        every { repository.findMessages() } returns listOf(MessageEntity(id.toString(), "Hello!", "en"))

        val found = service.findAll().toList()

        assertThat(found).containsExactly(Message(MessageId(id), MessageText("Hello!"), LanguageCode("en")))
    }

    @Test
    fun `saves a new message without an id and returns the id the database assigned`() = runTest {
        val assigned = UUID.randomUUID()
        val persisted = slot<MessageEntity>()
        every { repository.save(capture(persisted)) } answers {
            MessageEntity(assigned.toString(), persisted.captured.text, persisted.captured.language)
        }

        val saved = service.save(Message(null, MessageText("Bonjour!"), LanguageCode("fr")))

        assertThat(persisted.captured.id).isNull()
        assertThat(persisted.captured.text).isEqualTo("Bonjour!")
        assertThat(persisted.captured.language).isEqualTo("fr")
        assertThat(saved.id).isEqualTo(MessageId(assigned))
        assertThat(saved.text).isEqualTo(MessageText("Bonjour!"))
        assertThat(saved.language).isEqualTo(LanguageCode("fr"))
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `an empty database is not an error`() = runTest {
        every { repository.findMessages() } returns emptyList()

        assertThat(service.findAll().toList()).isEmpty()
    }
}
