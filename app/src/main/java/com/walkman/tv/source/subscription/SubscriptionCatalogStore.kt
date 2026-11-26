package com.walkman.tv.source.subscription

import android.content.Context
import com.walkman.tv.data.store.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** Persists only the user's explicit catalog URL; it never mutates imported music scripts. */
@Serializable
data class SubscriptionCatalogConfig(
    val catalogUrl: String = "",
)

class SubscriptionCatalogStore(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "subscription_catalog.json"),
        SubscriptionCatalogConfig.serializer(),
        SubscriptionCatalogConfig(),
    )

    suspend fun load(): SubscriptionCatalogConfig = withContext(Dispatchers.IO) {
        store.load()
    }

    suspend fun saveCatalogUrl(url: String) = withContext(Dispatchers.IO) {
        store.save(SubscriptionCatalogConfig(catalogUrl = url.trim()))
    }
}
