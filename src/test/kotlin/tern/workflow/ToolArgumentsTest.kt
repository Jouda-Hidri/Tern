package tern.workflow

import com.anthropic.core.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ToolArgumentsTest {
    private val mapper = ObjectMapper()

    @Test
    fun `a tool input round-trips through jackson as the json it was, not as a bean`() {
        val input = JsonValue.from(
            mapOf(
                "query" to "sum(rate(http_server_requests_seconds_count[1m]))",
                "limit" to 5,
                "nested" to mapOf("start" to "-1h"),
                "labels" to listOf("artic", "antarctic"),
            ),
        )

        val arguments = mapper.readTree(mapper.writeValueAsString(input))

        assertThat(arguments.path("query").asText()).isEqualTo("sum(rate(http_server_requests_seconds_count[1m]))")
        assertThat(arguments.path("limit").asInt()).isEqualTo(5)
        assertThat(arguments.path("nested").path("start").asText()).isEqualTo("-1h")
        assertThat(arguments.path("labels").map { it.asText() }).containsExactly("artic", "antarctic")
    }

    @Test
    fun `an mcp input schema survives the trip into a tool declaration`() {
        val schema = mapper.readTree(
            """{"type":"object","properties":{"query":{"type":"string","description":"PromQL"}},
                "required":["query"]}""",
        )

        val converted = JsonValue.from(mapper.convertValue(schema.path("properties").path("query"), Any::class.java))

        assertThat(mapper.readTree(mapper.writeValueAsString(converted)).path("type").asText()).isEqualTo("string")
    }
}
