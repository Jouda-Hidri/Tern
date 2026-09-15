package tern.workflow

import org.slf4j.LoggerFactory
import tern.tracing.RequestId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class InvestigationRunner(
    private val investigator: Investigator,
    private val store: RunStore,
    properties: WorkflowProperties,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(InvestigationRunner::class.java)
    private val executor = ThreadPoolExecutor(
        properties.concurrency,
        properties.concurrency,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(properties.queueDepth),
    ) { runnable -> Thread(runnable, "workflow-investigator").apply { isDaemon = true } }

    fun submit(alert: AlertContext): InvestigationRun {
        val requestId = RequestId.current()
        val run = InvestigationRun(
            id = UUID.randomUUID().toString().take(8),
            requestId = requestId,
            alert = alert,
            state = RunState.QUEUED,
            queuedAt = Instant.now(),
        )
        store.put(run)
        return try {
            executor.execute { investigate(run.id, requestId) }
            logger.info("Workflow - queued run ${run.id} for ${alert.title}")
            run
        } catch (e: RejectedExecutionException) {
            logger.warn("Workflow - rejected run ${run.id}, ${executor.queue.size} already queued")
            val rejected = run.copy(
                state = RunState.REJECTED,
                finishedAt = Instant.now(),
                error = "Investigation queue is full",
            )
            store.put(rejected)
            rejected
        }
    }

    private fun investigate(id: String, requestId: String?) = RequestId.withRequestId(requestId) {
        val startedAt = Instant.now()
        store.update(id) { it.copy(state = RunState.RUNNING, startedAt = startedAt) }
        val run = store.get(id) ?: return@withRequestId
        try {
            logger.info("Workflow - investigating ${run.alert.title}")
            val investigation = investigator.investigate(run.alert)
            store.update(id) {
                it.copy(
                    state = RunState.SUCCEEDED,
                    finishedAt = Instant.now(),
                    report = investigation.report,
                    toolCalls = investigation.toolCalls,
                    turns = investigation.turns,
                    inputTokens = investigation.inputTokens,
                    outputTokens = investigation.outputTokens,
                )
            }
            logger.info("Workflow - run $id produced a report after ${investigation.turns} turns")
            store.get(id)?.report?.let { logger.info("Workflow - report for run $id\n$it") }
        } catch (e: Exception) {
            logger.error("Workflow - run $id failed", e)
            store.update(id) {
                it.copy(
                    state = RunState.FAILED,
                    finishedAt = Instant.now(),
                    error = "${e::class.simpleName}: ${e.message}",
                )
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
