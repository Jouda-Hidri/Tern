package tern.workflow

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "tern.workflow")
data class WorkflowProperties(
    val enabled: Boolean = false,
    val investigator: InvestigatorKind = InvestigatorKind.DRY_RUN,
    val cliCommand: String = "claude",
    val model: String = "claude-opus-5",
    val runTimeout: Duration = Duration.ofMinutes(10),
    val concurrency: Int = 2,
    val queueDepth: Int = 32,
    val historySize: Int = 50,
    val mcp: Map<String, McpServerProperties> = emptyMap(),
) {
    fun configuredMcpServers(): Map<String, McpServerProperties> =
        mcp.filterValues { it.url.isNotBlank() }
}

data class McpServerProperties(
    val url: String = "",
    val token: String = "",
    val timeout: Duration = Duration.ofSeconds(30),
)
