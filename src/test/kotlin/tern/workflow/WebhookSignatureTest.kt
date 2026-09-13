package tern.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebhookSignatureTest {
    private val body = """{"alerts":[]}"""

    @Test
    fun `accepts a matching signature`() {
        assertThat(WebhookSignature.verify("shh", body, WebhookSignature.sign("shh", body))).isTrue()
    }

    @Test
    fun `accepts a versioned signature and a list of candidates`() {
        val signature = WebhookSignature.sign("shh", body)

        assertThat(WebhookSignature.verify("shh", body, "v1=$signature")).isTrue()
        assertThat(WebhookSignature.verify("shh", body, "v1=deadbeef,v1=$signature")).isTrue()
    }

    @Test
    fun `rejects a wrong signature, a wrong body and a missing header`() {
        assertThat(WebhookSignature.verify("shh", body, "deadbeef")).isFalse()
        assertThat(WebhookSignature.verify("shh", """{"alerts":[1]}""", WebhookSignature.sign("shh", body))).isFalse()
        assertThat(WebhookSignature.verify("shh", body, null)).isFalse()
    }

    @Test
    fun `an unset secret accepts anything, which is why the endpoint must stay internal`() {
        assertThat(WebhookSignature.verify("", body, null)).isTrue()
    }
}
