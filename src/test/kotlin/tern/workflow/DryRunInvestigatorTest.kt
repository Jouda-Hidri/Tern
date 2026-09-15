package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class DryRunInvestigatorTest {
    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()
    private val alert = AlertContext(
        source = "alertmanager",
        fingerprint = "ab61a7b0",
        title = "TernTargetDown",
        summary = "Prometheus cannot reach antarctic:8080.",
        severity = "critical",
        status = "firing",
        startedAt = "2026-09-13T16:02:21Z",
        labels = mapOf("role" to "antarctic"),
        links = emptyMap(),
    )

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun client(name: String = "prometheus") = McpClient(
        server = name,
        properties = McpServerProperties(url = server.url("/mcp").toString()),
        mapper = mapper,
        webClientBuilder = WebClient.builder(),
    )

    private fun handshake() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "session-1")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(202))
    }

    private fun tools(body: String) = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody(body),
    )

    @Test
    fun `reaches the servers and reports what it found, without calling a model`() {
        handshake()
        tools(
            """
            {"jsonrpc":"2.0","id":2,"result":{"tools":[
              {"name":"execute_query","description":"Run an instant PromQL query","inputSchema":{"type":"object"}},
              {"name":"get_targets","description":"Get scrape targets","inputSchema":{"type":"object"}}]}}
            """.trimIndent(),
        )

        val investigation = DryRunInvestigator(listOf(client())).investigate(alert)

        assertThat(investigation.toolCalls)
            .containsExactly("prometheus__execute_query", "prometheus__get_targets")
        assertThat(investigation.turns).isZero()
        assertThat(investigation.inputTokens).isZero()
        assertThat(investigation.outputTokens).isZero()
        assertThat(investigation.report)
            .contains("## Summary", "## Impact", "## Evidence", "## Likely cause", "## Next steps", "## Confidence")
            .contains("No model was called")
            .contains("TernTargetDown")
            .contains("`execute_query` - Run an instant PromQL query")
            .contains("WORKFLOW_INVESTIGATOR=cli")
    }

    @Test
    fun `names the server that did not answer instead of pretending it did`() {
        handshake()
        tools("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ok","inputSchema":{"type":"object"}}]}}""")
        val healthy = client("prometheus")

        val broken = McpClient(
            server = "grafana",
            properties = McpServerProperties(url = "http://127.0.0.1:1/mcp"),
            mapper = mapper,
            webClientBuilder = WebClient.builder(),
        )

        val report = DryRunInvestigator(listOf(healthy, broken)).investigate(alert).report

        assertThat(report).contains("`prometheus` answered `tools/list` with 1 tool(s)")
        assertThat(report).contains("`grafana` did not answer `tools/list`")
    }

    @Test
    fun `fails when nothing answers, exactly as a real investigation would`() {
        val broken = McpClient(
            server = "prometheus",
            properties = McpServerProperties(url = "http://127.0.0.1:1/mcp"),
            mapper = mapper,
            webClientBuilder = WebClient.builder(),
        )

        assertThatThrownBy { DryRunInvestigator(listOf(broken)).investigate(alert) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No MCP server answered tools/list")
    }
}
