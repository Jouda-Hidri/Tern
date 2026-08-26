package tern.artic

import tern.domain.Message
import tern.domain.MessageText

/**
 * REST payloads. Separate from the domain so the published API is an explicit choice rather
 * than whatever the domain happens to look like today.
 */
data class MessageRequest(val text: String) {
    fun toDomain(): Message = Message(MessageText(text))
}

data class MessageResponse(val id: String?, val text: String) {
    companion object {
        fun fromDomain(message: Message): MessageResponse =
            MessageResponse(id = message.id?.toString(), text = message.text.value)
    }
}
