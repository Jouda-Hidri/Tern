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
        "tern.workflow.enabled is true but none of ${CREDENTIAL_VARIABLES.joinToString(" or ")} is " +
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
    @ConditionalOnMissingBean
    fun anthropicClient(): AnthropicClient {
        requireAnthropicCredential(System::getenv)
        return AnthropicOkHttpClient.fromEnv()
    }

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
    fun investigator(
        anthropicClient: AnthropicClient,
        properties: WorkflowProperties,
        objectMapper: ObjectMapper,
        mcpClients: List<McpClient>,
    ) = Investigator(anthropicClient, properties, objectMapper, mcpClients)

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
