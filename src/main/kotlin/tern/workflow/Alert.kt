package tern.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class AlertContext(
    val source: String,
    val fingerprint: String,
    val title: String,
    val summary: String,
    val severity: String,
    val status: String,
    val startedAt: String,
    val labels: Map<String, String>,
    val links: Map<String, String>,
) {
    fun render(): String = buildString {
        appendLine("source: $source")
        appendLine("status: $status")
        appendLine("severity: $severity")
        appendLine("started_at: $startedAt")
        appendLine("title: $title")
        if (summary.isNotBlank()) appendLine("summary: $summary")
        if (labels.isNotEmpty()) {
            appendLine("labels:")
            labels.toSortedMap().forEach { (k, v) -> appendLine("  $k: $v") }
        }
        if (links.isNotEmpty()) {
            appendLine("links:")
            links.toSortedMap().forEach { (k, v) -> appendLine("  $k: $v") }
        }
    }.trim()
}

class AlertParser(private val mapper: ObjectMapper) {

    fun parse(body: String): List<AlertContext> {
        val root = mapper.readTree(body)
        return when {
            root.path("alerts").isArray -> root.path("alerts").map(::fromAlertmanager)
            else -> listOf(fromGeneric(root))
        }
    }

    private fun fromAlertmanager(alert: JsonNode): AlertContext {
        val labels = alert.path("labels").toStringMap()
        val annotations = alert.path("annotations").toStringMap()
        return AlertContext(
            source = "alertmanager",
            fingerprint = alert.path("fingerprint").asText(""),
            title = labels["alertname"] ?: annotations["summary"] ?: "unnamed alert",
            summary = annotations["description"] ?: annotations["summary"].orEmpty(),
            severity = labels["severity"] ?: "unknown",
            status = alert.path("status").asText("firing"),
            startedAt = alert.path("startsAt").asText(""),
            labels = labels,
            links = buildMap {
                alert.path("generatorURL").asText("").ifBlank { null }?.let { put("generator", it) }
                annotations["runbook_url"]?.let { put("runbook", it) }
            },
        )
    }

    private fun fromGeneric(root: JsonNode): AlertContext = AlertContext(
        source = "generic",
        fingerprint = root.path("id").asText(""),
        title = root.path("title").asText("unnamed alert"),
        summary = root.path("description").asText(""),
        severity = root.path("severity").asText("unknown"),
        status = root.path("status").asText("firing"),
        startedAt = root.path("startsAt").asText(""),
        labels = root.path("labels").toStringMap(),
        links = root.path("links").toStringMap(),
    )

    private fun JsonNode.toStringMap(): Map<String, String> =
        if (!isObject) emptyMap()
        else properties().associate { (key, value) -> key to value.asText("") }
}
