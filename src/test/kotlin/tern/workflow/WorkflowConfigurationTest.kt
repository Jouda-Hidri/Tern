package tern.workflow

import com.anthropic.client.AnthropicClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
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
        .withBean(AnthropicClient::class.java, { mockk<AnthropicClient>(relaxed = true) })

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
    fun `refuses to start without a credential, rather than failing every alert on a 401`() {
        assertThatThrownBy { requireAnthropicCredential { null } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN")

        assertThatThrownBy { requireAnthropicCredential { "" } }
            .isInstanceOf(IllegalStateException::class.java)

        requireAnthropicCredential { name -> "set".takeIf { name == "ANTHROPIC_AUTH_TOKEN" } }
        requireAnthropicCredential { name -> "set".takeIf { name == "ANTHROPIC_API_KEY" } }
    }

    @Test
    fun `cli and dry run need no credential at all, and never build an anthropic client`() {
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
                    assertThat(context).doesNotHaveBean(AnthropicClient::class.java)
                    assertThat(context.getBean(Investigator::class.java)).isInstanceOf(implementation)
                }
        }
    }

    @Test
    fun `the api is what you get when nothing says otherwise`() {
        runner.withPropertyValues(
            "tern.workflow.enabled=true",
            "tern.workflow.mcp.prometheus.url=http://prometheus-mcp:8000/mcp",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(Investigator::class.java)).isInstanceOf(ApiInvestigator::class.java)
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
