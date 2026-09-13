package tern.workflow

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

private val CREDENTIAL_VARIABLES = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

fun requireAnthropicCredential(env: (String) -> String?) {
    check(CREDENTIAL_VARIABLES.any { !env(it).isNullOrBlank() }) {
        "tern.workflow.investigator is api but none of ${CREDENTIAL_VARIABLES.joinToString(" or ")} is " +
            "set. The SDK does not notice until its first call, so without this the service would " +
            "start healthy, accept alerts, and fail each one on a 401 a minute later"
    }
}

@Configuration
@EnableConfigurationProperties(WorkflowProperties::class)
@ConditionalOnProperty(prefix = "tern.workflow", name = ["enabled"], havingValue = "true")
class WorkflowConfiguration {
    private val logger = LoggerFactory.getLogger(WorkflowConfiguration::class.java)

    @Bean
    fun alertParser(objectMapper: ObjectMapper) = AlertParser(objectMapper)

    @Bean
    fun runStore(properties: WorkflowProperties) = RunStore(properties.historySize)

    @Bean
    fun mcpClients(
        properties: WorkflowProperties,
        objectMapper: ObjectMapper,
        webClientBuilder: WebClient.Builder,
    ): List<McpClient> {
        val servers = properties.configuredMcpServers()
        check(servers.isNotEmpty()) {
            "tern.workflow.enabled is true but no MCP server has a url, so an investigation would " +
                "have nothing to look at. Configure tern.workflow.mcp.<name>.url or turn the module off"
        }
        logger.info("Workflow - MCP servers: ${servers.keys.joinToString()}")
        return servers.map { (name, server) -> McpClient(name, server, objectMapper, webClientBuilder) }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "tern.workflow", name = ["investigator"], havingValue = "api", matchIfMissing = true)
    fun anthropicClient(): AnthropicClient {
        requireAnthropicCredential(System::getenv)
        return AnthropicOkHttpClient.fromEnv()
    }

    @Bean
    @ConditionalOnProperty(prefix = "tern.workflow", name = ["investigator"], havingValue = "api", matchIfMissing = true)
    fun apiInvestigator(
        anthropicClient: AnthropicClient,
        properties: WorkflowProperties,
        objectMapper: ObjectMapper,
        mcpClients: List<McpClient>,
    ): Investigator {
        logger.info("Workflow - investigating over the API as ${properties.model}")
        return ApiInvestigator(anthropicClient, properties, objectMapper, mcpClients)
    }

    @Bean
    @ConditionalOnProperty(prefix = "tern.workflow", name = ["investigator"], havingValue = "cli")
    fun cliInvestigator(properties: WorkflowProperties, objectMapper: ObjectMapper): Investigator {
        logger.info(
            "Workflow - investigating through the local `${properties.cliCommand}` session. No API key is " +
                "used, and the session runs as whoever started this process",
        )
        return CliInvestigator(properties, objectMapper)
    }

    @Bean
    @ConditionalOnProperty(prefix = "tern.workflow", name = ["investigator"], havingValue = "dry-run")
    fun dryRunInvestigator(mcpClients: List<McpClient>): Investigator {
        logger.warn(
            "Workflow - DRY RUN. Alerts will reach the MCP servers and produce a placeholder report. " +
                "No model is called and nothing is charged",
        )
        return DryRunInvestigator(mcpClients)
    }

    @Bean
    fun reportPublisher(
        properties: WorkflowProperties,
        objectMapper: ObjectMapper,
        webClientBuilder: WebClient.Builder,
    ) = ReportPublisher(properties.sink, objectMapper, webClientBuilder)

    @Bean(destroyMethod = "close")
    fun investigationRunner(
        investigator: Investigator,
        runStore: RunStore,
        reportPublisher: ReportPublisher,
        properties: WorkflowProperties,
    ) = InvestigationRunner(investigator, runStore, reportPublisher, properties)
}
