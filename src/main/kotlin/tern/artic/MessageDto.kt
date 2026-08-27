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

data class MessageResponse(val id: String?, val text: String, val language: String?) {
    companion object {
        fun fromDomain(message: Message): MessageResponse = MessageResponse(
            id = message.id?.toString(),
            text = message.text.value,
            language = message.language?.value,
        )
    }
}

/**
 * A breakdown of what has been stored, by detected language. `unknown` counts the messages
 * saved while the detector was unavailable.
 */
data class MessageStats(val total: Int, val byLanguage: Map<String, Int>) {
    companion object {
        private const val UNKNOWN = "unknown"

        fun of(messages: List<Message>): MessageStats = MessageStats(
            total = messages.size,
            byLanguage = messages
                .groupingBy { it.language?.value ?: UNKNOWN }
                .eachCount()
                .toSortedMap(compareBy({ it == UNKNOWN }, { it })),  // unknown last, rest A-Z
        )
    }
}
