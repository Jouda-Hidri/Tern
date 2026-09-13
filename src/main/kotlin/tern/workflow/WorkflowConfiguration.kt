package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

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
    @ConditionalOnProperty(prefix = "tern.workflow", name = ["investigator"], havingValue = "cli")
    fun cliInvestigator(properties: WorkflowProperties, objectMapper: ObjectMapper): Investigator {
        logger.info("Workflow - investigating through the local `${properties.cliCommand}` session")
        return CliInvestigator(properties, objectMapper)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "tern.workflow",
        name = ["investigator"],
        havingValue = "dry-run",
        matchIfMissing = true,
    )
    fun dryRunInvestigator(mcpClients: List<McpClient>): Investigator {
        logger.warn("Workflow - DRY RUN. Alerts reach the MCP servers, no model is called")
        return DryRunInvestigator(mcpClients)
    }

    @Bean(destroyMethod = "close")
    fun investigationRunner(
        investigator: Investigator,
        runStore: RunStore,
        properties: WorkflowProperties,
    ) = InvestigationRunner(investigator, runStore, properties)
}
