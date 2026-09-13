package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AlertParserTest {
    private val parser = AlertParser(ObjectMapper())

    @Test
    fun `reads an alertmanager group into one context per alert`() {
        val alerts = parser.parse(
            """
            {"version":"4","status":"firing","receiver":"tern-workflow","alerts":[
              {"status":"firing","fingerprint":"abc123",
               "labels":{"alertname":"TernArticErrors","severity":"critical","service":"artic"},
               "annotations":{"summary":"artic is answering 5xx","description":"one or more 5xx"},
               "startsAt":"2026-09-11T10:00:00Z","generatorURL":"http://prometheus:9090/graph"},
              {"status":"resolved","fingerprint":"def456",
               "labels":{"alertname":"TernTargetDown","severity":"critical"},
               "annotations":{},"startsAt":"2026-09-11T09:00:00Z"}]}
            """.trimIndent(),
        )

        assertThat(alerts).hasSize(2)
        assertThat(alerts[0].source).isEqualTo("alertmanager")
        assertThat(alerts[0].title).isEqualTo("TernArticErrors")
        assertThat(alerts[0].severity).isEqualTo("critical")
        assertThat(alerts[0].summary).isEqualTo("one or more 5xx")
        assertThat(alerts[0].labels).containsEntry("service", "artic")
        assertThat(alerts[0].links).containsEntry("generator", "http://prometheus:9090/graph")
        assertThat(alerts[1].status).isEqualTo("resolved")
    }

    @Test
    fun `reads an incident io webhook`() {
        val alerts = parser.parse(
            """
            {"event_type":"public_incident.incident_created_v2","created_at":"2026-09-11T10:00:00Z",
             "incident":{"id":"01ABC","reference":"INC-42","name":"Writes are failing",
              "summary":"artic answering 503","permalink":"https://app.incident.io/incidents/42",
              "severity":{"name":"Major"},"incident_status":{"name":"Investigating"},
              "created_at":"2026-09-11T09:59:00Z"}}
            """.trimIndent(),
        )

        assertThat(alerts).singleElement().satisfies({
            assertThat(it.source).isEqualTo("incident.io")
            assertThat(it.fingerprint).isEqualTo("01ABC")
            assertThat(it.title).isEqualTo("Writes are failing")
            assertThat(it.severity).isEqualTo("Major")
            assertThat(it.status).isEqualTo("Investigating")
            assertThat(it.labels).containsEntry("reference", "INC-42")
            assertThat(it.links).containsEntry("incident", "https://app.incident.io/incidents/42")
        })
    }

    @Test
    fun `falls back to a generic shape`() {
        val alerts = parser.parse("""{"title":"Disk full","severity":"warning","labels":{"host":"a"}}""")

        assertThat(alerts).singleElement().satisfies({
            assertThat(it.source).isEqualTo("generic")
            assertThat(it.title).isEqualTo("Disk full")
            assertThat(it.labels).containsEntry("host", "a")
        })
    }

    @Test
    fun `renders a prompt-shaped context`() {
        val rendered = parser.parse(
            """{"title":"Disk full","severity":"warning","labels":{"host":"a"},"status":"firing"}""",
        ).single().render()

        assertThat(rendered).contains("title: Disk full", "severity: warning", "  host: a")
    }
}
