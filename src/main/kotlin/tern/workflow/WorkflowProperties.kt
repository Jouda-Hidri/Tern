package tern.workflow

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "tern.workflow")
data class WorkflowProperties(
    val enabled: Boolean = false,
    val investigator: InvestigatorKind = InvestigatorKind.API,
    val cliCommand: String = "claude",
    val model: String = "claude-opus-5",
    val effort: String = "high",
    val maxTokens: Long = 16_000,
    val maxTurns: Int = 24,
    val runTimeout: Duration = Duration.ofMinutes(10),
    val concurrency: Int = 2,
    val queueDepth: Int = 32,
    val historySize: Int = 50,
    val webhookSecret: String = "",
    val signatureHeader: String = "X-Tern-Signature",
    val mcp: Map<String, McpServerProperties> = emptyMap(),
    val sink: SinkProperties = SinkProperties(),
) {
    fun configuredMcpServers(): Map<String, McpServerProperties> =
        mcp.filterValues { it.url.isNotBlank() }
}

data class McpServerProperties(
    val url: String = "",
    val token: String = "",
    val timeout: Duration = Duration.ofSeconds(30),
)

data class SinkProperties(
    val url: String = "",
    val token: String = "",
    val timeout: Duration = Duration.ofSeconds(10),
)
