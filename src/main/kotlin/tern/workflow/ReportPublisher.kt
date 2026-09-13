package tern.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

class ReportPublisher(
    private val properties: SinkProperties,
    private val mapper: ObjectMapper,
    webClientBuilder: WebClient.Builder,
) {
    private val logger = LoggerFactory.getLogger(ReportPublisher::class.java)
    private val client = if (properties.url.isBlank()) null else webClientBuilder.build()

    fun publish(run: InvestigationRun) {
        logger.info("Workflow - report for run ${run.id}\n${run.report}")
        val client = client ?: return
        val body = mapper.createObjectNode().apply {
            put("run_id", run.id)
            put("source", run.alert.source)
            put("alert_id", run.alert.fingerprint)
            put("title", run.alert.title)
            put("severity", run.alert.severity)
            put("status", run.alert.status)
            put("message", run.report)
            put("tool_calls", run.toolCalls.size)
            put("duration_ms", run.durationMs ?: 0L)
        }
        runCatching {
            client.post()
                .uri(properties.url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { headers ->
                    properties.token.takeIf { it.isNotBlank() }
                        ?.let { headers.set(HttpHeaders.AUTHORIZATION, "Bearer $it") }
                }
                .bodyValue(mapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity()
                .block(properties.timeout)
        }.onFailure {
            logger.warn("Workflow - could not publish run ${run.id} to the sink: ${it.message}")
        }.onSuccess {
            logger.info("Workflow - published run ${run.id} to the sink")
        }
    }
}
