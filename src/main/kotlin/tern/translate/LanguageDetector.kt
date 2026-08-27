package tern.translate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import tern.domain.LanguageCode
import tern.domain.MessageText
import tern.tracing.RequestId
import java.time.Duration

/**
 * Asks LibreTranslate which language a message is written in.
 *
 * Interface plus implementation so decorators can be layered on by delegation - see
 * [CachingLanguageDetector].
 */
fun interface LanguageDetector {
    suspend fun detect(text: MessageText): Detection
}

/**
 * The contract here is that it never throws: the detector is a third party we do not control,
 * and a message must be storable whether or not it is up. Everything that can go wrong -
 * timeout, 5xx, an unparseable body, an unrecognised code - comes back as a [Detection].
 */
class LibreTranslateDetector(
    private val translateClient: WebClient,
    private val properties: TranslateProperties,
) : LanguageDetector {
    private val logger = LoggerFactory.getLogger(LibreTranslateDetector::class.java)

    override suspend fun detect(text: MessageText): Detection {
        // Reactor hands its callbacks to event-loop threads that carry no MDC, so the id has to
        // travel by hand for these lines to stay part of the request's trace.
        val requestId = RequestId.current()

        return runCatching {
            translateClient.post()
                .uri("/detect")
                .bodyValue(DetectRequest(q = text.value, apiKey = properties.apiKey))
                .retrieve()
                .bodyToMono(Array<Candidate>::class.java)
                // One retry, and only for the transient cases. Detection is a read, so
                // repeating it is safe; a 4xx is our fault and will fail again identically.
                .retryWhen(
                    Retry.backoff(1, Duration.ofMillis(200))
                        .filter { it !is WebClientResponseException || it.statusCode.is5xxServerError }
                )
                .awaitSingleOrNull()
        }.fold(
            onSuccess = { candidates -> (candidates ?: emptyArray()).toDetection() },
            onFailure = { e ->
                RequestId.withRequestId(requestId) {
                    logger.warn("Translate - detection failed at ${properties.url}: ${e.message}")
                }
                Detection.Unavailable
            },
        )
    }

    private fun Array<Candidate>.toDetection(): Detection =
        maxByOrNull { it.confidence }
            ?.let { LanguageCode.parseOrNull(it.language) }
            ?.let(Detection::Detected)
            ?: Detection.Unrecognised
}

/**
 * Kotlin's class delegation doing the tedious part: everything not overridden is forwarded to
 * [delegate] with no boilerplate, so this file only contains what caching actually adds. The
 * same texts recur constantly under load, and each miss is a network round trip.
 */
class CachingLanguageDetector(
    private val delegate: LanguageDetector,
    private val maxEntries: Int = 500,
) : LanguageDetector by delegate {

    private val cache = object : LinkedHashMap<MessageText, Detection>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<MessageText, Detection>) = size > maxEntries
    }

    override suspend fun detect(text: MessageText): Detection {
        synchronized(cache) { cache[text] }?.let { return it }

        // Only successful answers are cached: an outage must not be remembered as a result.
        return delegate.detect(text).also { detection ->
            if (detection !is Detection.Unavailable) synchronized(cache) { cache[text] = detection }
        }
    }
}

private data class DetectRequest(val q: String, @get:JsonProperty("api_key") val apiKey: String)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class Candidate(val language: String = "", val confidence: Double = 0.0)
