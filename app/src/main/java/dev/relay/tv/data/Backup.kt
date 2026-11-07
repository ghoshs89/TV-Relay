package dev.relay.tv.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Backup file, format 2. Never contains the login, any PIN, API keys or diagnostics settings.
 * Restoring replaces everything of these kinds on the device.
 */
@Serializable
data class BackupFile(
    val app: String = APP,
    val schema: Int = SCHEMA,
    val appVersion: String = "",
    val created: Long = 0L,
    val profiles: List<Profile> = emptyList(),
    /** Profiles that had a PIN when the backup was made; PINs themselves aren't saved. */
    val lockedProfiles: List<String> = emptyList(),
    val favourites: Map<String, List<FavItem>> = emptyMap(),
    val history: Map<String, List<WatchEntry>> = emptyMap(),
    val settings: Settings = Settings(),
    val channels: ChannelCustom = ChannelCustom(),
    val pinnedCategories: List<String> = emptyList(),
    val liveSort: Map<String, Int> = emptyMap(),
    val seriesTracks: Map<Int, TrackPref> = emptyMap(),
    val reminders: List<Reminder> = emptyList(),
) {
    companion object {
        const val APP = "relay"
        const val SCHEMA = 2
    }
}

class BackupException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** What a restore changed, for the message shown afterwards. */
data class RestoreResult(
    val profiles: Int,
    val favourites: Int,
    val history: Int,
    val groups: Int,
    /** Names of profiles that had a PIN in the backup but have none on this device now. */
    val needPin: List<String>,
) {
    fun summary(): String {
        val parts = listOf(
            "$profiles profile${if (profiles == 1) "" else "s"}",
            "$favourites favourite${if (favourites == 1) "" else "s"}",
            "$history watched item${if (history == 1) "" else "s"}",
            "$groups channel group${if (groups == 1) "" else "s"}",
        )
        val pins = if (needPin.isEmpty()) "" else " Set a PIN again for: ${needPin.joinToString(", ")} (Settings → Profiles)."
        return "Restored ${parts.joinToString(", ")} and your settings.$pins"
    }
}

object Backups {
    private val pretty = Json(from = Jsons.json) { prettyPrint = true }

    /** Removes everything secret: PINs, API keys, and diagnostics state. */
    fun sanitize(b: BackupFile): BackupFile = b.copy(
        app = BackupFile.APP,
        schema = BackupFile.SCHEMA,
        profiles = b.profiles.map { it.copy(pin = null) },
        settings = stripSettings(b.settings),
    )

    fun stripSettings(s: Settings): Settings = s.copy(tmdbKey = "", ratingsKey = "", logUntil = 0L)

    fun encode(b: BackupFile): String = pretty.encodeToString(BackupFile.serializer(), sanitize(b))

    /**
     * Reads and checks a whole backup before anything is changed. Accepts format 2, and format 1
     * files from earlier versions of Relay (their PINs and keys are dropped).
     */
    fun decode(text: String): BackupFile {
        val root = try {
            Jsons.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BackupException("This isn't a Relay backup file.")
        if (root.at("app").str() != BackupFile.APP) throw BackupException("This isn't a Relay backup file.")
        val schema = root.at("schema").int() ?: root.at("version").int()
        val file = when {
            schema == BackupFile.SCHEMA -> try {
                Jsons.json.decodeFromJsonElement<BackupFile>(root)
            } catch (e: Exception) {
                throw BackupException("The backup file is damaged.", e)
            }
            schema == 1 && root.containsKey("data") -> fromV1(root.at("data") as? JsonObject ?: throw BackupException("The backup file is damaged."))
            schema != null && schema > BackupFile.SCHEMA ->
                throw BackupException("This backup is from a newer version of Relay. Update Relay, then try again.")
            else -> throw BackupException("This backup format isn't supported.")
        }
        validate(file)
        return sanitize(file)
    }

    private fun validate(b: BackupFile) {
        val ids = b.profiles.map { it.id }
        if (b.profiles.isEmpty()) throw BackupException("The backup has no profiles.")
        if (ids.any { it.isBlank() } || ids.toSet().size != ids.size) throw BackupException("The backup's profiles are damaged.")
        if (b.profiles.any { it.name.isBlank() }) throw BackupException("The backup's profiles are damaged.")
        if (b.settings.timeshiftMinutes !in 1..240 || b.settings.subSize !in 0..3) {
            throw BackupException("The backup's settings are damaged.")
        }
    }

    /** Format 1 stored the raw preference entries; map the known ones. */
    private fun fromV1(data: JsonObject): BackupFile {
        fun raw(key: String): String? = data[key].str()
        fun <T> decode(key: String, ser: KSerializer<T>): T? = raw(key)?.let {
            try {
                Jsons.json.decodeFromString(ser, it)
            } catch (e: Exception) {
                throw BackupException("The backup file is damaged ($key).", e)
            }
        }
        val profiles = decode("profiles", ListSerializer(Profile.serializer())).orEmpty()
        val favs = data.keys.filter { it.startsWith("fav_") }.associate { k ->
            k.removePrefix("fav_") to decode(k, ListSerializer(FavItem.serializer())).orEmpty()
        }
        val hist = data.keys.filter { it.startsWith("hist_") }.associate { k ->
            k.removePrefix("hist_") to decode(k, ListSerializer(WatchEntry.serializer())).orEmpty()
        }
        val tracks = data.keys.filter { it.startsWith("trk_") }.mapNotNull { k ->
            val id = k.removePrefix("trk_").toIntOrNull() ?: return@mapNotNull null
            decode(k, TrackPref.serializer())?.let { id to it }
        }.toMap()
        return BackupFile(
            profiles = profiles,
            lockedProfiles = profiles.filter { !it.pin.isNullOrEmpty() }.map { it.id },
            favourites = favs,
            history = hist,
            settings = decode("settings", Settings.serializer()) ?: Settings(),
            channels = decode("chcustom", ChannelCustom.serializer()) ?: ChannelCustom(),
            pinnedCategories = decode("pincats", ListSerializer(String.serializer())).orEmpty(),
            liveSort = decode("livesort", MapSerializer(String.serializer(), Int.serializer())).orEmpty(),
            seriesTracks = tracks,
            reminders = decode("reminders", ListSerializer(Reminder.serializer())).orEmpty(),
        )
    }
}
