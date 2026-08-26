package tern.domain

import java.util.UUID

/**
 * The domain model. Deliberately free of persistence, transport and framework concerns:
 * the entity ([tern.antarctic.MessageEntity]), the REST payloads ([tern.artic.MessageRequest],
 * [tern.artic.MessageResponse]) and the generated protobuf types each map to and from this.
 */
data class Message(val id: MessageId?, val text: MessageText) {

    /** A message that has not been persisted yet, and so has no id. */
    constructor(text: MessageText) : this(null, text)
}

@JvmInline
value class MessageId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun of(value: String): MessageId =
            try {
                MessageId(UUID.fromString(value))
            } catch (e: IllegalArgumentException) {
                throw InvalidMessageException("Message id must be a UUID, was '$value'")
            }
    }
}

@JvmInline
value class MessageText(val value: String) {
    init {
        if (value.isBlank()) throw InvalidMessageException("Message text must not be blank")
        if (value.length > MAX_LENGTH) {
            throw InvalidMessageException("Message text must be at most $MAX_LENGTH characters")
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 1_000
    }
}

/** Raised when input cannot produce a valid domain object; surfaces as a 400. */
class InvalidMessageException(message: String) : IllegalArgumentException(message)
