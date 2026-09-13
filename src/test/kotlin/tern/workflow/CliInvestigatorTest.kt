package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Duration

class CliInvestigatorTest {
    @TempDir
    lateinit var directory: Path

    private val mapper = ObjectMapper()
    private val alert = AlertContext(
        source = "alertmanager",
        fingerprint = "ab61a7b0",
        title = "TernTargetDown",
        summary = "Prometheus cannot reach antarctic:8080.",
        severity = "critical",
        status = "firing",
        startedAt = "2026-09-13T16:02:21Z",
        labels = mapOf("role" to "antarctic"),
        links = emptyMap(),
    )

    private fun properties(command: String, timeout: Duration = Duration.ofSeconds(30)) = WorkflowProperties(
        enabled = true,
        investigator = InvestigatorKind.CLI,
        cliCommand = command,
        runTimeout = timeout,
        mcp = mapOf(
            "prometheus" to McpServerProperties(url = "http://localhost:8000/mcp"),
            "grafana" to McpServerProperties(url = "http://localhost:8001/mcp", token = "grafana-token"),
            "kubernetes" to McpServerProperties(url = ""),
        ),
    )

    private fun capturedArgs(): List<String> =
        File(directory.toFile(), "args.txt").readText().split('\u0000').filter { it.isNotEmpty() }

    private fun fakeCli(body: String): String {
        val script = File(directory.toFile(), "fake-claude")
        script.writeText("#!/usr/bin/env bash\n$body\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    @Test
    fun `reads the report, the turn count and the tokens out of the cli json`() {
        val cli = fakeCli(
            """cat <<'JSON'
            {"type":"result","subtype":"success","is_error":false,"num_turns":7,
             "result":"## Summary\nantarctic is down.","usage":{"input_tokens":1234,"output_tokens":56}}
JSON""",
        )

        val investigation = CliInvestigator(properties(cli), mapper).investigate(alert)

        assertThat(investigation.report).isEqualTo("## Summary\nantarctic is down.")
        assertThat(investigation.turns).isEqualTo(7)
        assertThat(investigation.inputTokens).isEqualTo(1234)
        assertThat(investigation.outputTokens).isEqualTo(56)
    }

    @Test
    fun `passes the alert, the system prompt and only the reachable servers`() {
        val cli = fakeCli(
            """printf '%s\0' "$@" > "$(dirname "$0")/args.txt"
            echo '{"is_error":false,"result":"ok","num_turns":1}'""",
        )

        CliInvestigator(properties(cli), mapper).investigate(alert)
        val args = capturedArgs()

        assertThat(args).contains("--print", "--strict-mcp-config", "--output-format", "json")
        assertThat(args).contains("mcp__prometheus", "mcp__grafana")
        assertThat(args).doesNotContain("mcp__kubernetes")
        assertThat(args.single { it.startsWith("Investigate this alert") }).contains("TernTargetDown")
        assertThat(args.single { it.contains("on-call engineer") }).contains("## Confidence")

        val config = mapper.readTree(args.single { it.startsWith("{\"mcpServers\"") })
        assertThat(config.path("mcpServers").path("prometheus").path("url").asText())
            .isEqualTo("http://localhost:8000/mcp")
        assertThat(config.path("mcpServers").path("grafana").path("headers").path("Authorization").asText())
            .isEqualTo("Bearer grafana-token")
        assertThat(config.path("mcpServers").has("kubernetes")).isFalse()
    }

    @Test
    fun `runs somewhere neutral, so the report cannot pick up whatever repo started the service`() {
        val cli = fakeCli(
            """pwd > "$(dirname "$0")/cwd.txt"
            echo '{"is_error":false,"result":"ok","num_turns":1}'""",
        )

        CliInvestigator(properties(cli), mapper).investigate(alert)

        val cwd = File(directory.toFile(), "cwd.txt").readText().trim()
        assertThat(cwd).doesNotContain("Tern")
        assertThat(File(cwd).name).startsWith("tern-workflow")
    }

    @Test
    fun `refuses the tools that could act on the machine it is running on`() {
        val cli = fakeCli(
            """printf '%s\0' "$@" > "$(dirname "$0")/args.txt"
            echo '{"is_error":false,"result":"ok","num_turns":1}'""",
        )

        CliInvestigator(properties(cli), mapper).investigate(alert)

        assertThat(capturedArgs()).contains("--disallowed-tools", "Bash", "Write", "Edit", "WebFetch")
    }

    @Test
    fun `a failing, silent or non-json cli is a failed run rather than an empty report`() {
        assertThatThrownBy { CliInvestigator(properties(fakeCli("exit 3")), mapper).investigate(alert) }
            .hasMessageContaining("exited 3")

        assertThatThrownBy { CliInvestigator(properties(fakeCli("echo not json")), mapper).investigate(alert) }
            .hasMessageContaining("did not answer JSON")

        assertThatThrownBy {
            CliInvestigator(properties(fakeCli("""echo '{"is_error":false,"result":"  "}'""")), mapper)
                .investigate(alert)
        }.hasMessageContaining("without writing a report")

        assertThatThrownBy {
            CliInvestigator(properties(fakeCli("""echo '{"is_error":true,"result":"session expired"}'""")), mapper)
                .investigate(alert)
        }.hasMessageContaining("session expired")
    }

    @Test
    fun `a cli that never returns is killed rather than holding the run open`() {
        val cli = fakeCli("sleep 30")

        assertThatThrownBy {
            CliInvestigator(properties(cli, Duration.ofSeconds(1)), mapper).investigate(alert)
        }.hasMessageContaining("ran past PT1S")
    }

    @Test
    fun `will not run with no mcp server to look through`() {
        val properties = WorkflowProperties(enabled = true, investigator = InvestigatorKind.CLI, mcp = emptyMap())

        assertThatThrownBy { CliInvestigator(properties, mapper).investigate(alert) }
            .hasMessageContaining("No MCP server is configured")
    }
}
