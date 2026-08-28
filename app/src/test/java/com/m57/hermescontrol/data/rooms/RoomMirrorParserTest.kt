package com.m57.hermescontrol.data.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 parser tests. Fixture-backed expectations come from the Desktop-derived
 * oracle (HaeinGeek/asgard-rooms PR #2, `EXPECTED.json` /
 * `EXPECTED-cache-walk.json`). Display ordering is a separate mobile UI contract,
 * so its purpose-built tie case deliberately does not reuse the merge-order oracle.
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
        val expMobile = exp["rooms"]!!.jsonObject.values.first().jsonObject["mobile"]!!.jsonObject
        val normalizedAtExp =
            expMobile["normalizedAt"]!!.jsonArray
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
    fun `at normalization follows JavaScript numeric string grammar`() {
        val values = listOf(JsonPrimitive("0x10"), JsonPrimitive("1d"))

        assertEquals(listOf(16L, 0L), values.map(AtNormalizer::normalize))
    }

    @Test
    fun `at normalization rejects non-finite and out-of-long-range values`() {
        val cases =
            listOf(
                JsonPrimitive("Infinity"),
                JsonPrimitive("1e400"),
                JsonPrimitive(Double.MAX_VALUE),
            )

        assertEquals(listOf(0L, 0L, 0L), cases.map(AtNormalizer::normalize))
    }

    @Test
    fun `at normalization uses the oracle Long storage boundary`() {
        val oracleBoundary = (expectedFor("at-normalization")["longStorageBoundary"] as JsonPrimitive).content
        val guardBoundary = java.math.BigDecimal(Long.MAX_VALUE.toDouble()).toBigInteger().toString()

        assertEquals(oracleBoundary, guardBoundary)
        assertEquals(0L, AtNormalizer.normalize(JsonPrimitive(oracleBoundary)))
    }

    @Test
    fun `ieee-754 witnesses from 2^53 to 2^63 keep nearest-double value, boundary and beyond clamp to 0`() {
        // Witness set pinned by the asgard-rooms oracle (at-normalization.json
        // entries 16-19): 2^53+1 rounds down to the 2^53 double, 2^53+3 rounds
        // up to the 2^53+4 double, 2^62+1 rounds to the 2^62 double, and the
        // largest double below 2^63 (minus 1) stays exact — all four are valid
        // Kotlin Longs kept as their nearest IEEE-754 double. Entries 21-23 pin
        // the boundary itself: 2^63 and Long.MAX_VALUE clamp to 0 while the
        // largest double below 2^63 survives, so an outlier can never saturate
        // a read watermark.
        val keptCases =
            listOf(
                JsonPrimitive("9007199254740993"), // 2^53+1 -> 9007199254740992 (rounds down)
                JsonPrimitive("9007199254740995"), // 2^53+3 -> 9007199254740996 (rounds up)
                JsonPrimitive("4611686018427387905"), // 2^62+1 -> 4611686018427387904
                JsonPrimitive("9223372036854774785"), // largest double < 2^63, minus 1 -> ...784
            )
        val oracleKept =
            listOf(9007199254740992L, 9007199254740996L, 4611686018427387904L, 9223372036854774784L)
        assertEquals(oracleKept, keptCases.map(AtNormalizer::normalize))

        assertEquals(0L, AtNormalizer.normalize(JsonPrimitive("9223372036854775808"))) // 2^63
        assertEquals(0L, AtNormalizer.normalize(JsonPrimitive("9223372036854775807"))) // Long.MAX_VALUE
        assertEquals(9223372036854774784L, AtNormalizer.normalize(JsonPrimitive("9223372036854774784")))
    }

    @Test
    fun `display order is normalized at with array-order tiebreak`() {
        val log =
            listOf(
                LogEntry(id = "z-last-lexically", at = JsonPrimitive(20L)),
                LogEntry(id = "a-first-lexically", at = JsonPrimitive(20L)),
                LogEntry(id = "middle-time", at = JsonPrimitive(10L)),
            )

        val order = RoomAnalysis.sortEntriesByNormalizedAt(log)

        assertEquals(listOf(2, 0, 1), order)
    }

    // ------------------------------------------------------------------
    // 2. Badge semantics
    // ------------------------------------------------------------------

    @Test
    fun `gap marker uses first normalized entry at against read watermark`() {
        val zeroOldest = RoomMirrorParser.parse(obj("at-normalization.json")).rooms.values.first().second
        val zeroOldestExpected =
            expectedFor("at-normalization")["rooms"]!!.jsonObject.values.first().jsonObject["mobile"]!!.jsonObject
        assertEquals(
            (zeroOldestExpected["hasHistoryGapWhenNeverOpened"] as JsonPrimitive).booleanOrNull,
            RoomAnalysis.hasHistoryGap(zeroOldest.log, lastOpenedAt = 0L),
        )

        val positiveOldest =
            RoomMirrorParser.parse(obj("v3-normal.json"))
                .rooms.getValue("id:rmfixt001-aaaaa").second
        val oldestAt = positiveOldest.log.first().normalizedAt
        assertTrue(oldestAt > 0L)
        assertTrue(RoomAnalysis.hasHistoryGap(positiveOldest.log, lastOpenedAt = oldestAt - 1L))
        assertFalse(RoomAnalysis.hasHistoryGap(positiveOldest.log, lastOpenedAt = oldestAt))
        assertFalse(RoomAnalysis.hasHistoryGap(emptyList(), lastOpenedAt = 0L))
    }

    @Test
    fun `badge - member mention badges never-opened room regardless of at`() {
        val room = RoomMirrorParser.parse(obj("at-normalization.json")).rooms.values.first().second
        val expRoom =
            expectedFor("at-normalization")["rooms"]!!.jsonObject.values.first().jsonObject["mobile"]!!.jsonObject
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
        val expRoom =
            expectedFor("at-normalization")["rooms"]!!.jsonObject.values.first().jsonObject["mobile"]!!.jsonObject
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
    fun `legacy room display name comes from the map key`() {
        val raw =
            json.parseToJsonElement(
                """{"version":2,"rooms":{"map-key":{"name":"stale-inner","log":[]}},"deleted":{}}""",
            )

        val snapshot = RoomMirrorParser.parse(raw)

        assertEquals("map-key", snapshot.rooms.getValue("name:map-key").second.name)
    }

    @Test
    fun `v2 negative tombstone revision clamps to zero before comparison`() {
        val raw =
            json.parseToJsonElement(
                """{"version":2,"rooms":{"room":{"revision":0,"log":[]}},"deleted":{"room":-3}}""",
            )

        val snapshot = RoomMirrorParser.parse(raw)
        val tombstones = Tombstones.apply(snapshot.deleted, cachedRevisionsOf(snapshot))

        assertEquals(0L, snapshot.deleted.getValue("name:room"))
        assertEquals(setOf("name:room"), tombstones.deletedRoomKeys)
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
    fun `name tombstone keeps an evicted room watermark when revision cannot be matched`() {
        val key = "name:evicted-room"
        val watermark =
            RoomCacheOps.WatermarkRecord(
                roomKey = key,
                lastOpenedAt = 100L,
                lastSeenInMirrorAt = 200L,
            )
        val state = RoomCacheOps.State(watermarks = mapOf(key to watermark))
        val snapshot = NormalizedSnapshot(3, emptyMap(), mapOf(key to 7L))

        val outcome = RoomCacheOps.merge(state, snapshot, nowMs = 300L)

        assertEquals(watermark, outcome.state.watermarks[key])
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
                """
                {
                  "version": 3,
                  "rooms": {
                    "name:legacy": {"name": "legacy", "roomId": "modern-id", "log": []}
                  },
                  "deleted": {}
                }
                """.trimIndent(),
            )

        val snapshot = RoomMirrorParser.parse(raw)

        assertEquals(setOf("id:modern-id"), snapshot.rooms.keys)
    }

    @Test
    fun `v3 keeps rooms without array logs while pre-v3 drops them`() {
        fun rawFor(version: Int) =
            json.parseToJsonElement(
                """
                {
                  "version": $version,
                  "rooms": {
                    "id:missing": {"name": "missing", "roomId": "missing"},
                    "id:null": {"name": "null", "roomId": "null", "log": null},
                    "id:object": {"name": "object", "roomId": "object", "log": {"not": "an array"}},
                    "id:good": {"name": "good", "roomId": "good", "log": []}
                  },
                  "deleted": {}
                }
                """.trimIndent(),
            )

        // v3 is verbatim passthrough: rooms whose log is missing/null/an object
        // survive normalization and decode with an empty log.
        val v3 = RoomMirrorParser.parse(rawFor(3))
        assertEquals(setOf("id:missing", "id:null", "id:object", "id:good"), v3.rooms.keys)
        listOf("id:missing", "id:null", "id:object").forEach { key ->
            assertEquals(0, v3.rooms.getValue(key).second.log.size)
        }

        // The pre-v3 cleanup still skips those rooms rather than caching them
        // as empty (keys lift to the `name:` identity domain).
        val v2 = RoomMirrorParser.parse(rawFor(2))
        assertEquals(setOf("name:id:good"), v2.rooms.keys)
    }

    @Test
    fun `v3 malformed fixture matches oracle - passthrough keeps malformed room, id tombstone final-deletes`() {
        val snapshot = RoomMirrorParser.parse(obj("v3-malformed.json"))
        val exp = expectedFor("v3-malformed")

        // v3 is verbatim passthrough: BOTH rooms survive normalization — the
        // log-not-an-array room is kept (logIsArray=false witness, decodes with
        // an empty log and its stale inner name) and the negative tombstone
        // revision passes through unclamped.
        assertEquals(
            exp["roomKeysAfterNormalize"]!!.jsonArray.map { (it as JsonPrimitive).content }.toSet(),
            snapshot.rooms.keys,
        )
        assertEquals(
            exp["tombstoneKeysAfterNormalize"]!!.jsonObject.entries.associate {
                it.key to (it.value as JsonPrimitive).content.toLong()
            },
            snapshot.deleted,
        )
        assertEquals(-9L, snapshot.deleted.getValue("id:rmfixt041-negdel"))
        // Stale inner name is NOT replaced by the map key on the v3 path.
        assertEquals("stale-inner-name", snapshot.rooms.getValue("id:rmfixt040-malfrm").second.name)
        assertEquals(0, snapshot.rooms.getValue("id:rmfixt040-malfrm").second.log.size)
        assertEquals(4L, snapshot.rooms.getValue("id:rmfixt040-malfrm").second.revision)

        // roomKeysSurviving is the cache-walk outcome, not parsing: the id:
        // tombstone final-deletes the second room in the merge despite rev -9.
        val outcome = RoomCacheOps.merge(RoomCacheOps.State(), snapshot, nowMs = 1L)
        assertEquals(
            exp["roomKeysSurviving"]!!.jsonArray.map { (it as JsonPrimitive).content }.toSet(),
            outcome.state.rooms.keys,
        )
    }

    @Test
    fun `logIsArray oracle column matches parser acceptance per fixture`() {
        // Consume the logIsArray witness for every fixture against the actual
        // parser output: pre-v3 snapshots drop rooms whose log is not an array,
        // while v3 normalization is verbatim passthrough, so every room object
        // survives parsing regardless of logIsArray (deletion happens in the
        // cache walk, never during parsing).
        expected().forEach { entry ->
            val file = entry["file"]!!.jsonPrimitive.content
            val snapshot = RoomMirrorParser.parse(obj(file))
            val parsedKeys = snapshot.rooms.keys
            if (entry["declaredVersion"]!!.jsonPrimitive.int < 3) {
                entry["rooms"]!!.jsonObject.forEach { (key, roomExp) ->
                    val logIsArray = roomExp.jsonObject["logIsArray"]!!.jsonPrimitive.boolean
                    assertEquals("pre-v3 acceptance of $key in $file", logIsArray, key in parsedKeys)
                }
            } else {
                assertEquals(
                    "v3 passthrough keeps every room object in $file",
                    entry["roomKeysAfterNormalize"]!!.jsonArray.map { (it as JsonPrimitive).content }.toSet(),
                    parsedKeys,
                )
            }
        }
    }

    @Test
    fun `mobile oracle view matches derived display order, badges, and watermark rules per fixture`() {
        // Consume the remaining mobile oracle columns for every surviving room:
        // displayOrder (normalized at, array-order tiebreak), newestAt and
        // watermarkAfterOpen (max(0, newest, 1) in the entry-timestamp domain),
        // badgeNeverOpened / badgeOpenedBeforeWindow / badgeOpenedAtNewest, and
        // mentionEntryIndexes. Parity here is what makes EXPECTED.json's mobile
        // section a consumed oracle rather than dead weight.
        expected().forEach { entry ->
            val fixtureName = entry["file"]!!.jsonPrimitive.content
            val snapshot = RoomMirrorParser.parse(json.parseToJsonElement(fixture(fixtureName)))
            entry["rooms"]!!.jsonObject.forEach { (key, roomExp) ->
                val mobile = roomExp.jsonObject["mobile"]!!.jsonObject
                val room = snapshot.rooms[key]?.second ?: return@forEach
                val log = room.log
                val normalized = mobile["normalizedAt"]!!.jsonArray.map { (it as JsonPrimitive).content.toLong() }
                assertEquals("normalizedAt column for $key", normalized, log.map { it.normalizedAt })

                val displayOrder = mobile["displayOrder"]!!.jsonArray.map { (it as JsonPrimitive).content.toInt() }
                assertEquals("displayOrder for $key", displayOrder, RoomAnalysis.sortEntriesByNormalizedAt(log))

                if (log.isEmpty()) return@forEach

                val newestAt = mobile["newestAt"]!!.jsonPrimitive.content.toLong()
                assertEquals("newestAt for $key", newestAt, log.maxOf { it.normalizedAt })

                val watermarkAfterOpen = mobile["watermarkAfterOpen"]!!.jsonPrimitive.content.toLong()
                assertEquals(
                    "watermarkAfterOpen = max(0, newest, 1) for $key",
                    watermarkAfterOpen,
                    maxOf(0L, log.maxOf { it.normalizedAt }, 1L),
                )

                val oldestAt = mobile["oldestAt"]!!.jsonPrimitive.content.toLong()
                assertEquals(
                    "badgeNeverOpened for $key",
                    mobile["badgeNeverOpened"]!!.jsonPrimitive.boolean,
                    UnreadBadge.anyMention(log, 0L),
                )
                assertEquals(
                    "badgeOpenedBeforeWindow for $key",
                    mobile["badgeOpenedBeforeWindow"]!!.jsonPrimitive.boolean,
                    UnreadBadge.anyMention(log, oldestAt - 1L),
                )
                assertEquals(
                    "badgeOpenedAtNewest for $key",
                    mobile["badgeOpenedAtNewest"]!!.jsonPrimitive.boolean,
                    UnreadBadge.anyMention(log, newestAt),
                )

                val mentionIndexes =
                    mobile["mentionEntryIndexes"]!!.jsonArray.map { (it as JsonPrimitive).content.toInt() }
                assertEquals(
                    "mentionEntryIndexes for $key",
                    mentionIndexes,
                    log.withIndex().filter {
                            (_, e) ->
                        UnreadBadge.MENTION.containsMatchIn((e.text ?: "")) && e.isMember
                    }.map {
                            (i, _) ->
                        i
                    },
                )
            }
        }
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
