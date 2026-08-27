package tern.tracing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.DispatcherType
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class RequestIdFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestIdFilter::class.java)

    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // An attribute, so the ASYNC dispatch reuses the id rather than minting a second one.
        val requestId = request.getAttribute(REQUEST_ATTRIBUTE) as? String
            ?: request.getHeader(RequestId.HTTP_HEADER)
            ?: RequestId.newId()
        request.setAttribute(REQUEST_ATTRIBUTE, requestId)
        response.setHeader(RequestId.HTTP_HEADER, requestId)

        val worthLogging = !request.requestURI.startsWith(ACTUATOR_PREFIX)
        val initialDispatch = request.dispatcherType == DispatcherType.REQUEST
        if (initialDispatch) request.setAttribute(STARTED_AT, System.currentTimeMillis())

        RequestId.withRequestId(requestId) {
            if (worthLogging && initialDispatch) {
                log.info("HTTP --> {} {} - request received", request.method, request.requestURI)
            }
            try {
                filterChain.doFilter(request, response)
            } finally {
                // A suspending handler goes async here, and the response is not written until
                // the ASYNC dispatch. Logging in this finally would time how long it took to
                // suspend, not how long the caller waited.
                if (worthLogging && !request.isAsyncStarted) {
                    val startedAt = request.getAttribute(STARTED_AT) as? Long
                    val took = startedAt?.let { System.currentTimeMillis() - it }
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
        val STARTED_AT: String = RequestIdFilter::class.qualifiedName + ".startedAt"
    }
}
