package tern.translate

import io.netty.channel.ChannelOption
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * Runs against a real HTTP server returning canned responses, so what is verified is the
 * behaviour that matters for a third party: that nothing here ever throws, and that a timeout,
 * a 5xx or a nonsense body all come back as "language unknown".
 */
class LanguageDetectorTest {

    private lateinit var server: MockWebServer
    private lateinit var detector: LanguageDetector

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
            .responseTimeout(Duration.ofMillis(500))
        detector = LanguageDetector(
            WebClient.builder()
                .baseUrl(server.url("/").toString().trimEnd('/'))
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .build()
        )
    }

    @AfterEach
    fun stopServer() = server.shutdown()

    @Test
    fun `returns the detected language`() = runTest {
        server.enqueue(json("""[{"confidence": 92.0, "language": "fr"}]"""))

        assertThat(detector.detect("Bonjour!")).isEqualTo("fr")
    }

    @Test
    fun `picks the most confident candidate when the detector is unsure`() = runTest {
        server.enqueue(json("""[{"confidence": 12.5, "language": "es"}, {"confidence": 74.0, "language": "ru"}]"""))

        assertThat(detector.detect("Privet!")).isEqualTo("ru")
    }

    @Test
    fun `sends the text the way LibreTranslate expects it`() = runTest {
        server.enqueue(json("""[{"confidence": 99.0, "language": "en"}]"""))

        detector.detect("Hello!")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/detect")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"q":"Hello!"}""")
    }

    @Test
    fun `a timeout leaves the language unknown instead of throwing`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThat(detector.detect("Hello!")).isEmpty()
    }

    @Test
    fun `a server error leaves the language unknown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        assertThat(detector.detect("Hello!")).isEmpty()
    }

    @Test
    fun `an unparseable body leaves the language unknown`() = runTest {
        server.enqueue(json("not json at all"))

        assertThat(detector.detect("Hello!")).isEmpty()
    }

    @Test
    fun `an empty result set leaves the language unknown`() = runTest {
        server.enqueue(json("[]"))

        assertThat(detector.detect("Hello!")).isEmpty()
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
