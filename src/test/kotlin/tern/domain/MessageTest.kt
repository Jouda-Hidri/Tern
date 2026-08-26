package tern.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class MessageTest {

    @Test
    fun `a message built from text alone has no id yet`() {
        val message = Message(MessageText("Hello!"))

        assertThat(message.id).isNull()
        assertThat(message.text.value).isEqualTo("Hello!")
    }

    @Test
    fun `blank text is not a message`() {
        assertThatThrownBy { MessageText("   ") }
            .isInstanceOf(InvalidMessageException::class.java)
            .hasMessageContaining("must not be blank")
    }

    @Test
    fun `text longer than the limit is rejected`() {
        assertThatThrownBy { MessageText("a".repeat(MessageText.MAX_LENGTH + 1)) }
            .isInstanceOf(InvalidMessageException::class.java)
            .hasMessageContaining("at most ${MessageText.MAX_LENGTH}")
    }

    @Test
    fun `text exactly at the limit is accepted`() {
        val text = MessageText("a".repeat(MessageText.MAX_LENGTH))

        assertThat(text.value).hasSize(MessageText.MAX_LENGTH)
    }

    @Test
    fun `an id has to be a uuid`() {
        assertThatThrownBy { MessageId.of("not-a-uuid") }
            .isInstanceOf(InvalidMessageException::class.java)
            .hasMessageContaining("must be a UUID")
    }

    @Test
    fun `a uuid round trips through its string form`() {
        val uuid = UUID.randomUUID()

        assertThat(MessageId.of(uuid.toString())).isEqualTo(MessageId(uuid))
        assertThat(MessageId(uuid).toString()).isEqualTo(uuid.toString())
    }
}
