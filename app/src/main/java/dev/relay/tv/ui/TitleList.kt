package dev.relay.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.relay.tv.data.AppLog
import dev.relay.tv.data.TitleListCache
import dev.relay.tv.data.TitleQuery
import dev.relay.tv.data.TitleSource
import dev.relay.tv.data.buildTitleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The list for a Movies or Series category. It is built off the screen thread, and the last few
 * are kept in [cache], so coming back to a category shows it at once. Null while the first build
 * for a new category is still running. When the library or language guesses change, the previous
 * list stays on screen until the new one is ready.
 */
@Composable
fun <T> rememberTitleList(
    label: String,
    query: TitleQuery,
    generation: Long,
    cache: TitleListCache<T>,
    source: TitleSource<T>,
): List<T>? {
    var built by remember { mutableStateOf<Pair<TitleQuery, List<T>>?>(null) }
    LaunchedEffect(query, generation) {
        if (cache.get(generation, query) == null) {
            val t0 = System.currentTimeMillis()
            val items = withContext(Dispatchers.Default) { buildTitleList(source, query) }
            cache.put(generation, query, items)
            built = query to items
            val ms = System.currentTimeMillis() - t0
            if (ms > 150) AppLog.w("Library", "$label list took $ms ms off the screen thread (${items.size} titles)")
        }
    }
    return cache.get(generation, query) ?: built?.takeIf { it.first == query }?.second
}
