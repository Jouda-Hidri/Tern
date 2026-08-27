package tern.translate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class LanguageDetector(private val translateClient: WebClient) {
    private val logger = LoggerFactory.getLogger(LanguageDetector::class.java)

    // Never throws and never returns null: a message must be storable whether or not the
    // detector is up, so an unknown language is the empty string.
    suspend fun detect(text: String): String = try {
        translateClient.post()
            .uri("/detect")
            .bodyValue(mapOf("q" to text))
            .retrieve()
            .bodyToMono(Array<Candidate>::class.java)
            .awaitSingleOrNull()
            ?.maxByOrNull { it.confidence }
            ?.language
            .orEmpty()
    } catch (e: Exception) {
        logger.warn("Translate - detection failed: ${e.message}")
        ""
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Candidate(val language: String = "", val confidence: Double = 0.0)
