package tern.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

const val MCP_PROTOCOL_VERSION = "2025-06-18"
private const val SESSION_HEADER = "Mcp-Session-Id"
private const val VERSION_HEADER = "MCP-Protocol-Version"

data class McpTool(val server: String, val name: String, val description: String, val inputSchema: JsonNode) {
    val qualifiedName: String get() = "${server}__$name"
}

class McpFailure(message: String) : RuntimeException(message)

class McpClient(
    val server: String,
    private val properties: McpServerProperties,
    private val mapper: ObjectMapper,
    webClientBuilder: WebClient.Builder,
) {
    private val logger = LoggerFactory.getLogger(McpClient::class.java)

    // Servers answer /mcp with a 307 to /mcp/, and an unfollowed redirect reads as a server with
    // no tools rather than one that failed.
    private val client = webClientBuilder.clone()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(true)))
        .baseUrl(properties.url)
        .build()
    private val session = AtomicReference<String>("")
    private val initialised = AtomicReference(false)
    private val nextId = AtomicLong(1)

    fun listTools(): List<McpTool> {
        val result = request("tools/list", mapper.createObjectNode())
        return result.path("tools").mapNotNull { tool ->
            val name = tool.path("name").asText("")
            if (name.isBlank()) {
                null
            } else {
                McpTool(
                    server = server,
                    name = name,
                    description = tool.path("description").asText(""),
                    inputSchema = tool.path("inputSchema").takeIf { it.isObject } ?: emptySchema(),
                )
            }
        }
    }

    fun callTool(name: String, arguments: JsonNode): String {
        val params = mapper.createObjectNode()
        params.put("name", name)
        params.set<ObjectNode>("arguments", if (arguments.isObject) arguments else mapper.createObjectNode())
        val result = request("tools/call", params)
        val text = result.path("content")
            .filter { it.path("type").asText() == "text" }
            .joinToString("\n") { it.path("text").asText("") }
            .ifBlank { mapper.writeValueAsString(result) }
        if (result.path("isError").asBoolean(false)) throw McpFailure(text)
        return text
    }

    private fun request(method: String, params: ObjectNode): JsonNode {
        ensureInitialised()
        val response = exchange(envelope(method, params))
        if (response.status != 404) return unwrap(response)
        initialised.set(false)
        session.set("")
        ensureInitialised()
        return unwrap(exchange(envelope(method, params)))
    }

    private fun ensureInitialised() {
        if (initialised.get()) return
        synchronized(this) {
            if (initialised.get()) return
            val params = mapper.createObjectNode()
            params.put("protocolVersion", MCP_PROTOCOL_VERSION)
            params.set<ObjectNode>("capabilities", mapper.createObjectNode())
            params.set<ObjectNode>(
                "clientInfo",
                mapper.createObjectNode().put("name", "tern-workflow").put("version", "1"),
            )
            val response = exchange(envelope("initialize", params))
            response.sessionId?.let { session.set(it) }
            unwrap(response)
            initialised.set(true)
            exchange(notification("notifications/initialized"))
            logger.info("MCP - initialised $server at ${properties.url}")
        }
    }

    private fun envelope(method: String, params: ObjectNode): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("jsonrpc", "2.0")
        node.put("id", nextId.getAndIncrement())
        node.put("method", method)
        node.set<ObjectNode>("params", params)
        return node
    }

    private fun notification(method: String): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("jsonrpc", "2.0")
        node.put("method", method)
        return node
    }

    private fun exchange(payload: ObjectNode): McpResponse {
        val response = client.post()
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers ->
                headers.set(VERSION_HEADER, MCP_PROTOCOL_VERSION)
                session.get().takeIf { it.isNotBlank() }?.let { headers.set(SESSION_HEADER, it) }
                properties.token.takeIf { it.isNotBlank() }
                    ?.let { headers.set(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            }
            .bodyValue(mapper.writeValueAsString(payload))
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { body ->
                        McpResponse(
                            status = clientResponse.statusCode().value(),
                            sessionId = clientResponse.headers().header(SESSION_HEADER).firstOrNull(),
                            body = body,
                        )
                    }
            }
            .timeout(properties.timeout)
            .onErrorMap {
                when (it) {
                    is McpFailure -> it
                    is TimeoutException -> McpFailure("$server did not answer within ${properties.timeout}")
                    else -> McpFailure("$server is unreachable: ${it.message}")
                }
            }
            .block()
            ?: throw McpFailure("$server answered nothing")
        if (response.status >= 400 && response.status != 404) {
            throw McpFailure("$server answered ${response.status}: ${response.body.take(200)}")
        }
        return response
    }

    private fun unwrap(response: McpResponse): JsonNode {
        if (response.body.isBlank()) return mapper.createObjectNode()
        val node = mapper.readTree(extractPayload(response.body))
        node.path("error").takeIf { it.isObject }?.let {
            throw McpFailure("$server returned ${it.path("code").asInt()}: ${it.path("message").asText()}")
        }
        return node.path("result")
    }

    private fun extractPayload(body: String): String {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("event:") && !trimmed.startsWith("data:")) return body
        return body.lineSequence()
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trim() }
            .ifBlank { "{}" }
    }

    private fun emptySchema(): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("type", "object")
        node.set<ObjectNode>("properties", mapper.createObjectNode())
        return node
    }

    private data class McpResponse(val status: Int, val sessionId: String?, val body: String)
}
