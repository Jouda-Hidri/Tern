package tern.artic

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatus
import tern.domain.InvalidMessageException
import tern.domain.MessageText

/**
 * The mapping is worth testing on its own: an antarctic outage reaching the caller as a 500
 * rather than a 503 is invisible until someone looks, and it regressed exactly once already -
 * the coroutine stubs throw the checked StatusException, and the handler only matched the
 * runtime one.
 */
class ApiExceptionHandlerTest {

    private val handler = ApiExceptionHandler()

    @ParameterizedTest
    @CsvSource(
        "UNAVAILABLE, 503",
        "DEADLINE_EXCEEDED, 504",
        "INVALID_ARGUMENT, 400",
        "NOT_FOUND, 404",
        "PERMISSION_DENIED, 403",
        "UNAUTHENTICATED, 401",
        "INTERNAL, 500",
        "UNKNOWN, 500",
    )
    fun `maps each grpc code onto an http status`(code: Status.Code, expected: Int) {
        val response = handler.onGrpcFailure(StatusException(Status.fromCode(code)))

        assertThat(response.statusCode.value()).isEqualTo(expected)
    }

    @Test
    fun `handles the runtime flavour of the same failure identically`() {
        val checked = handler.onGrpcFailure(StatusException(Status.UNAVAILABLE))
        val runtime = handler.onGrpcFailure(StatusRuntimeException(Status.UNAVAILABLE))

        assertThat(runtime.statusCode).isEqualTo(checked.statusCode)
        assertThat(runtime.body?.message).isEqualTo(checked.body?.message)
    }

    @Test
    fun `a domain validation failure is the caller's fault, not ours`() {
        val response = handler.onInvalidMessage(InvalidMessageException("Message text must not be blank"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body?.message).contains("must not be blank")
    }

    @Test
    fun `value class validation surfaces through the same handler`() {
        val thrown = runCatching { MessageText("  ") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(InvalidMessageException::class.java)
        assertThat(handler.onInvalidMessage(thrown as InvalidMessageException).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
