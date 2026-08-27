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

// Neither the database id nor the detected language belongs here: one is a persistence detail,
// the other is derived metadata reported by /stats.
data class MessageResponse(val text: String) {
    companion object {
        fun from(message: Message): MessageResponse = MessageResponse(text = message.text)
    }
}
