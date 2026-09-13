package tern.workflow

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.time.Instant

private const val MAX_TOOL_RESULT_CHARS = 20_000

class ApiInvestigator(
    private val anthropic: AnthropicClient,
    private val properties: WorkflowProperties,
    private val mapper: ObjectMapper,
    private val clients: List<McpClient>,
) : Investigator {
    private val logger = LoggerFactory.getLogger(ApiInvestigator::class.java)

    override fun investigate(alert: AlertContext): Investigation {
        val discovered = discoverTools()
        if (clients.isNotEmpty() && discovered.isEmpty()) {
            throw IllegalStateException("No MCP server answered tools/list, so there is nothing to investigate with")
        }

        val byName = discovered.associateBy { toolName(it) }
        val tools = discovered.map { declare(toolName(it), it) }
        val messages = mutableListOf(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userPrompt(alert))
                .build(),
        )

        val toolCalls = mutableListOf<String>()
        val deadline = Instant.now().plus(properties.runTimeout)
        var inputTokens = 0L
        var outputTokens = 0L

        repeat(properties.maxTurns) { turn ->
            if (Instant.now().isAfter(deadline)) {
                throw IllegalStateException("Investigation ran past ${properties.runTimeout}")
            }
            val message = anthropic.messages().create(params(messages, tools))
            inputTokens += message.usage().inputTokens()
            outputTokens += message.usage().outputTokens()
            messages += MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(message.content().map { it.toParam() })
                .build()

            if (message.stopReason().orElse(null) != StopReason.TOOL_USE) {
                val report = textOf(message)
                if (report.isBlank()) {
                    throw IllegalStateException(
                        "Stopped on ${message.stopReason().orElse(null)} without writing a report",
                    )
                }
                return Investigation(report, toolCalls, turn + 1, inputTokens, outputTokens)
            }

            val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                val tool = byName[use.name()]
                val arguments = mapper.readTree(mapper.writeValueAsString(use._input()))
                toolCalls += "${use.name()} ${compact(arguments)}"
                execute(tool, use.id(), arguments)
            }
            messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build()
        }

        throw IllegalStateException("Investigation did not settle within ${properties.maxTurns} turns")
    }

    private fun execute(tool: McpTool?, toolUseId: String, arguments: JsonNode): ContentBlockParam {
        val client = clients.firstOrNull { it.server == tool?.server }
        val outcome = when {
            tool == null || client == null -> Result.failure(McpFailure("unknown tool"))
            else -> runCatching { client.callTool(tool.name, arguments) }
        }
        outcome.exceptionOrNull()?.let { logger.warn("Workflow - tool ${tool?.qualifiedName} failed: ${it.message}") }
        return ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder()
                .toolUseId(toolUseId)
                .content(outcome.getOrElse { it.message ?: "tool call failed" }.take(MAX_TOOL_RESULT_CHARS))
                .isError(outcome.isFailure)
                .build(),
        )
    }

    private fun params(messages: List<MessageParam>, tools: List<Tool>): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(properties.model)
            .maxTokens(properties.maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(OutputConfig.builder().effort(effort()).build())
            .system(SYSTEM_PROMPT)
        tools.forEach(builder::addTool)
        messages.forEach(builder::addMessage)
        return builder.build()
    }

    private fun effort(): OutputConfig.Effort = when (properties.effort.lowercase()) {
        "low" -> OutputConfig.Effort.LOW
        "medium" -> OutputConfig.Effort.MEDIUM
        "xhigh" -> OutputConfig.Effort.XHIGH
        "max" -> OutputConfig.Effort.MAX
        else -> OutputConfig.Effort.HIGH
    }

    private fun discoverTools(): List<McpTool> = clients.flatMap { client ->
        runCatching { client.listTools() }
            .onFailure { logger.warn("Workflow - ${client.server} tools/list failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun declare(name: String, tool: McpTool): Tool {
        val properties = Tool.InputSchema.Properties.builder()
        tool.inputSchema.path("properties").takeIf { it.isObject }?.properties()?.forEach { (key, value) ->
            properties.putAdditionalProperty(key, JsonValue.from(mapper.convertValue(value, Any::class.java)))
        }
        val schema = Tool.InputSchema.builder()
            .properties(properties.build())
            .required(tool.inputSchema.path("required").map { it.asText() })
            .build()
        return Tool.builder()
            .name(name)
            .description(tool.description.ifBlank { "${tool.name} on ${tool.server}" })
            .inputSchema(schema)
            .build()
    }

    private fun toolName(tool: McpTool): String =
        tool.qualifiedName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(128)

    private fun textOf(message: Message): String = message.content()
        .mapNotNull { it.text().orElse(null) }
        .joinToString("\n") { it.text() }
        .trim()

    private fun compact(arguments: JsonNode): String = mapper.writeValueAsString(arguments).take(200)
}
