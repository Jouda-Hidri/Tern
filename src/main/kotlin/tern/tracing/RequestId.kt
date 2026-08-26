package tern.tracing

import io.grpc.Metadata
import org.slf4j.MDC
import java.util.UUID

/**
 * A single id that follows one request across every hop: HTTP into artic, gRPC over to
 * antarctic, and back. It is put in the SLF4J MDC so every log line carries it (see the
 * `logging.pattern.level` in application.yml), which is what makes `docker compose logs`
 * readable end to end.
 */
object RequestId {
    const val MDC_KEY = "requestId"
    const val HTTP_HEADER = "X-Request-Id"

    val METADATA_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER)

    fun current(): String? = MDC.get(MDC_KEY)

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    /** Runs [block] with [requestId] in the MDC, restoring whatever was there before. */
    fun <T> withRequestId(requestId: String?, block: () -> T): T {
        val previous = MDC.get(MDC_KEY)
        if (requestId != null) MDC.put(MDC_KEY, requestId) else MDC.remove(MDC_KEY)
        try {
            return block()
        } finally {
            if (previous != null) MDC.put(MDC_KEY, previous) else MDC.remove(MDC_KEY)
        }
    }
}
