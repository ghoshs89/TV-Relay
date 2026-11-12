package dev.relay.tv.data

/** Which kind of TMDB credential a pasted string looks like. */
enum class KeyKind { V3, V4, Unknown }

/** What a service said about a key. */
enum class KeyCheck { Ok, Rejected, Unreachable }

/** What to do with one key that arrived from the phone. */
sealed interface KeyAction {
    data object Leave : KeyAction
    data object Remove : KeyAction
    data class Check(val key: String) : KeyAction
}

/** The words shown to the person, and the value to store (null means leave the saved key as it is). */
data class KeyOutcome(val message: String, val newValue: String?)

/** What the phone's keys form sent. A blank field means "leave it"; the Remove boxes clear a key. */
data class KeysForm(
    val tmdb: String = "",
    val ratings: String = "",
    val removeTmdb: Boolean = false,
    val removeRatings: Boolean = false,
)

/** [lines] are shown on the phone and the TV; [changed] is true when a key was saved or removed. */
data class KeysResult(val lines: List<String>, val changed: Boolean)

/**
 * Forgiving handling of pasted API keys. People copy the wrong box, copy a whole line of code, or
 * paste a key broken over several lines, and typing on a phone adds quotes and spaces. Everything
 * here is plain text work so it can be tested without a device.
 */
object KeyText {

    private val QUOTES = setOf('"', '\'', '`', '“', '”', '‘', '’', '«', '»', '<', '>')
    private val NAMED = Regex("(?i)api[_-]?key\\s*[=:]\\s*([^&\\s]+)")
    private val BEARER = Regex("(?i)^bearer\\s+")
    private val V3 = Regex("^[0-9a-fA-F]{32}$")
    private val V4 = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    /**
     * The key itself, without what people paste around it: spaces and line breaks, quotes, a leading
     * `api_key=` or `apikey=` (also inside a pasted web address), and a leading `Bearer`.
     */
    fun clean(raw: String): String {
        val text = raw.filterNot { it in QUOTES }.trim()
        if (text.isEmpty()) return ""
        NAMED.find(text)?.let { return it.groupValues[1].filterNot(Char::isWhitespace) }
        val body = text.replace(BEARER, "")
        val tokens = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            // "API Key: abc..." keeps the one piece that looks like a key; a key wrapped over
            // several lines has no such piece and is joined back together.
            val whole = tokens.joinToString("")
            if (kindOfClean(whole) != KeyKind.Unknown) return whole
            val pieces = tokens.filter { kindOfClean(it) != KeyKind.Unknown }
            if (pieces.size == 1) return pieces[0]
            return whole
        }
        return tokens.firstOrNull().orEmpty()
    }

    /** TMDB v3 API key (32 hex characters), v4 read access token (a JWT), or neither. */
    fun kind(raw: String): KeyKind = kindOfClean(clean(raw))

    private fun kindOfClean(s: String): KeyKind = when {
        V4.matches(s) -> KeyKind.V4
        V3.matches(s) -> KeyKind.V3
        else -> KeyKind.Unknown
    }

    /** TMDB's answer to a request that needs a key; [code] is null when there was no answer at all. */
    fun tmdbCheck(code: Int?): KeyCheck = when {
        code == null -> KeyCheck.Unreachable
        code in 200..299 -> KeyCheck.Ok
        code == 401 || code == 403 -> KeyCheck.Rejected
        else -> KeyCheck.Unreachable
    }

    private val REFUSED = Regex("(?i)invalid|api\\s*key|apikey|unauthori[sz]ed|forbidden|HTTP 40[13]\\b")

    /** MDBList's answer to a request for a known title. A key that is over its daily limit is still a real key. */
    fun ratingsCheck(reply: MdbList.Reply?): KeyCheck = when (reply) {
        null -> KeyCheck.Unreachable
        is MdbList.Reply.Found, MdbList.Reply.LimitReached -> KeyCheck.Ok
        MdbList.Reply.NotFound -> KeyCheck.Unreachable
        is MdbList.Reply.Failed -> if (REFUSED.containsMatchIn(reply.reason)) KeyCheck.Rejected else KeyCheck.Unreachable
    }

    fun message(service: String, check: KeyCheck): String = when (check) {
        KeyCheck.Ok -> "$service key saved"
        KeyCheck.Rejected -> "$service key was not accepted (check that you copied all of it)"
        KeyCheck.Unreachable -> "Could not reach $service, try again"
    }

    fun removedMessage(service: String) = "$service key removed"

    /** A Remove box wins over anything typed; an empty field leaves the saved key alone. */
    fun action(raw: String, remove: Boolean): KeyAction {
        if (remove) return KeyAction.Remove
        val key = clean(raw)
        return if (key.isEmpty()) KeyAction.Leave else KeyAction.Check(key)
    }
}

/**
 * Turns what the phone sent into saved keys. Each key is checked with its service first; only a
 * key the service accepts is stored. [apply] is called once with the new values (null for a key that
 * stays as it is), so the caller can save both in one step on the right thread.
 */
object KeysIntake {

    suspend fun process(
        form: KeysForm,
        checkTmdb: suspend (String) -> KeyCheck,
        checkRatings: suspend (String) -> KeyCheck,
        apply: (tmdb: String?, ratings: String?) -> Unit,
    ): KeysResult {
        val tmdb = one("TMDB", KeyText.action(form.tmdb, form.removeTmdb), checkTmdb)
        val ratings = one("MDBList", KeyText.action(form.ratings, form.removeRatings), checkRatings)
        val lines = listOfNotNull(tmdb?.message, ratings?.message)
        if (tmdb?.newValue == null && ratings?.newValue == null) {
            return KeysResult(lines.ifEmpty { listOf("Nothing to save: both boxes were empty") }, changed = false)
        }
        apply(tmdb?.newValue, ratings?.newValue)
        return KeysResult(lines, changed = true)
    }

    private suspend fun one(service: String, action: KeyAction, check: suspend (String) -> KeyCheck): KeyOutcome? =
        when (action) {
            KeyAction.Leave -> null
            KeyAction.Remove -> KeyOutcome(KeyText.removedMessage(service), "")
            is KeyAction.Check -> {
                val result = check(action.key)
                KeyOutcome(KeyText.message(service, result), action.key.takeIf { result == KeyCheck.Ok })
            }
        }
}
