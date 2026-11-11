package dev.relay.tv.data

/** A list build asks whether it is still wanted this often (items between asks). */
internal const val CHECK_EVERY = 2_000

/**
 * Like filter, but calls [check] every [CHECK_EVERY] items. The screen passes a check that throws
 * when the build was cancelled (the category changed again, another letter was typed), so a long
 * list on a slow processor stops instead of finishing work nobody will see.
 */
internal inline fun <T> Iterable<T>.filterChecked(check: () -> Unit, predicate: (T) -> Boolean): List<T> {
    val out = ArrayList<T>()
    var n = 0
    for (x in this) {
        if (++n % CHECK_EVERY == 0) check()
        if (predicate(x)) out.add(x)
    }
    return out
}

internal inline fun <T, R> Iterable<T>.mapCheckedTo(destination: MutableCollection<R>, check: () -> Unit, transform: (T) -> R): MutableCollection<R> {
    var n = 0
    for (x in this) {
        if (++n % CHECK_EVERY == 0) check()
        destination.add(transform(x))
    }
    return destination
}
