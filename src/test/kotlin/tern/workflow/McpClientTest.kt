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
import java.time.Duration

class McpClientTest {
    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun client(timeout: Duration = Duration.ofSeconds(5)) = McpClient(
        server = "prometheus",
        properties = McpServerProperties(url = server.url("/mcp").toString(), timeout = timeout),
        mapper = mapper,
        webClientBuilder = WebClient.builder(),
    )

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun initialised() = json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""")
        .setHeader("Mcp-Session-Id", "session-1")

    @Test
    fun `initialises once, then lists tools`() {
        server.enqueue(initialised())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            json(
                """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[
                  {"name":"execute_query","description":"Run an instant PromQL query",
                   "inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}]}}
                """.trimIndent(),
            ),
        )

        val tools = client().listTools()

        assertThat(tools).singleElement().satisfies({
            assertThat(it.name).isEqualTo("execute_query")
            assertThat(it.qualifiedName).isEqualTo("prometheus__execute_query")
            assertThat(it.inputSchema.path("required").map { field -> field.asText() }).containsExactly("query")
        })
        assertThat(server.takeRequest().body.readUtf8()).contains("\"method\":\"initialize\"")
        assertThat(server.takeRequest().body.readUtf8()).contains("notifications/initialized")
        val listRequest = server.takeRequest()
        assertThat(listRequest.getHeader("Mcp-Session-Id")).isEqualTo("session-1")
        assertThat(listRequest.getHeader("MCP-Protocol-Version")).isEqualTo(MCP_PROTOCOL_VERSION)
    }

    @Test
    fun `reads a tool result delivered as server-sent events`() {
        server.enqueue(initialised())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: message\n" +
                        """data: {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"up 1"}]}}""" +
                        "\n\n",
                ),
        )

        val result = client().callTool("execute_query", mapper.readTree("""{"query":"up"}"""))

        assertThat(result).isEqualTo("up 1")
    }

    @Test
    fun `a tool that reports isError is a failure, not a result`() {
        server.enqueue(initialised())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"bad query"}]}}"""),
        )

        assertThatThrownBy { client().callTool("execute_query", mapper.readTree("""{"query":"("}""")) }
            .isInstanceOf(McpFailure::class.java)
            .hasMessageContaining("bad query")
    }

    @Test
    fun `a json-rpc error is a failure`() {
        server.enqueue(initialised())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"no such method"}}"""))

        assertThatThrownBy { client().listTools() }
            .isInstanceOf(McpFailure::class.java)
            .hasMessageContaining("no such method")
    }

    @Test
    fun `an expired session is re-initialised once`() {
        server.enqueue(initialised())
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(initialised().setHeader("Mcp-Session-Id", "session-2"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""))

        assertThat(client().listTools()).isEmpty()
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun `an unreachable server fails rather than hanging`() {
        server.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS))

        assertThatThrownBy { client(Duration.ofMillis(300)).listTools() }
            .isInstanceOf(McpFailure::class.java)
    }
}
