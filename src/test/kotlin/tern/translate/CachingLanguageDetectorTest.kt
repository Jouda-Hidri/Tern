package tern.translate

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tern.domain.LanguageCode
import tern.domain.MessageText
import java.util.concurrent.atomic.AtomicInteger

/**
 * The delegate is a `fun interface`, so a lambda is a complete stand-in for it - no mock needed.
 */
class CachingLanguageDetectorTest {

    private val calls = AtomicInteger()

    private fun detectorReturning(vararg answers: Detection): LanguageDetector {
        val queue = answers.toMutableList()
        return LanguageDetector { queue.removeAt(0).also { calls.incrementAndGet() } }
    }

    @Test
    fun `the same text is only sent to the third party once`() = runTest {
        val fr = Detection.Detected(LanguageCode("fr"))
        val detector = CachingLanguageDetector(detectorReturning(fr, fr))

        assertThat(detector.detect(MessageText("Bonjour!"))).isEqualTo(fr)
        assertThat(detector.detect(MessageText("Bonjour!"))).isEqualTo(fr)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `different texts are each asked about`() = runTest {
        val detector = CachingLanguageDetector(
            detectorReturning(Detection.Detected(LanguageCode("fr")), Detection.Detected(LanguageCode("en")))
        )

        detector.detect(MessageText("Bonjour!"))
        detector.detect(MessageText("Hello!"))

        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `an outage is not cached, so recovery is picked up on the next call`() = runTest {
        val en = Detection.Detected(LanguageCode("en"))
        val detector = CachingLanguageDetector(detectorReturning(Detection.Unavailable, en))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unavailable)
        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(en)
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `an unrecognised answer is cached - asking again would give the same reply`() = runTest {
        val detector = CachingLanguageDetector(detectorReturning(Detection.Unrecognised))

        assertThat(detector.detect(MessageText("?!"))).isEqualTo(Detection.Unrecognised)
        assertThat(detector.detect(MessageText("?!"))).isEqualTo(Detection.Unrecognised)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `the least recently used entry is evicted once the cache is full`() = runTest {
        val en = Detection.Detected(LanguageCode("en"))
        val detector = CachingLanguageDetector(detectorReturning(en, en, en, en), maxEntries = 2)

        detector.detect(MessageText("one"))
        detector.detect(MessageText("two"))
        detector.detect(MessageText("three"))   // evicts "one"
        detector.detect(MessageText("one"))     // so this is a miss again

        assertThat(calls.get()).isEqualTo(4)
    }
}
