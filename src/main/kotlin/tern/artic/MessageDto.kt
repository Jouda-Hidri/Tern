package tern.artic

import tern.antarctic.Message
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

data class MessageRequest(
    @field:NotBlank(message = "text must not be blank")
    @field:Size(max = 1000, message = "text must be at most 1000 characters")
    val text: String,
) {
    fun toMessage(): Message = Message(id = null, text = text)
}

const val UNKNOWN_LANGUAGE = "unknown"

// The database id does not belong here: it is a persistence detail. The language does - it is
// the point of storing the message, and it is known by the time the response is written.
data class MessageResponse(val text: String, val language: String) {
    companion object {
        fun from(message: Message): MessageResponse =
            MessageResponse(text = message.text, language = message.language.ifEmpty { UNKNOWN_LANGUAGE })
    }
}
