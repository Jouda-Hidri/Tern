package tern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith

class TernConfigurationTest {

    private val configuration = TernConfiguration()

    @Test
    fun `starts when the detector gives up well inside the deadline`() {
        val client = configuration.translateClient(
            url = "http://localhost:5000",
            timeout = Duration.ofMillis(500),
            deadline = Duration.ofSeconds(2),
        )

        assertThat(client).isNotNull
    }

    @Test
    fun `refuses to start when a slow detector would outlive the deadline`() {
        val thrown = assertFailsWith<IllegalStateException> {
            configuration.translateClient(
                url = "http://localhost:5000",
                timeout = Duration.ofSeconds(5),
                deadline = Duration.ofSeconds(2),
            )
        }

        assertThat(thrown.message).contains("must be shorter than")
    }

    @Test
    fun `refuses to start when they are merely equal, which is already too late`() {
        assertFailsWith<IllegalStateException> {
            configuration.translateClient(
                url = "http://localhost:5000",
                timeout = Duration.ofSeconds(2),
                deadline = Duration.ofSeconds(2),
            )
        }
    }

    @Test
    fun `a blank detector url still yields a client rather than failing to build one`() {
        val client = configuration.translateClient(
            url = "",
            timeout = Duration.ofMillis(500),
            deadline = Duration.ofSeconds(2),
        )

        assertThat(client).isNotNull
    }
}
