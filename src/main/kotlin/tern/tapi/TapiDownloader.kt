package tern.tapi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import tern.tracing.RequestId
import java.time.Duration

/**
 * Streams the large CSV that tapi exposes, after a message has been saved.
 *
 * Two things this deliberately does not do. It does not make the caller wait: the download is a
 * side effect of saving, not part of the answer, so [download] launches and returns. And it does
 * not fail anything: tapi is a separate deployment that may not be running at all (it is absent
 * from docker-compose), so an error is logged and dropped.
 *
 * The work is launched into a scope this bean owns rather than a global one, and the scope is
 * cancelled on shutdown - so in-flight downloads are torn down with the application instead of
 * outliving it. A [SupervisorJob] keeps one failed download from cancelling its siblings.
 */
@Component
class TapiDownloader(
    private val tapiClient: WebClient,
    private val properties: TapiProperties,
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(TapiDownloader::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("tapi"))

    fun download() {
        val requestId = RequestId.current()
        scope.launch {
            var lines = 0L
            lines() // one line per emission, streamed - the file is never held whole in memory
                .catch { e ->
                    RequestId.withRequestId(requestId) {
                        logger.warn("Tapi - Unreachable at ${properties.url}, skipping download: ${e.message}")
                    }
                }
                .collect { line ->
                    // tapi serves a ~200MB file, so a line apiece would be millions of log
                    // entries. The first proves what is arriving; the rest are counted.
                    if (lines == 0L) RequestId.withRequestId(requestId) { logger.debug("Tapi - first line: $line") }
                    lines++
                }
            RequestId.withRequestId(requestId) {
                logger.debug("Tapi - Download finished, $lines line(s).")
            }
        }
    }

    /**
     * `bodyToFlux(String)` splits the response on newlines as the bytes arrive; `asFlow` hands
     * that to the coroutine world with backpressure intact.
     */
    private fun lines(): Flow<String> = tapiClient.get()
        .retrieve()
        .bodyToFlux(String::class.java)
        // Retrying restarts the stream from the beginning, so this is kept to a single attempt
        // and only for the transient cases - a 4xx would fail identically.
        .retryWhen(
            Retry.backoff(1, Duration.ofMillis(500))
                .filter { it !is WebClientResponseException || it.statusCode.is5xxServerError }
        )
        .asFlow()

    override fun destroy() = scope.cancel("Application is shutting down")
}
