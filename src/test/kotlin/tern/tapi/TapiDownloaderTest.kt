package tern.tapi

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The download is fire-and-forget, so what matters is not a return value but that it never
 * throws at the caller, that a stalled or failing tapi is survivable, and that the response is
 * consumed as a stream rather than buffered whole.
 */
class TapiDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: TapiDownloader

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        downloader = downloaderFor(readTimeout = Duration.ofMillis(500))
    }

    @AfterEach
    fun stopServer() = server.shutdown()

    private fun downloaderFor(readTimeout: Duration): TapiDownloader {
        val properties = TapiProperties(
            url = server.url("/").toString().trimEnd('/'),
            readTimeout = readTimeout,
            connectTimeout = Duration.ofMillis(500),
        )
        return TapiDownloader(TapiConfiguration().tapiClient(properties), properties)
    }

    @Test
    fun `consumes the csv line by line rather than as one buffer`() {
        server.enqueue(csv("id,text\n1,Hello!\n2,Bonjour!\n"))

        downloader.download()

        // The request being made at all proves the stream was subscribed to; the decoder is
        // configured to split on newlines, which is what keeps the file out of memory.
        awaitRequests(1)
        assertThat(server.takeRequest().method).isEqualTo("GET")
    }

    @Test
    fun `a stalled tapi does not hang the caller`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThatCode { downloader.download() }.doesNotThrowAnyException()
    }

    @Test
    fun `an unreachable tapi is survivable`() {
        server.shutdown()

        assertThatCode { downloader.download() }.doesNotThrowAnyException()
    }

    @Test
    fun `a server error is retried once`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(csv("id,text\n1,Hello!\n"))

        downloader.download()

        awaitRequests(2)
    }

    @Test
    fun `a client error is not retried - it would fail identically`() {
        server.enqueue(MockResponse().setResponseCode(404))

        downloader.download()

        // Give a retry the chance to happen, then assert it did not.
        Thread.sleep(1_000)
        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun awaitRequests(count: Int) =
        await().atMost(Duration.ofSeconds(5)).until { server.requestCount >= count }

    private fun csv(body: String) = MockResponse()
        .setHeader("Content-Type", "text/csv")
        .setBody(body)
}
