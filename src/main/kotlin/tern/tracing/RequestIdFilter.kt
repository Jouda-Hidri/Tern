package tern.tracing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Entry point of a trace: stamps every inbound HTTP request with a request id (reusing an
 * incoming [RequestId.HTTP_HEADER] if the caller already sent one) and logs the start and
 * end of the request with its status and duration.
 */
@Component
class RequestIdFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestIdFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(RequestId.HTTP_HEADER) ?: RequestId.newId()
        response.setHeader(RequestId.HTTP_HEADER, requestId)

        RequestId.withRequestId(requestId) {
            val startedAt = System.currentTimeMillis()
            log.info("HTTP --> {} {} - request received", request.method, request.requestURI)
            try {
                filterChain.doFilter(request, response)
            } finally {
                val took = System.currentTimeMillis() - startedAt
                log.info(
                    "HTTP <-- {} {} - responded {} in {} ms",
                    request.method, request.requestURI, response.status, took,
                )
            }
        }
    }
}
