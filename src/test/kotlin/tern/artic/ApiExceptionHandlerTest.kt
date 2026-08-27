package tern.artic

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatus

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


    /**
     * Guards the domain's `requireValid` helper. If the value classes went back to stdlib
     * `require`, they would throw plain IllegalArgumentException - and if this handler were
     * widened to catch that, a genuine internal bug would be reported to the caller as a 400.
     */
    @Test
    fun `a plain IllegalArgumentException is our bug, not the caller's`() {
        val response = handler.onUnexpectedFailure(IllegalArgumentException("an internal bug"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body?.message).doesNotContain("an internal bug")
    }

}
