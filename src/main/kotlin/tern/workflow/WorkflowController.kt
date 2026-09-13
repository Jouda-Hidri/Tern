package tern.workflow

import com.fasterxml.jackson.core.JacksonException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tern.artic.ApiError
import tern.tracing.RequestId

@RestController
@RequestMapping("/workflow")
@ConditionalOnProperty(prefix = "tern.workflow", name = ["enabled"], havingValue = "true")
class WorkflowController(
    private val parser: AlertParser,
    private val runner: InvestigationRunner,
    private val store: RunStore,
    private val properties: WorkflowProperties,
) {
    private val logger = LoggerFactory.getLogger(WorkflowController::class.java)

    @PostMapping("/alerts", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> {
        val signature = headers.getFirst(properties.signatureHeader)
        if (!WebhookSignature.verify(properties.webhookSecret, body, signature)) {
            logger.warn("Workflow - rejected a webhook with a bad or missing signature")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError(401, "Signature does not match", RequestId.current()))
        }

        val alerts = try {
            parser.parse(body)
        } catch (e: JacksonException) {
            logger.warn("Workflow - rejected a malformed webhook: ${e.message}")
            return ResponseEntity.badRequest()
                .body(ApiError(400, "Request body is malformed or missing required fields", RequestId.current()))
        }

        val accepted = alerts.filter { it.status != "resolved" }
        if (accepted.isEmpty()) {
            logger.info("Workflow - nothing to investigate in a payload of ${alerts.size} alert(s)")
            return ResponseEntity.accepted().body(AcceptedRuns(emptyList()))
        }

        val runs = accepted.map { runner.submit(it) }
        return ResponseEntity.accepted().body(AcceptedRuns(runs.map { RunSummary.from(it) }))
    }

    @GetMapping("/runs")
    fun list(): List<RunSummary> = store.recent().map(RunSummary::from)

    @GetMapping("/runs/{id}")
    fun get(@PathVariable id: String): ResponseEntity<Any> {
        val run = store.get(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError(404, "No run with id $id", RequestId.current()))
        return ResponseEntity.ok(RunDetail.from(run))
    }

    @GetMapping("/runs/{id}/report", produces = ["text/markdown"])
    fun report(@PathVariable id: String): ResponseEntity<String> {
        val run = store.get(id) ?: return ResponseEntity.notFound().build()
        val report = run.report ?: return ResponseEntity.status(HttpStatus.ACCEPTED).body("Run ${run.id} is ${run.state}")
        return ResponseEntity.ok(report)
    }
}

data class AcceptedRuns(val runs: List<RunSummary>)

data class RunSummary(
    val id: String,
    val state: RunState,
    val title: String,
    val severity: String,
    val source: String,
    val queuedAt: String,
    val durationMs: Long?,
) {
    companion object {
        fun from(run: InvestigationRun) = RunSummary(
            id = run.id,
            state = run.state,
            title = run.alert.title,
            severity = run.alert.severity,
            source = run.alert.source,
            queuedAt = run.queuedAt.toString(),
            durationMs = run.durationMs,
        )
    }
}

data class RunDetail(
    val id: String,
    val state: RunState,
    val requestId: String?,
    val alert: AlertContext,
    val queuedAt: String,
    val durationMs: Long?,
    val turns: Int,
    val toolCalls: List<String>,
    val inputTokens: Long,
    val outputTokens: Long,
    val report: String?,
    val error: String?,
) {
    companion object {
        fun from(run: InvestigationRun) = RunDetail(
            id = run.id,
            state = run.state,
            requestId = run.requestId,
            alert = run.alert,
            queuedAt = run.queuedAt.toString(),
            durationMs = run.durationMs,
            turns = run.turns,
            toolCalls = run.toolCalls,
            inputTokens = run.inputTokens,
            outputTokens = run.outputTokens,
            report = run.report,
            error = run.error,
        )
    }
}
