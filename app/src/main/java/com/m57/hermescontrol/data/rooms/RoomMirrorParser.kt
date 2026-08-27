package com.m57.hermescontrol.data.rooms

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Parses the Bot Mode room mirror snapshot and applies the cache rules from
 * docs/room-view-contract.md rev 2.1 (Cache / Normalization sections).
 *
 * The expected values for every rule implemented here come from the
 * Desktop-derived oracle in HaeinGeek/asgard-rooms PR #2
 * (`tests/fixtures/EXPECTED.json`, `EXPECTED-cache-walk.json`); a parser that
 * passes `RoomMirrorParserTest` reproduces Desktop.
 */
object RoomMirrorParser {
    val json = Json { ignoreUnknownKeys = true }

    /** Parse + normalize a raw `ui_meta["hermes-bots-groups"]` payload. */
    fun parse(raw: JsonElement): NormalizedSnapshot {
        val envelope = json.decodeFromJsonElement(RoomsSnapshot.serializer(), raw)
        return normalize(envelope, declaredVersion = envelope.version)
    }


    /**
     * Version fallback per `normalizeGroupChatSyncSnapshot`:
     * - v3: keys are already `id:`/`name:`; used as-is.
     * - v2/v1: rooms are keyed by BARE NAME at the top level — lift every key
     *   to `name:<name>` and inject `name` into the room object.
     * - v1 tombstones are wall-clock ms: clamp every value to revision 0 so a
     *   1.7e12 timestamp can never outrank a real revision and mass-delete
     *   the cache.
     */
    fun normalize(
        envelope: RoomsSnapshot,
        declaredVersion: Int,
    ): NormalizedSnapshot {
        val legacy = declaredVersion < 3
        val roomsBuilder = LinkedHashMap<String, Pair<String, Room>>()
        envelope.rooms.forEach { (key, element) ->
            val room =
                runCatching { json.decodeFromJsonElement(Room.serializer(), element) }
                    .getOrDefault(Room())
            val sourceKey = if (legacy) "name:$key" else key
            val normalizedKey = if (legacy) sourceKey else roomIdentity(sourceKey, room)
            val displayName = room.name ?: if (legacy) key else sourceKey.removePrefix("name:")
            roomsBuilder[normalizedKey] = sourceKey to room.copy(name = displayName)
        }
        val deleted =
            envelope.deleted
                .mapKeys { (key, _) -> if (legacy) "name:$key" else key } // desktop lifts legacy tombstone keys too
                .mapValues { (_, rev) ->
                    if (declaredVersion < 2) 0L else rev // v1 wall-clock ms -> clamp to 0 (plugin.js L388)
                }
        return NormalizedSnapshot(declaredVersion, roomsBuilder.toMap(), deleted)
    }


    /** Stable room identity: `id:<roomId>` when present, else the original key. */
    fun roomIdentity(
        key: String,
        room: Room,
    ): String = room.roomId?.let { "id:$it" } ?: key
}

data class NormalizedSnapshot(
    val declaredVersion: Int,
    /** Normalized key -> (original key, room with injected display name). */
    val rooms: Map<String, Pair<String, Room>>,
    /** Normalized tombstones after v1 clamping. */
    val deleted: Map<String, Long>,
)

/**
 * Tombstone evaluation against a cached-room map, mirroring plugin.js L609–622:
 * - `id:` tombstone: unconditional delete.
 * - `name:` tombstone: delete only when tombstone rev >= cached room revision;
 *   an unmet stale tombstone is discarded after evaluation (not retried forever).
 * Returns which cached keys were deleted and which tombstone keys to discard.
 */
object Tombstones {
    data class Result(val deletedRoomKeys: Set<String>, val discardedTombstoneKeys: Set<String>)

    fun apply(
        deleted: Map<String, Long>,
        cachedRooms: Map<String, CachedRevision>,
    ): Result {
        val deletedRooms = mutableSetOf<String>()
        val discarded = mutableSetOf<String>()
        deleted.forEach { (key, rev) ->
            val cached = cachedRooms[key]
            when {
                cached == null -> Unit // nothing cached under this key; keep tombstone window as-is
                key.startsWith("id:") -> {
                    deletedRooms += key // id: final delete (L616)
                }
                // name: delete only on rev >= room.revision (L617); else discard (L620)
                rev >= cached.revision -> deletedRooms += key
                else -> discarded += key
            }
        }
        return Result(deletedRooms, discarded)
    }

    data class CachedRevision(val revision: Long)
}

/**
 * Best-effort boolean unread badge per SPEC Unread section:
 * ```
 * entry.from.kind == "member"
 * && entry.text matches /@user\b/i (case-insensitive)
 * && (lastOpenedAt == 0L ? true : normalizedAt(entry.at) > lastOpenedAt)
 * ```
 * Never-opened rooms badge regardless of `at`; known false negatives
 * (at==0 in opened rooms, 1200-char truncation) are accepted by design.
 */
object UnreadBadge {
    val MENTION = Regex("@user\\b", RegexOption.IGNORE_CASE)

    fun shouldBadge(
        entry: LogEntry,
        lastOpenedAt: Long,
    ): Boolean =
        entry.isMember &&
            (entry.text ?: "").contains(MENTION) &&
            (lastOpenedAt == 0L || entry.normalizedAt > lastOpenedAt)

    fun anyMention(
        log: List<LogEntry>,
        lastOpenedAt: Long,
    ): Boolean = log.any { shouldBadge(it, lastOpenedAt) }
}

/**
 * Cache lifecycle rules (SPEC Cache section):
 * - rooms present in the current snapshot replace their payload;
 * - absent-without-tombstone stays cached ("not currently mirrored");
 * - tombstone matching deletes room AND watermark (the only deletion path);
 * - payload cap 20 (oldest lastSeenInMirrorAt first), watermark records kept,
 *   capped at 200 with eviction key max(lastOpenedAt, lastSeenInMirrorAt).
 */
object RoomCacheOps {
    const val PAYLOAD_CAP = 20
    const val WATERMARK_CAP = 200

    @Serializable
    data class WatermarkRecord(
        val roomKey: String,
        val lastOpenedAt: Long,
        val lastSeenInMirrorAt: Long,
    )

    @Serializable
    data class State(
        val rooms: Map<String, Room> = emptyMap(),
        val watermarks: Map<String, WatermarkRecord> = emptyMap(),
    )

    /**
     * Merge one fetched snapshot into [state]. Mirrors the oracle's cache walk:
     * returns updated state plus the set of keys currently missing from the
     * mirror (for the "현재 미러에 없음" UI state).
     */
    fun merge(
        state: State,
        snapshot: NormalizedSnapshot,
        nowMs: Long,
        cachedMissingCap: Int = PAYLOAD_CAP,
    ): MergeOutcome {
        require(cachedMissingCap >= 0) { "cachedMissingCap must be non-negative" }
        val rooms = state.rooms.toMutableMap()
        val watermarks = state.watermarks.toMutableMap()

        // 1. Upsert rooms present in the mirror; refresh lastSeenInMirrorAt.
        val presentKeys = mutableSetOf<String>()
        snapshot.rooms.forEach { (key, pair) ->
            presentKeys += key
            rooms[key] = pair.second
            val existing = watermarks[key]
            watermarks[key] =
                existing?.copy(lastSeenInMirrorAt = nowMs)
                    ?: WatermarkRecord(key, lastOpenedAt = 0L, lastSeenInMirrorAt = nowMs)
        }

        // 2. Evaluate tombstones against the combined cache so a room present in
        //    the same snapshot cannot be resurrected after a matching tombstone.
        val cachedRevisions =
            rooms.mapValues { (_, room) -> Tombstones.CachedRevision(room.revision ?: 0L) }
        val tomb = Tombstones.apply(snapshot.deleted, cachedRevisions)
        val watermarkDeletes =
            tomb.deletedRoomKeys +
                snapshot.deleted.keys.filter { key -> key.startsWith("id:") && watermarks.containsKey(key) }
        tomb.deletedRoomKeys.forEach { key ->
            rooms.remove(key)
            presentKeys.remove(key)
        }
        watermarkDeletes.forEach(watermarks::remove)

        // 3. Bound cached-missing payloads only — current mirror rooms are never
        //    hidden by the local cache cap. Watermarks survive payload eviction.
        val missingKeys = (rooms.keys - presentKeys).toMutableSet()
        while (missingKeys.size > cachedMissingCap) {
            val key =
                missingKeys.minByOrNull { candidate ->
                    watermarks[candidate]?.lastSeenInMirrorAt ?: Long.MIN_VALUE
                } ?: break
            rooms.remove(key)
            missingKeys.remove(key)
        }

        // 4. Bound watermarks at 200 by max(lastOpenedAt, lastSeenInMirrorAt).
        while (watermarks.size > WATERMARK_CAP) {
            watermarks.minByOrNull { (_, rec) ->
                maxOf(rec.lastOpenedAt, rec.lastSeenInMirrorAt)
            }?.let { watermarks.remove(it.key) } ?: break
        }

        return MergeOutcome(
            state = State(rooms, watermarks),
            currentRoomKeys = presentKeys,
            missingFromMirror = missingKeys,
            discardedTombstoneKeys = tomb.discardedTombstoneKeys,
        )
    }

    data class MergeOutcome(
        val state: State,
        val currentRoomKeys: Set<String>,
        val missingFromMirror: Set<String>,
        val discardedTombstoneKeys: Set<String>,
    )
}

/** Profile selection over `profiles.list` results (deterministic, SPEC Read path). */
object ProfileSelection {
    const val GROUPS_META_KEY = "hermes-bots-groups"

    sealed interface Selection {
        /** Profile carrying `hermes-bots-groups`. */
        data class Found(val profileName: String, val snapshot: JsonElement) : Selection

        /** No default profile and zero or ambiguous non-default candidates. */
        data object Unavailable : Selection
    }

    /**
     * @param profiles decoded `profiles.list` result: list of profile objects
     *   (`name`, plus optional `uiMeta`/`ui_meta` map).
     */
    fun select(profiles: List<Map<String, Any?>>): Selection = selectResult(mapOf("profiles" to profiles))

    /** Select from the actual `{profiles:[...]}` gateway result envelope. */
    fun selectResult(result: Any?): Selection {
        val root = anyToJsonElement(result) as? JsonObject ?: return Selection.Unavailable
        val profiles = (root["profiles"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val default = profiles.firstOrNull { profileName(it) == "default" }
        if (default != null) {
            val snapshot = snapshotFrom(default) ?: return Selection.Unavailable
            return Selection.Found("default", snapshot)
        }

        // Fallback: exactly one non-default carrier. Such data may be residue of a
        // profile no Desktop is currently syncing (documented caveat).
        val carriers =
            profiles.mapNotNull { profile ->
                val name = profileName(profile) ?: return@mapNotNull null
                snapshotFrom(profile)?.let { Selection.Found(name, it) }
            }
        return carriers.singleOrNull() ?: Selection.Unavailable
    }

    private fun profileName(profile: JsonObject): String? =
        (profile["name"] as? JsonPrimitive)?.contentOrNull

    private fun snapshotFrom(profile: JsonObject): JsonElement? {
        val meta = (profile["ui_meta"] ?: profile["uiMeta"]) as? JsonObject ?: return null
        return meta[GROUPS_META_KEY]
    }

    /** Normalize whatever HermesWsClient hands back into a JsonElement. */
    fun anyToJsonElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> ->
                JsonObject(
                    value.entries.associate { (k, v) ->
                        (k as? String ?: k.toString()) to anyToJsonElement(v)
                    },
                )
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
}

/** Small helpers reused by tests: mention indexes and unreachable-mention listing. */
object RoomAnalysis {
    fun mentionIndexes(
        log: List<LogEntry>,
        lastOpenedAt: Long,
    ): List<Int> =
        log.withIndex().filter { (_, e) -> UnreadBadge.shouldBadge(e, lastOpenedAt) }
            .map { it.index }

    /**
     * Mentions that can never badge in an ALREADY-OPENED room because their
     * `at` normalized to 0 (`0 > lastOpenedAt` is false once opened).
     */
    fun mentionsUnreachableWhenOpened(log: List<LogEntry>): List<Int> =
        log.withIndex()
            .filter { (_, e) -> e.isMember && (e.text ?: "").contains(UnreadBadge.MENTION) && e.normalizedAt == 0L }
            .map { it.index }

    fun sortEntriesByNormalizedAt(log: List<LogEntry>): List<Int> =
        log.indices.sortedWith(compareBy({ log[it].normalizedAt }, { it }))
}
