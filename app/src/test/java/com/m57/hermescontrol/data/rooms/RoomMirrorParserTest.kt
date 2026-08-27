package com.m57.hermescontrol.data.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 parser tests: every expectation comes from the Desktop-derived oracle
 * (HaeinGeek/asgard-rooms PR #2, `EXPECTED.json` / `EXPECTED-cache-walk.json`).
 * A parser passing these reproduces Desktop.
 */
class RoomMirrorParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .readBytes().decodeToString()

    private fun obj(name: String) = json.parseToJsonElement(fixture(name)).jsonObject

    private fun expected(): List<kotlinx.serialization.json.JsonObject> =
        Json.parseToJsonElement(fixture("EXPECTED.json")).jsonArray
            .map { it.jsonObject }

    private fun expectedFor(name: String) = expected().first { it["file"]!!.toString().contains(name) }

    // ------------------------------------------------------------------
    // 1. at normalization (fixture 4: at-normalization.json, idx table)
    // ------------------------------------------------------------------

    @Test
    fun `at normalization matches oracle per-entry expectations`() {
        val raw = obj("at-normalization.json")
        val snapshot = RoomMirrorParser.parse(raw)
        val room = snapshot.rooms.values.first().second
        val exp = expectedFor("at-normalization")
        val normalizedAtExp =
            exp["rooms"]!!.jsonObject.values.first().jsonObject["normalizedAt"]!!.jsonArray
                .map { (it as JsonPrimitive).content.toLong() }
        assertEquals(normalizedAtExp, room.log.map { it.normalizedAt })
    }

    @Test
    fun `at edge cases - missing null non-numeric negative boolean float numeric-string`() {
        val cases =
            listOf(
                "missing" to null as kotlinx.serialization.json.JsonElement?,
                "null" to JsonPrimitive("null"),
                "string-nonnumeric" to JsonPrimitive("soon"),
                "string-mixed" to JsonPrimitive("12abc"),
                "numeric-string" to JsonPrimitive("1787737510000"),
                "boolean-true" to JsonPrimitive(true),
                "negative" to JsonPrimitive(-5),
                "float" to JsonPrimitive(1787737520000.7),
            )
        val want = listOf(0L, 0L, 0L, 0L, 1787737510000L, 1L, 0L, 1787737520000L)
        assertEquals(want, cases.map { AtNormalizer.normalize(it.second) })
    }

    @Test
    fun `display order is normalized at with array-order tiebreak`() {
        val room = RoomMirrorParser.parse(obj("at-normalization.json")).rooms.values.first().second
        val exp =
            expectedFor("at-normalization").let { e ->
                e["rooms"]!!.jsonObject.values
                    .first()
                    .jsonObject["sortOrderByEntryKey"]!!
                    .jsonArray
                    .map { it.toString() }
            }
        val got =
            RoomAnalysis.sortEntriesByNormalizedAt(room.log).map {
                    i ->
                JsonPrimitive(entryKeyJson(room.log[i])).toString()
            }
        assertEquals(exp, got)
    }

    /** Mirrors the oracle's entry key: `id:<id>` when present, else JSON.stringify([...]). */
    private fun entryKeyJson(e: LogEntry): String {
        val id = e.id
        if (id != null) return "id:$id"
        // JS JSON.stringify of [Number, String, String, String, String]
        val arr =
            listOf(
                e.normalizedAt.toString(),
                "\"" + (e.from?.kind ?: "") + "\"",
                "\"" + (e.from?.name ?: "") + "\"",
                "\"" + (e.from?.source ?: "") + "\"",
                "\"legacy\"",
            )
        return "[" + arr.joinToString(",") + ","
    }

    // ------------------------------------------------------------------
    // 2. Badge semantics
    // ------------------------------------------------------------------

    @Test
    fun `badge - member mention badges never-opened room regardless of at`() {
        val room = RoomMirrorParser.parse(obj("at-normalization.json")).rooms.values.first().second
        val expRoom = expectedFor("at-normalization")["rooms"]!!.jsonObject.values.first().jsonObject
        assertEquals(true, (expRoom["badgeNeverOpened"] as JsonPrimitive).booleanOrNull)
        assertTrue(UnreadBadge.anyMention(room.log, lastOpenedAt = 0L))

        // Already opened before the window: only in-window mentions badge.
        assertEquals(true, UnreadBadge.anyMention(room.log, lastOpenedAt = 1_787_737_500_000L))
        // Opened at newest mention: no badge.
        assertFalse(UnreadBadge.anyMention(room.log, lastOpenedAt = 1_787_737_590_000L))
    }

    @Test
    fun `badge - user and non-member kinds never badge even with at-user text`() {
        val entries =
            listOf(
                entry(kind = "user", text = "@user please confirm"),
                entry(kind = "system", text = "@user reboot"),
                entry(kind = null, text = "@user ghost"),
            )
        entries.forEach { assertFalse(UnreadBadge.shouldBadge(it, 0L)) }
    }

    @Test
    fun `badge - atusername does not match but case-insensitive USER does`() {
        assertTrue(UnreadBadge.shouldBadge(entry(kind = "member", text = "ping @USER now"), 0L))
        assertFalse(UnreadBadge.shouldBadge(entry(kind = "member", text = "ping @username now"), 0L))
    }

    @Test
    fun `badge - unreachable mentions when opened are listed by the oracle rule`() {
        val room = RoomMirrorParser.parse(obj("at-normalization.json")).rooms.values.first().second
        val expRoom = expectedFor("at-normalization")["rooms"]!!.jsonObject.values.first().jsonObject
        val wantIdx =
            expRoom["mentionsUnreachableWhenOpened"]!!.jsonArray.map { (it as JsonPrimitive).content.toInt() }
        assertEquals(wantIdx, RoomAnalysis.mentionsUnreachableWhenOpened(room.log))
    }

    private fun entry(
        kind: String?,
        text: String,
    ): LogEntry =
        LogEntry(
            from = kind?.let { LogEntry.EntryFrom(it, "bot-one") },
            text = text,
            at = JsonPrimitive(1_787_737_600_000L),
        )

    // ------------------------------------------------------------------
    // 3. Version fallback + tombstones
    // ------------------------------------------------------------------

    @Test
    fun `v1 tombstone wall-clock ms clamps to revision 0 - room survives`() {
        val snapshot = RoomMirrorParser.parse(obj("legacy-v1.json"))
        assertEquals(1, snapshot.declaredVersion)
        assertEquals(mapOf("name:alpha-v1" to 0L), snapshot.deleted)
        val result = Tombstones.apply(snapshot.deleted, cachedRevisionsOf(snapshot))
        assertTrue(result.deletedRoomKeys.isEmpty())
        assertEquals(setOf("name:alpha-v1"), result.discardedTombstoneKeys)
        assertEquals(listOf("name:alpha-v1"), snapshot.rooms.keys.toList())
    }

    @Test
    fun `snapshot without a version follows the legacy name-keyed path`() {
        val raw =
            json.parseToJsonElement(
                """{"rooms":{"alpha":{"log":[]}},"deleted":{}}""",
            )

        val snapshot = RoomMirrorParser.parse(raw)

        assertEquals(0, snapshot.declaredVersion)
        assertEquals(setOf("name:alpha"), snapshot.rooms.keys)
    }

    @Test
    fun `v2 bare-name keys lift to name-prefixed identity`() {
        val snapshot = RoomMirrorParser.parse(obj("legacy-v2.json"))
        assertEquals(setOf("name:alpha-v2", "name:beta-v2"), snapshot.rooms.keys)
        snapshot.rooms.values.forEach { (_, room) ->
            assertTrue(room.name != null)
            room.log.forEach { assertTrue(it.id == null) } // pre-nested-era entries are id-less
        }
        val result = Tombstones.apply(snapshot.deleted, cachedRevisionsOf(snapshot))
        // gone-v2 tombstone rev 5 has no cached room; nothing deleted, nothing discarded.
        assertTrue(result.deletedRoomKeys.isEmpty())
        assertTrue(result.discardedTombstoneKeys.isEmpty())
    }

    @Test
    fun `mixed tombstones - ripe deletes stale is discarded`() {
        val snapshot = RoomMirrorParser.parse(obj("legacy-name-key.json"))
        val result = Tombstones.apply(snapshot.deleted, cachedRevisionsOf(snapshot))
        assertEquals(setOf("name:ripe-tomb"), result.deletedRoomKeys)
        assertEquals(setOf("name:stale-tomb"), result.discardedTombstoneKeys)
        val surviving = snapshot.rooms.keys - result.deletedRoomKeys
        assertEquals(setOf("id:rmfixt020-mixed", "name:oldroom", "name:stale-tomb"), surviving)
    }

    @Test
    fun `cache merge applies snapshot tombstones after upsert so ripe rooms cannot resurrect`() {
        val snapshot = RoomMirrorParser.parse(obj("legacy-name-key.json"))

        val outcome = RoomCacheOps.merge(RoomCacheOps.State(), snapshot, nowMs = 100L)

        assertFalse(outcome.state.rooms.containsKey("name:ripe-tomb"))
        assertTrue(outcome.state.rooms.containsKey("name:stale-tomb"))
        assertEquals(setOf("name:stale-tomb"), outcome.discardedTombstoneKeys)
        assertFalse(outcome.currentRoomKeys.contains("name:ripe-tomb"))
    }

    private fun cachedRevisionsOf(s: NormalizedSnapshot) =
        s.rooms.mapValues { (_, pair) -> Tombstones.CachedRevision(pair.second.revision ?: 0L) }

    // ------------------------------------------------------------------
    // 4. Cache walk (fixture 2 + oracle EXPECTED-cache-walk.json)
    // ------------------------------------------------------------------

    private fun cacheWalkExpected(trace: String): List<kotlinx.serialization.json.JsonObject> =
        Json.parseToJsonElement(fixture("EXPECTED-cache-walk.json"))
            .jsonObject[trace]!!.jsonArray.map { it.jsonObject }

    private fun gammaRecord(
        steps: List<kotlinx.serialization.json.JsonObject>,
        step: Int,
    ) = steps[step - 1]["cache"]!!.jsonArray
        .map { it.jsonObject }
        .last { it["key"]!!.toString().contains("gamma") }

    /**
     * Confirmed decision 3-revised: payload eviction keeps the read watermark,
     * tombstone matching is the only watermark deletion path. Gamma returns
     * with identical log/revision -> badge=false (evictedKeepWatermark trace).
     */
    @Test
    fun `eviction keeps watermark - returning gamma does not resurrect old mentions`() {
        var state = RoomCacheOps.State(emptyMap(), emptyMap())

        state =
            RoomCacheOps.merge(
                state,
                RoomMirrorParser.parse(obj("v3-capped-room-evicted/02-before.json")),
                1_787_737_100_000L,
            ).state
        // User opens gamma -> watermark set (oracle step 1 event).
        state =
            state.copy(
                watermarks =
                    state.watermarks.toMutableMap().apply {
                        val g = state.watermarks.filterKeys { it.contains("gamma") }.values.first()
                        put(g.roomKey, g.copy(lastOpenedAt = 1_787_730_000_000L))
                    },
            )
        val outcome2 =
            RoomCacheOps.merge(
                state,
                RoomMirrorParser.parse(obj("v3-capped-room-evicted/03-after.json")),
                1_787_737_300_000L,
                cachedMissingCap = 0,
            )
        state = outcome2.state
        // warm tombstoned -> gone entirely; gamma absent without tombstone -> payload evicted at test cap 0.
        assertTrue(state.rooms.keys.none { it.contains("warm") })
        assertTrue(state.rooms.keys.none { it.contains("gamma") })
        assertTrue(state.watermarks.keys.any { it.contains("gamma") })
        assertFalse(outcome2.missingFromMirror.contains("id:rmfixt010-hot01"))

        val outcome3 =
            RoomCacheOps.merge(
                state,
                RoomMirrorParser.parse(obj("v3-capped-room-evicted/04-gamma-returns.json")),
                1_787_737_600_000L,
                cachedMissingCap = 0,
            )
        val gammaKey = outcome3.state.watermarks.keys.first { it.contains("gamma") }
        val wm = outcome3.state.watermarks.getValue(gammaKey)
        assertEquals(1_787_730_000_000L, wm.lastOpenedAt) // watermark survived eviction path
        val badge =
            UnreadBadge.anyMention(
                outcome3.state.rooms.getValue(gammaKey).log,
                wm.lastOpenedAt,
            )
        assertFalse(badge)

        // Oracle cross-check for the same trace.
        val oracleGammaStep3 = gammaRecord(cacheWalkExpected("evictedKeepWatermark"), 3)
        assertEquals(false, (oracleGammaStep3["badge"] as JsonPrimitive).booleanOrNull)
        assertEquals(
            1_787_730_000_000L,
            (oracleGammaStep3["lastOpenedAt"] as JsonPrimitive).content.toLong(),
        )
    }

    /** The counterfactual the SPEC rejected: dropping watermarks resurrects old mentions. */
    @Test
    fun `watermark-dropping variant would produce the false positive the oracle documents`() {
        val steps = cacheWalkExpected("evicted")
        assertEquals(true, (gammaRecord(steps, 3)["badge"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun `id tombstone deletes an evicted room watermark even when its payload is already gone`() {
        val key = "id:evicted-room"
        val state =
            RoomCacheOps.State(
                rooms = emptyMap(),
                watermarks =
                    mapOf(
                        key to
                            RoomCacheOps.WatermarkRecord(
                                roomKey = key,
                                lastOpenedAt = 100L,
                                lastSeenInMirrorAt = 200L,
                            ),
                    ),
            )
        val snapshot = NormalizedSnapshot(3, emptyMap(), mapOf(key to 7L))

        val outcome = RoomCacheOps.merge(state, snapshot, nowMs = 300L)

        assertFalse(outcome.state.watermarks.containsKey(key))
    }

    @Test
    fun `watermark cap keeps 200 records and evicts the smallest effective recency`() {
        val watermarks =
            (0..RoomCacheOps.WATERMARK_CAP).associate { index ->
                val key = "id:watermark-$index"
                key to
                    RoomCacheOps.WatermarkRecord(
                        roomKey = key,
                        lastOpenedAt = index.toLong(),
                        lastSeenInMirrorAt = (index * 2L),
                    )
            }

        val outcome =
            RoomCacheOps.merge(
                RoomCacheOps.State(watermarks = watermarks),
                NormalizedSnapshot(3, emptyMap(), emptyMap()),
                nowMs = 1_000L,
            )

        assertEquals(RoomCacheOps.WATERMARK_CAP, outcome.state.watermarks.size)
        assertFalse(outcome.state.watermarks.containsKey("id:watermark-0"))
        assertTrue(outcome.state.watermarks.containsKey("id:watermark-200"))
    }

    // ------------------------------------------------------------------
    // 5. Profile selection
    // ------------------------------------------------------------------

    @Test
    fun `profile selection prefers default then unique carrier else unavailable`() {
        val groups = mapOf<String, Any>(ProfileSelection.GROUPS_META_KEY to emptyMap<String, Any>())
        val def = mapOf<String, Any?>("name" to "default", "uiMeta" to groups)
        val other = mapOf<String, Any?>("name" to "work", "ui_meta" to groups)
        val none = mapOf<String, Any?>("name" to "bare")

        assertTrue(ProfileSelection.select(listOf(def, other)) is ProfileSelection.Selection.Found)
        assertEquals(
            "default",
            (ProfileSelection.select(listOf(def, other)) as ProfileSelection.Selection.Found).profileName,
        )
        assertEquals("work", (ProfileSelection.select(listOf(other)) as ProfileSelection.Selection.Found).profileName)
        assertTrue(ProfileSelection.select(listOf(none)) is ProfileSelection.Selection.Unavailable)
        assertTrue(
            ProfileSelection.select(
                listOf(other, mapOf<String, Any?>("name" to "work2", "uiMeta" to groups)),
            ) is ProfileSelection.Selection.Unavailable,
        )
    }

    // ------------------------------------------------------------------
    // 6. v3 normal envelope end-to-end vs oracle
    // ------------------------------------------------------------------

    @Test
    fun `v3 room identity prefers roomId over the incoming map key`() {
        val raw =
            json.parseToJsonElement(
                """{"version":3,"rooms":{"name:legacy":{"name":"legacy","roomId":"modern-id","log":[]}},"deleted":{}}""",
            )

        val snapshot = RoomMirrorParser.parse(raw)

        assertEquals(setOf("id:modern-id"), snapshot.rooms.keys)
    }

    @Test
    fun `v3 normal parses nested from optional fields and matches oracle rooms`() {
        val snapshot = RoomMirrorParser.parse(obj("v3-normal.json"))
        val exp = expectedFor("v3-normal")
        assertEquals(
            exp["roomKeysSurviving"]!!.jsonArray.map { it.toString() }.map { it.trim('"') },
            snapshot.rooms.keys.toList(),
        )
        val alpha = snapshot.rooms["id:rmfixt001-aaaaa"]!!.second
        assertEquals(3, alpha.log.size)
        assertEquals(2, alpha.members.size)
        // Optional fields survive parsing: source on a remote member, thread on an entry.
        assertTrue(alpha.members.any { it.connectionId != null && it.sourceScoped == true })
        assertTrue(alpha.log.any { it.thread != null })
        assertEquals(listOf<Int>(2), RoomAnalysis.mentionIndexes(alpha.log, lastOpenedAt = 0L))
        val beta = snapshot.rooms["id:rmfixt002-bbbbb"]!!.second
        assertTrue(RoomAnalysis.mentionIndexes(beta.log, lastOpenedAt = 0L).isEmpty())
    }
}
