package com.walkman.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthPolicyTest {

    @Test
    fun unknownSourcesKeepOriginalOrder() {
        assertEquals(
            listOf("first", "second", "third"),
            SourceHealthPolicy.rank(listOf("first", "second", "third"), emptyMap(), 100L),
        )
    }

    @Test
    fun healthierSourceRanksBeforeLessReliableSource() {
        val health = mapOf(
            "unstable" to SourceHealth(success = 1, failure = 5, latencyMs = 80),
            "stable" to SourceHealth(success = 8, failure = 1, latencyMs = 250),
        )
        assertEquals(
            listOf("stable", "unstable"),
            SourceHealthPolicy.rank(listOf("unstable", "stable"), health, 100L),
        )
    }

    @Test
    fun coolingSourceAlwaysRanksAfterAvailableSource() {
        val health = mapOf(
            "cooling" to SourceHealth(success = 20, failure = 0, cooldownUntilMs = 500L),
            "available" to SourceHealth(success = 1, failure = 2),
        )
        assertEquals(
            listOf("available", "cooling"),
            SourceHealthPolicy.rank(listOf("cooling", "available"), health, 200L),
        )
    }

    @Test
    fun latencyBreaksReliabilityTies() {
        val health = mapOf(
            "slow" to SourceHealth(success = 4, failure = 1, latencyMs = 900),
            "fast" to SourceHealth(success = 4, failure = 1, latencyMs = 120),
        )
        assertEquals(
            listOf("fast", "slow"),
            SourceHealthPolicy.rank(listOf("slow", "fast"), health, 100L),
        )
    }

    @Test
    fun twoConsecutiveFailuresEnterCooldownAndSuccessClearsIt() {
        val firstFailure = SourceHealthPolicy.recordFailure(null, 1_000L)
        assertEquals(0L, firstFailure.cooldownUntilMs)

        val secondFailure = SourceHealthPolicy.recordFailure(firstFailure, 2_000L)
        assertEquals(2_000L + SourceHealthPolicy.FAILURE_COOLDOWN_MS, secondFailure.cooldownUntilMs)

        val recovered = SourceHealthPolicy.recordSuccess(secondFailure, 300L, 3_000L)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(0L, recovered.cooldownUntilMs)
        assertEquals(1, recovered.success)
    }

    @Test
    fun sampleCountsAreCompacted() {
        var value = SourceHealth()
        repeat(50) { value = SourceHealthPolicy.recordSuccess(value, 100L, it.toLong()) }
        assertTrue(value.sampleCount <= SourceHealthPolicy.MAX_SAMPLES)
    }
}
