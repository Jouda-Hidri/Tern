package tern.antarctic

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText
import java.util.UUID

class MessageServiceTest {

    private val repository = mockk<MessageRepository>()
    private val service = MessageService(repository)

    @Test
    fun `reads entities from the repository and hands back domain messages`() {
        val id = UUID.randomUUID()
        every { repository.findMessages() } returns listOf(MessageEntity(id.toString(), "Hello!"))

        val found = service.findAll()

        assertThat(found).containsExactly(Message(MessageId(id), MessageText("Hello!")))
    }

    @Test
    fun `saves a new message without an id and returns the id the database assigned`() {
        val assigned = UUID.randomUUID()
        val persisted = slot<MessageEntity>()
        every { repository.save(capture(persisted)) } answers {
            MessageEntity(assigned.toString(), persisted.captured.text)
        }

        val saved = service.save(Message(MessageText("Bonjour!")))

        assertThat(persisted.captured.id).isNull()
        assertThat(persisted.captured.text).isEqualTo("Bonjour!")
        assertThat(saved.id).isEqualTo(MessageId(assigned))
        assertThat(saved.text).isEqualTo(MessageText("Bonjour!"))
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `an empty database is not an error`() {
        every { repository.findMessages() } returns emptyList()

        assertThat(service.findAll()).isEmpty()
    }
}
