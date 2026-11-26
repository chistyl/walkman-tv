package com.walkman.tv.source.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionCatalogCoreTest {

    @Test
    fun parsesArrayAndResolvesRelativeScriptUrls() {
        val parsed = SubscriptionCatalogCore.parse(
            """[{"id":"alpha","name":"Alpha","url":"scripts/a.js"}]""",
            "https://example.com/catalog/sources.json",
        )
        assertEquals(
            listOf(SubscriptionCatalogEntry("alpha", "Alpha", "https://example.com/catalog/scripts/a.js")),
            parsed,
        )
    }

    @Test
    fun parsesObjectWrapperAndPreservesAbsoluteUrls() {
        val parsed = SubscriptionCatalogCore.parse(
            """{"sources":[{"id":"beta","name":"Beta","url":"https://cdn.example.net/b.js"}]}""",
            "https://example.com/sources.json",
        )
        assertEquals("https://cdn.example.net/b.js", parsed?.single()?.url)
    }

    @Test
    fun rejectsMalformedOrEmptyCatalogAsAWhole() {
        assertNull(SubscriptionCatalogCore.parse("", "https://example.com/sources.json"))
        assertNull(SubscriptionCatalogCore.parse("""{"sources":[]}""", "https://example.com/sources.json"))
        assertNull(
            SubscriptionCatalogCore.parse(
                """[{"id":"ok","name":"OK","url":"a.js"},{"id":"bad","name":"","url":"b.js"}]""",
                "https://example.com/sources.json",
            ),
        )
    }

    @Test
    fun rejectsDuplicateIdsAndUnsafeSchemes() {
        assertNull(
            SubscriptionCatalogCore.parse(
                """[{"id":"a","name":"A","url":"a.js"},{"id":"a","name":"Again","url":"b.js"}]""",
                "https://example.com/sources.json",
            ),
        )
        assertNull(
            SubscriptionCatalogCore.parse(
                """[{"id":"a","name":"A","url":"file:///tmp/a.js"}]""",
                "https://example.com/sources.json",
            ),
        )
        assertNull(
            SubscriptionCatalogCore.parse(
                """[{"id":"a","name":"A","url":"a.js"}]""",
                "file:///tmp/sources.json",
            ),
        )
    }

    @Test
    fun stableSubscriptionIdentityRoundTrips() {
        assertEquals("user_api_subscription_remote-1", SubscriptionCatalogCore.localId("remote-1"))
        assertEquals("remote-1", SubscriptionCatalogCore.remoteId("user_api_subscription_remote-1"))
        assertNull(SubscriptionCatalogCore.remoteId("manual-script"))
        assertNull(SubscriptionCatalogCore.remoteId("user_api_subscription_"))
    }

    @Test
    fun contentComparisonIgnoresUrlButDetectsNameOrScriptChanges() {
        val local = listOf(LocalSubscriptionScript("a", "Alpha", "script body"))
        val sameBody = listOf(RemoteSubscriptionScript("a", "Alpha", "https://new.example/a.js", "script body"))
        assertFalse(SubscriptionCatalogCore.contentChanged(local, sameBody))

        val changedBody = listOf(RemoteSubscriptionScript("a", "Alpha", "https://new.example/a.js", "new body"))
        assertTrue(SubscriptionCatalogCore.contentChanged(local, changedBody))

        val changedName = listOf(RemoteSubscriptionScript("a", "Renamed", "https://new.example/a.js", "script body"))
        assertTrue(SubscriptionCatalogCore.contentChanged(local, changedName))
    }

    @Test
    fun updatePlanAddsUpdatesAndRetainsMissingEntriesWithoutDeletion() {
        val local = listOf(
            LocalSubscriptionRecord("user_api_subscription_a", "a", "Alpha", "old body", true),
            LocalSubscriptionRecord("user_api_subscription_b", "b", "Beta", "same body", false),
            LocalSubscriptionRecord("user_api_subscription_c", "c", "Gamma", "keep body", true),
        )
        val remote = listOf(
            RemoteSubscriptionScript("a", "Alpha 2", "https://example.com/a.js", "new body"),
            RemoteSubscriptionScript("b", "Beta", "https://example.com/b.js", "same body"),
            RemoteSubscriptionScript("d", "Delta", "https://example.com/d.js", "new script"),
        )

        val plan = SubscriptionCatalogCore.buildUpdatePlan(local, remote)
        assertEquals(listOf("d"), plan?.additions?.map { it.id })
        assertEquals(listOf("user_api_subscription_a"), plan?.updates?.map { it.localId })
        assertEquals(listOf("user_api_subscription_b"), plan?.unchangedLocalIds)
        assertEquals(listOf("user_api_subscription_c"), plan?.retainedMissingLocalIds)
        assertEquals(false, local.first { it.remoteId == "b" }.enabled)
    }

    @Test
    fun updatePlanRejectsAmbiguousOrManualLocalIdentity() {
        val duplicateLocal = listOf(
            LocalSubscriptionRecord("user_api_subscription_a", "a", "A", "one", true),
            LocalSubscriptionRecord("user_api_subscription_a", "a", "A", "two", true),
        )
        assertNull(SubscriptionCatalogCore.buildUpdatePlan(duplicateLocal, emptyList()))

        val manualScript = listOf(
            LocalSubscriptionRecord("manual-script", "a", "Manual", "body", true),
        )
        assertNull(SubscriptionCatalogCore.buildUpdatePlan(manualScript, emptyList()))

        val duplicateRemote = listOf(
            RemoteSubscriptionScript("a", "A", "https://example.com/a.js", "one"),
            RemoteSubscriptionScript("a", "A2", "https://example.com/a2.js", "two"),
        )
        assertNull(SubscriptionCatalogCore.buildUpdatePlan(emptyList(), duplicateRemote))

        val unsafeUrl = listOf(
            RemoteSubscriptionScript("unsafe", "Unsafe", "file:///tmp/source.js", "body"),
        )
        assertNull(SubscriptionCatalogCore.buildUpdatePlan(emptyList(), unsafeUrl))
    }

}
