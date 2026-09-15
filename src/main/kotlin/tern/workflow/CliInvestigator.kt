package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val FORBIDDEN_TOOLS = listOf("Bash", "Write", "Edit", "NotebookEdit", "WebFetch", "WebSearch")

class CliInvestigator(
    private val properties: WorkflowProperties,
    private val mapper: ObjectMapper,
) : Investigator {
    private val logger = LoggerFactory.getLogger(CliInvestigator::class.java)

    override fun investigate(alert: AlertContext): Investigation {
        val servers = properties.configuredMcpServers()
        check(servers.isNotEmpty()) { "No MCP server is configured, so there is nothing to investigate with" }

        val process = ProcessBuilder(command(alert, servers.keys))
            .directory(neutralDirectory())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.outputStream.close()

        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        if (!process.waitFor(properties.runTimeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("${properties.cliCommand} ran past ${properties.runTimeout}")
        }
        val text = output.get(10, TimeUnit.SECONDS)
        if (process.exitValue() != 0) {
            throw IllegalStateException("${properties.cliCommand} exited ${process.exitValue()}: ${text.take(300)}")
        }
        return read(text)
    }

    private fun command(alert: AlertContext, servers: Set<String>): List<String> = buildList {
        add(properties.cliCommand)
        add("--print")
        add(userPrompt(alert))
        add("--output-format"); add("json")
        add("--model"); add(properties.model)
        add("--append-system-prompt"); add(SYSTEM_PROMPT)
        add("--mcp-config"); add(mcpConfig())
        add("--strict-mcp-config")
        add("--permission-mode"); add("dontAsk")
        add("--allowed-tools"); addAll(servers.map { "mcp__$it" })
        add("--disallowed-tools"); addAll(FORBIDDEN_TOOLS)
    }

    private fun neutralDirectory(): File = Files.createTempDirectory("tern-workflow").toFile().apply { deleteOnExit() }

    private fun mcpConfig(): String {
        val servers = mapper.createObjectNode()
        properties.configuredMcpServers().forEach { (name, server) ->
            val entry = mapper.createObjectNode()
            entry.put("type", "http")
            entry.put("url", server.url)
            if (server.token.isNotBlank()) {
                val headers = mapper.createObjectNode()
                headers.put("Authorization", "Bearer ${server.token}")
                entry.set<ObjectNode>("headers", headers)
            }
            servers.set<ObjectNode>(name, entry)
        }
        val root = mapper.createObjectNode()
        root.set<ObjectNode>("mcpServers", servers)
        return mapper.writeValueAsString(root)
    }

    private fun read(output: String): Investigation {
        val json = runCatching { mapper.readTree(output) }
            .getOrElse { throw IllegalStateException("${properties.cliCommand} did not answer JSON: ${output.take(300)}") }
        if (json.path("is_error").asBoolean(true)) {
            throw IllegalStateException("${properties.cliCommand} reported an error: ${json.path("result").asText()}")
        }
        val report = json.path("result").asText("").trim()
        check(report.isNotBlank()) { "${properties.cliCommand} finished without writing a report" }
        val denials = json.path("permission_denials").map { it.path("tool_name").asText() }
        if (denials.isNotEmpty()) logger.warn("Workflow - the session refused ${denials.joinToString()}")
        return Investigation(
            report = report,
            toolCalls = denials.map { "denied: $it" },
            turns = json.path("num_turns").asInt(0),
            inputTokens = json.path("usage").path("input_tokens").asLong(0),
            outputTokens = json.path("usage").path("output_tokens").asLong(0),
        )
    }
}
