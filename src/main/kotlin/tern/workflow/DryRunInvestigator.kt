package tern.workflow

import org.slf4j.LoggerFactory

class DryRunInvestigator(private val clients: List<McpClient>) : Investigator {
    private val logger = LoggerFactory.getLogger(DryRunInvestigator::class.java)

    override fun investigate(alert: AlertContext): Investigation {
        val discovered = clients.associate { client ->
            client.server to runCatching { client.listTools() }
                .onFailure { logger.warn("Workflow - ${client.server} tools/list failed: ${it.message}") }
        }
        val tools = discovered.values.mapNotNull { it.getOrNull() }.flatten()
        if (clients.isNotEmpty() && tools.isEmpty()) {
            throw IllegalStateException("No MCP server answered tools/list, so there is nothing to investigate with")
        }
        logger.info("Workflow - dry run reached ${discovered.size} server(s) and found ${tools.size} tool(s)")
        return Investigation(
            report = report(alert, discovered),
            toolCalls = tools.map { it.qualifiedName },
            turns = 0,
            inputTokens = 0,
            outputTokens = 0,
        )
    }

    private fun report(alert: AlertContext, discovered: Map<String, Result<List<McpTool>>>): String = buildString {
        appendLine("## Summary")
        appendLine("Dry run. The alert arrived and the MCP servers answered. No model was called.")
        appendLine()
        appendLine("## Impact")
        appendLine("None. No model was called and nothing was charged.")
        appendLine()
        appendLine("## Evidence")
        appendLine("The alert, as the investigator would have seen it:")
        appendLine()
        appendLine("```")
        appendLine(alert.render())
        appendLine("```")
        appendLine()
        discovered.forEach { (server, result) ->
            result.fold(
                onSuccess = { tools ->
                    appendLine("`$server` answered `tools/list` with ${tools.size} tool(s):")
                    tools.forEach { appendLine("- `${it.name}` - ${it.description.lineSequence().first().take(90)}") }
                },
                onFailure = { appendLine("`$server` did not answer `tools/list`: ${it.message}") },
            )
            appendLine()
        }
        appendLine("`tools/call` was not exercised: only the model decides which tools to call.")
        appendLine()
        appendLine("## Likely cause")
        appendLine("Not determined. A dry run checks that an alert can reach the tools, not what the alert means.")
        appendLine()
        appendLine("## Next steps")
        appendLine("Run with `WORKFLOW_INVESTIGATOR=cli` to have this alert actually investigated.")
        appendLine()
        appendLine("## Confidence")
        append("n/a - nothing was investigated.")
    }
}
