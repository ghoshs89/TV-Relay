package dev.relay.tv.sync

/** The file as stored, with the tag that says which version of it this is. */
class Remote(val bytes: ByteArray, val version: String)

/** Why a read or write did not work, in terms the screen can explain. */
enum class Problem {
    /** No network, a timeout, or the host could not be reached. */
    Offline,

    /** The token was refused or does not have the permission needed. */
    Refused,

    /** The repository (or the account) can't be found with this token. */
    NotFound,

    /** The file is larger than the store allows. */
    TooLarge,

    /** Anything else. */
    Other,
}

class TransportException(val problem: Problem, message: String) : Exception(message)

sealed interface WriteResult {
    /** Written; [version] is the new tag. */
    class Saved(val version: String) : WriteResult

    /** Somebody else wrote since the file was read: read again and redo the merge. */
    data object Changed : WriteResult
}

/**
 * Where the one sync file lives. Everything above this interface is independent of the place, so
 * another store (a WebDAV address, say) only needs these two calls. Calls block: they run on a
 * background thread. Failures throw [TransportException].
 */
interface SyncTransport {
    /** The file, or null when nothing has been written yet. */
    fun read(): Remote?

    /**
     * Writes [bytes]. [ifVersion] is the tag of the file that was read (null: expect no file); if the
     * store holds anything else the result is [WriteResult.Changed] and nothing is written.
     */
    fun write(bytes: ByteArray, ifVersion: String?): WriteResult
}
