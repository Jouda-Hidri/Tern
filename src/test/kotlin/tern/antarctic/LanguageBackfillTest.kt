package tern.antarctic

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tern.translate.LanguageDetector
import java.util.concurrent.atomic.AtomicInteger

class LanguageBackfillTest {

    private val db = mockk<MessageRepository>()
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val languageDetector = mockk<LanguageDetector>()

    private fun backfill(batchSize: Int = 100, parallelism: Int = 8) =
        LanguageBackfill(db, jdbc, languageDetector, batchSize, parallelism)

    @Test
    fun `names every message the detector can identify, in one batch`() = runTest {
        every { db.findWithoutLanguage(any()) } returns listOf(
            Message("id-1", "Bonjour!", ""),
            Message("id-2", "Hello!", ""),
        )
        coEvery { languageDetector.detect("Bonjour!") } returns "fr"
        coEvery { languageDetector.detect("Hello!") } returns "en"
        val batch = slot<List<Array<Any>>>()
        every { jdbc.batchUpdate(any<String>(), capture(batch)) } returns intArrayOf(1, 1)

        val named = backfill().backfill()

        assertThat(named).isEqualTo(2)
        assertThat(batch.captured.map { it.toList() }).containsExactlyInAnyOrder(
            listOf("fr", "id-1"),
            listOf("en", "id-2"),
        )
    }

    @Test
    fun `writes nothing when there is nothing to name`() = runTest {
        every { db.findWithoutLanguage(any()) } returns emptyList()

        assertThat(backfill().backfill()).isEqualTo(0)
        verify(exactly = 0) { jdbc.batchUpdate(any<String>(), any<List<Array<Any>>>()) }
    }

    @Test
    fun `leaves a message alone when the detector still cannot name it`() = runTest {
        every { db.findWithoutLanguage(any()) } returns listOf(Message("id-1", "???", ""))
        coEvery { languageDetector.detect(any()) } returns ""

        assertThat(backfill().backfill()).isEqualTo(0)
        verify(exactly = 0) { jdbc.batchUpdate(any<String>(), any<List<Array<Any>>>()) }
    }

    @Test
    fun `detects concurrently, but no more at once than the limit allows`() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        every { db.findWithoutLanguage(any()) } returns (1..12).map { Message("id-$it", "text $it", "") }
        coEvery { languageDetector.detect(any()) } coAnswers {
            peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                delay(30)
                "fr"
            } finally {
                inFlight.decrementAndGet()
            }
        }
        every { jdbc.batchUpdate(any<String>(), any<List<Array<Any>>>()) } returns IntArray(12) { 1 }

        val named = backfill(parallelism = 3).backfill()

        assertThat(named).isEqualTo(12)
        assertThat(peak.get()).isLessThanOrEqualTo(3)
        assertThat(peak.get()).isGreaterThan(1)
    }
}
