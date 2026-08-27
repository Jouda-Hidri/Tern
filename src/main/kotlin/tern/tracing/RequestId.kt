package tern.tracing

import io.grpc.Metadata
import org.slf4j.MDC
import java.util.UUID

// Held in the SLF4J MDC, which `logging.pattern.level` in application.yml prints on every line.
object RequestId {
    const val MDC_KEY = "requestId"
    const val HTTP_HEADER = "X-Request-Id"

    val METADATA_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER)

    fun current(): String? = MDC.get(MDC_KEY)

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

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
