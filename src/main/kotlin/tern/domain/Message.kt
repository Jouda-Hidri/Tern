package tern.domain

import java.util.UUID

/**
 * The domain model. Deliberately free of persistence, transport and framework concerns:
 * the entity ([tern.antarctic.MessageEntity]), the REST payloads ([tern.artic.MessageRequest],
 * [tern.artic.MessageResponse]) and the generated protobuf types each map to and from this.
 */
data class Message(val id: MessageId?, val text: MessageText, val language: LanguageCode? = null) {

    /** A message that has not been persisted yet, and so has no id. */
    constructor(text: MessageText) : this(null, text)
}

/**
 * An ISO 639 code as reported by the detector. Nullable everywhere it appears: detection is
 * done by a third party that is allowed to be unavailable, and a message without a known
 * language is still a perfectly good message.
 */
@JvmInline
value class LanguageCode(val value: String) {
    init {
        requireValid(PATTERN.matches(value)) { "Not a language code: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")

        /** Lenient parse for values arriving from outside; unusable input becomes null. */
        fun parseOrNull(value: String?): LanguageCode? =
            value?.takeIf { PATTERN.matches(it) }?.let { LanguageCode(it) }
    }
}

@JvmInline
value class MessageId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun of(value: String): MessageId = runCatching { MessageId(UUID.fromString(value)) }
            .getOrElse { throw InvalidMessageException("Message id must be a UUID, was '$value'") }
    }
}

@JvmInline
value class MessageText(val value: String) {
    init {
        requireValid(value.isNotBlank()) { "Message text must not be blank" }
        requireValid(value.length <= MAX_LENGTH) { "Message text must be at most $MAX_LENGTH characters" }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 1_000
    }
}

/** Raised when input cannot produce a valid domain object; surfaces as a 400. */
class InvalidMessageException(message: String) : IllegalArgumentException(message)

/**
 * `require` in the shape the domain needs. The stdlib version throws a plain
 * [IllegalArgumentException], which the API layer would have to catch wholesale - and then a
 * genuine internal bug would be reported to the caller as a 400. This throws the domain's own
 * type instead, so only real validation failures are treated as the caller's fault.
 *
 * Inline, so the message lambda costs nothing on the overwhelmingly common valid path.
 */
private inline fun requireValid(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidMessageException(message())
}
