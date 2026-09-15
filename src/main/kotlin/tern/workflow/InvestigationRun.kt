package tern.workflow

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

enum class RunState { QUEUED, RUNNING, SUCCEEDED, FAILED, REJECTED }

data class InvestigationRun(
    val id: String,
    val requestId: String?,
    val alert: AlertContext,
    val state: RunState,
    val queuedAt: Instant,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val report: String? = null,
    val error: String? = null,
    val toolCalls: List<String> = emptyList(),
    val turns: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    val durationMs: Long?
        get() = if (startedAt != null && finishedAt != null) {
            Duration.between(startedAt, finishedAt).toMillis()
        } else {
            null
        }
}

class RunStore(private val capacity: Int) {
    private val runs = Collections.synchronizedMap(
        object : LinkedHashMap<String, InvestigationRun>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InvestigationRun>) = size > capacity
        },
    )

    fun put(run: InvestigationRun) {
        runs[run.id] = run
    }

    fun get(id: String): InvestigationRun? = runs[id]

    fun recent(): List<InvestigationRun> = synchronized(runs) { runs.values.toList() }.reversed()

    fun update(id: String, change: (InvestigationRun) -> InvestigationRun) {
        synchronized(runs) { runs[id]?.let { runs[id] = change(it) } }
    }
}
