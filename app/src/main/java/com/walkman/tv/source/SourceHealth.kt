package com.walkman.tv.source

import kotlinx.serialization.Serializable

/**
 * Platform-neutral source-health model ported from LX Music Enhanced.
 *
 * This class deliberately contains no Android or storage dependencies so ranking and cooldown
 * behavior can be unit-tested independently from playback and TV UI.
 */
@Serializable
data class SourceHealth(
    val success: Int = 0,
    val failure: Int = 0,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val cooldownUntilMs: Long = 0,
    val updatedAtMs: Long = 0,
) {
    val sampleCount: Int get() = success + failure
}

object SourceHealthPolicy {
    const val FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
    const val MAX_SAMPLES = 40
    private const val EWMA_OLD_WEIGHT = 0.7
    private const val EWMA_NEW_WEIGHT = 0.3
    private const val COOLDOWN_FAILURE_THRESHOLD = 2

    fun recordSuccess(
        previous: SourceHealth?,
        latencyMs: Long,
        nowMs: Long,
    ): SourceHealth {
        val current = previous ?: SourceHealth()
        val nextLatency = current.latencyMs?.let {
            (it * EWMA_OLD_WEIGHT + latencyMs.coerceAtLeast(0) * EWMA_NEW_WEIGHT).toLong()
        } ?: latencyMs.coerceAtLeast(0)
        return compact(current.copy(
            success = current.success + 1,
            latencyMs = nextLatency,
            consecutiveFailures = 0,
            cooldownUntilMs = 0,
            updatedAtMs = nowMs,
        ))
    }

    fun recordFailure(previous: SourceHealth?, nowMs: Long): SourceHealth {
        val current = previous ?: SourceHealth()
        val failures = current.consecutiveFailures + 1
        return compact(current.copy(
            failure = current.failure + 1,
            consecutiveFailures = failures,
            cooldownUntilMs = if (failures >= COOLDOWN_FAILURE_THRESHOLD) {
                nowMs + FAILURE_COOLDOWN_MS
            } else {
                0
            },
            updatedAtMs = nowMs,
        ))
    }

    /**
     * Ranks only the IDs supplied by the caller. The caller remains responsible for eligibility
     * filtering (enabled state, platform/action support). Unknown sources keep a neutral prior.
     * Original order is the deterministic final tie-breaker.
     */
    fun rank(
        ids: List<String>,
        healthById: Map<String, SourceHealth>,
        nowMs: Long,
    ): List<String> {
        val originalOrder = ids.withIndex().associate { it.value to it.index }
        return ids.sortedWith { leftId, rightId ->
            val left = healthById[leftId]
            val right = healthById[rightId]
            val leftCooling = (left?.cooldownUntilMs ?: 0L) > nowMs
            val rightCooling = (right?.cooldownUntilMs ?: 0L) > nowMs
            when {
                leftCooling != rightCooling -> if (leftCooling) 1 else -1
                else -> {
                    val reliabilityDelta = reliability(right) - reliability(left)
                    when {
                        kotlin.math.abs(reliabilityDelta) > 0.0001 ->
                            if (reliabilityDelta > 0) 1 else -1
                        else -> {
                            val latencyDelta = (left?.latencyMs ?: Long.MAX_VALUE)
                                .compareTo(right?.latencyMs ?: Long.MAX_VALUE)
                            if (latencyDelta != 0) latencyDelta
                            else (originalOrder[leftId] ?: 0).compareTo(originalOrder[rightId] ?: 0)
                        }
                    }
                }
            }
        }
    }

    private fun reliability(item: SourceHealth?): Double = item?.let {
        (it.success + 1.0) / (it.success + it.failure + 2.0)
    } ?: 0.5

    private fun compact(item: SourceHealth): SourceHealth {
        if (item.sampleCount <= MAX_SAMPLES) return item
        return item.copy(
            success = (item.success + 1) / 2,
            failure = (item.failure + 1) / 2,
        )
    }
}
