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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What a Movies or Series grid shows: the list, and whether it is the previous category's still being replaced. */
class TitleListState<T>(val items: List<T>?, val stale: Boolean)

/** Remembers the last list shown outside composition state, so it can stay up while the next one is built. */
private class LastShown<T> { var items: List<T>? = null }

/**
 * The list for a Movies or Series category. It is built off the screen thread, and the last few
 * are kept in [cache], so coming back to a category shows it at once. While a new category's first
 * build runs, the previous list stays on screen and is marked [TitleListState.stale] so the grid can
 * dim it. Null items only before anything has been shown.
 */
@Composable
fun <T> rememberTitleList(
    label: String,
    query: TitleQuery,
    generation: Long,
    cache: TitleListCache<T>,
    source: TitleSource<T>,
): TitleListState<T> {
    var built by remember { mutableStateOf<Pair<TitleQuery, List<T>>?>(null) }
    val last = remember { LastShown<T>() }
    LaunchedEffect(query, generation) {
        if (cache.get(generation, query) == null) {
            val t0 = System.currentTimeMillis()
            val items = withContext(Dispatchers.Default) { buildTitleList(source, query) { ensureActive() } }
            cache.put(generation, query, items)
            built = query to items
            val ms = System.currentTimeMillis() - t0
            AppLog.d("Library", "$label list: $ms ms, ${items.size} titles")
            if (ms > 150) AppLog.w("Library", "$label list took $ms ms off the screen thread (${items.size} titles)")
        }
    }
    val exact = cache.get(generation, query) ?: built?.takeIf { it.first == query }?.second
    if (exact != null) {
        last.items = exact
        return TitleListState(exact, stale = false)
    }
    return TitleListState(last.items, stale = last.items != null)
}
