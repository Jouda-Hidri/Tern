package tern.antarctic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tern.translate.LanguageDetector

@Component
@ConditionalOnProperty("tern.backfill.enabled", havingValue = "true")
class LanguageBackfill(
    private val db: MessageRepository,
    private val jdbc: JdbcTemplate,
    private val languageDetector: LanguageDetector,
    @Value("\${tern.backfill.batch-size:100}") private val batchSize: Int = 100,
    @Value("\${tern.backfill.parallelism:8}") private val parallelism: Int = 8,
) {
    private val logger = LoggerFactory.getLogger(LanguageBackfill::class.java)

    @Scheduled(fixedDelayString = "\${tern.backfill.interval-ms:60000}")
    fun scheduled() {
        runBlocking { backfill() }
    }

    suspend fun backfill(): Int = coroutineScope {
        val stale = withContext(Dispatchers.IO) { db.findWithoutLanguage(batchSize) }
            .filter { it.id != null }
        if (stale.isEmpty()) return@coroutineScope 0

        val gate = Semaphore(parallelism)
        val detected = stale
            .map { message ->
                async { message.id!! to gate.withPermit { languageDetector.detect(message.text) } }
            }
            .awaitAll()
            .filter { it.second.isNotEmpty() }

        if (detected.isEmpty()) {
            logger.info("Backfill - ${stale.size} without a language, detector named none")
            return@coroutineScope 0
        }

        withContext(Dispatchers.IO) {
            jdbc.batchUpdate(
                "UPDATE messages SET language = ? WHERE id = ?::uuid",
                detected.map { arrayOf<Any>(it.second, it.first) },
            )
        }
        logger.info("Backfill - named ${detected.size} of ${stale.size} messages")
        detected.size
    }
}
