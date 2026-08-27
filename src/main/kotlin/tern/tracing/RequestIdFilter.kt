package tern.tracing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.DispatcherType
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Entry point of a trace: stamps every inbound HTTP request with a request id (reusing an
 * incoming [RequestId.HTTP_HEADER] if the caller already sent one) and logs it arriving.
 */
@Component
class RequestIdFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestIdFilter::class.java)

    /**
     * The controllers are suspending, so Spring completes them through an ASYNC dispatch on a
     * different thread. By default this filter skips that dispatch, which would leave the MDC
     * empty for everything running on it - including the exception handler, whose error bodies
     * quote the request id.
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Held as an attribute so the ASYNC dispatch reuses the id rather than minting a second
        // one for the same request.
        val requestId = request.getAttribute(REQUEST_ATTRIBUTE) as? String
            ?: request.getHeader(RequestId.HTTP_HEADER)
            ?: RequestId.newId()
        request.setAttribute(REQUEST_ATTRIBUTE, requestId)
        response.setHeader(RequestId.HTTP_HEADER, requestId)

        // Probes hit /actuator every few seconds from both docker-compose and kubelet. They
        // still get a request id - so anything they trigger stays traceable - but logging them
        // would bury the traffic that matters.
        val worthLogging = !request.requestURI.startsWith(ACTUATOR_PREFIX) &&
            request.dispatcherType == DispatcherType.REQUEST

        RequestId.withRequestId(requestId) {
            val startedAt = System.currentTimeMillis()
            // One INFO line per request entering the service is the whole of the default
            // output; the matching response line, and everything the request goes on to do,
            // is DEBUG. Failures are not lost by that: ApiExceptionHandler logs them.
            if (worthLogging) {
                log.info("HTTP --> {} {} - request received", request.method, request.requestURI)
            }
            try {
                filterChain.doFilter(request, response)
            } finally {
                if (worthLogging) {
                    val took = System.currentTimeMillis() - startedAt
                    log.debug(
                        "HTTP <-- {} {} - responded {} in {} ms",
                        request.method, request.requestURI, response.status, took,
                    )
                }
            }
        }
    }

    private companion object {
        const val ACTUATOR_PREFIX = "/actuator"
        val REQUEST_ATTRIBUTE: String = RequestIdFilter::class.qualifiedName + ".requestId"
    }
}
