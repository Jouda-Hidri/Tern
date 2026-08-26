package tern.antarctic

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import tern.domain.Message
import tern.domain.MessageId
import tern.domain.MessageText

/**
 * Persistence representation of a message. Kept separate from [Message] so the table layout
 * can change without the domain following it around.
 */
@Table("messages")
data class MessageEntity(@Id val id: String?, val text: String) {

    fun toDomain(): Message = Message(id?.let { MessageId.of(it) }, MessageText(text))

    companion object {
        fun fromDomain(message: Message): MessageEntity =
            MessageEntity(id = message.id?.toString(), text = message.text.value)
    }
}
