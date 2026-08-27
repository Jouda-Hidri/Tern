package tern.artic

import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tern.domain.InvalidMessageException
import tern.tracing.RequestId

/**
 * Without this, a downstream gRPC failure reaches the caller as a bare 500 with an empty body.
 * Each error carries the request id, so a client report can be tied straight back to the log
 * lines for that request across both services.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(InvalidMessageException::class)
    fun onInvalidMessage(e: InvalidMessageException): ResponseEntity<ApiError> {
        logger.warn("Rejected request: ${e.message}")
        return respond(HttpStatus.BAD_REQUEST, e.message ?: "Invalid message")
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ApiError> {
        logger.warn("Rejected malformed body: ${e.message}")
        return respond(HttpStatus.BAD_REQUEST, "Request body is malformed or missing required fields")
    }

    @ExceptionHandler(StatusRuntimeException::class)
    fun onGrpcFailure(e: StatusRuntimeException): ResponseEntity<ApiError> {
        val status = when (e.status.code) {
            Status.Code.INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST
            Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
            Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
            Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
            Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT
            Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        logger.error("Antarctic call failed with ${e.status.code}, answering $status", e)
        return respond(status, "Antarctic is ${e.status.code.name.lowercase().replace('_', ' ')}")
    }

    @ExceptionHandler(Exception::class)
    fun onUnexpectedFailure(e: Exception): ResponseEntity<ApiError> {
        logger.error("Unexpected failure", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error")
    }

    private fun respond(status: HttpStatus, message: String) =
        ResponseEntity.status(status).body(ApiError(status.value(), message, RequestId.current()))
}

data class ApiError(val status: Int, val message: String, val requestId: String?)
