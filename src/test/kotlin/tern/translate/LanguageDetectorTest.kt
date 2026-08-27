package tern.translate

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tern.domain.LanguageCode
import tern.domain.MessageText
import java.time.Duration

/**
 * Runs against a real HTTP server returning canned responses, so what is actually verified is
 * the behaviour that matters for a third party: that nothing here ever throws, and that a
 * timeout, a 5xx or a nonsense body all degrade to "language unknown".
 */
class LanguageDetectorTest {

    private lateinit var server: MockWebServer
    private lateinit var detector: LibreTranslateDetector

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        detector = detectorFor(apiKey = "")
    }

    @AfterEach
    fun stopServer() = server.shutdown()

    private fun detectorFor(apiKey: String): LibreTranslateDetector {
        val properties = TranslateProperties(
            url = server.url("/").toString().trimEnd('/'),
            apiKey = apiKey,
            timeout = Duration.ofMillis(500),
            connectTimeout = Duration.ofMillis(500),
        )
        return LibreTranslateDetector(TranslateConfiguration().translateClient(properties), properties)
    }

    @Test
    fun `returns the detected language`() = runTest {
        server.enqueue(json("""[{"confidence": 92.0, "language": "fr"}]"""))

        assertThat(detector.detect(MessageText("Bonjour!"))).isEqualTo(Detection.Detected(LanguageCode("fr")))
    }

    @Test
    fun `picks the most confident candidate when the detector is unsure`() = runTest {
        server.enqueue(json("""[{"confidence": 12.5, "language": "es"}, {"confidence": 74.0, "language": "ru"}]"""))

        assertThat(detector.detect(MessageText("Privet!"))).isEqualTo(Detection.Detected(LanguageCode("ru")))
    }

    @Test
    fun `sends the text and the api key the way LibreTranslate expects them`() = runTest {
        detector = detectorFor(apiKey = "s3cret")
        server.enqueue(json("""[{"confidence": 99.0, "language": "en"}]"""))

        detector.detect(MessageText("Hello!"))

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/detect")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"q":"Hello!","api_key":"s3cret"}""")
    }

    @Test
    fun `a timeout leaves the language unknown instead of throwing`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unavailable)
    }

    @Test
    fun `a server error is retried once, then gives up quietly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unavailable)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a server error that clears on the retry still yields a language`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json("""[{"confidence": 92.0, "language": "fr"}]"""))

        assertThat(detector.detect(MessageText("Bonjour!"))).isEqualTo(Detection.Detected(LanguageCode("fr")))
    }

    @Test
    fun `a client error is not retried - it would fail identically`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an unparseable body leaves the language unknown`() = runTest {
        server.enqueue(json("not json at all"))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unavailable)
    }

    @Test
    fun `a code the domain does not accept is unrecognised, not an outage`() = runTest {
        server.enqueue(json("""[{"confidence": 92.0, "language": "not-a-language-code"}]"""))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unrecognised)
    }

    @Test
    fun `an empty result set is unrecognised, not an outage`() = runTest {
        server.enqueue(json("[]"))

        assertThat(detector.detect(MessageText("Hello!"))).isEqualTo(Detection.Unrecognised)
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
