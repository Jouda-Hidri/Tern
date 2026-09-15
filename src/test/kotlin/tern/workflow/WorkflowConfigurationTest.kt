package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class WorkflowConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(JacksonAutoConfiguration::class.java, WebClientAutoConfiguration::class.java),
        )
        .withUserConfiguration(WorkflowConfiguration::class.java)

    @Test
    fun `is absent unless it is turned on`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(InvestigationRunner::class.java)
            assertThat(context).doesNotHaveBean(WorkflowProperties::class.java)
        }
    }

    @Test
    fun `refuses to start when it is on with nowhere to look`() {
        runner.withPropertyValues("tern.workflow.enabled=true").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseMessage(
                    "tern.workflow.enabled is true but no MCP server has a url, so an investigation " +
                        "would have nothing to look at. Configure tern.workflow.mcp.<name>.url or " +
                        "turn the module off",
                )
        }
    }

    @Test
    fun `each investigator kind gets its own implementation`() {
        mapOf(
            "cli" to CliInvestigator::class.java,
            "dry-run" to DryRunInvestigator::class.java,
        ).forEach { (kind, implementation) ->
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(JacksonAutoConfiguration::class.java, WebClientAutoConfiguration::class.java),
                )
                .withUserConfiguration(WorkflowConfiguration::class.java)
                .withPropertyValues(
                    "tern.workflow.enabled=true",
                    "tern.workflow.investigator=$kind",
                    "tern.workflow.mcp.prometheus.url=http://prometheus-mcp:8000/mcp",
                )
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(InvestigationRunner::class.java)
                    assertThat(context.getBean(Investigator::class.java)).isInstanceOf(implementation)
                }
        }
    }

    @Test
    fun `wires one client per configured server and ignores the blank ones`() {
        runner.withPropertyValues(
            "tern.workflow.enabled=true",
            "tern.workflow.mcp.prometheus.url=http://prometheus-mcp:8000/mcp",
            "tern.workflow.mcp.grafana.url=",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(InvestigationRunner::class.java)
            assertThat(context.getBean(ObjectMapper::class.java)).isNotNull()

            @Suppress("UNCHECKED_CAST")
            val clients = context.getBean("mcpClients") as List<McpClient>
            assertThat(clients.map { it.server }).containsExactly("prometheus")
        }
    }
}
