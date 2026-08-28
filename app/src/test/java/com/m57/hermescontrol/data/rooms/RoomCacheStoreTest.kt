package com.m57.hermescontrol.data.rooms

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RoomCacheStoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `disk serializer round-trips independent connection caches`() =
        runTest {
            val fixture =
                javaClass.classLoader!!.getResourceAsStream("fixtures/v3-normal.json")!!
                    .readBytes()
                    .decodeToString()
            val snapshot = RoomMirrorParser.parse(json.parseToJsonElement(fixture))
            val populated = RoomCacheOps.merge(RoomCacheOps.State(), snapshot, nowMs = 123L).state
            val expected =
                RoomCacheDiskState(
                    connections =
                        mapOf(
                            "connection-a" to populated,
                            "connection-b" to RoomCacheOps.State(),
                        ),
                )
            val bytes = ByteArrayOutputStream()

            RoomCacheStateSerializer.writeTo(expected, bytes)
            val restored = RoomCacheStateSerializer.readFrom(ByteArrayInputStream(bytes.toByteArray()))

            assertEquals(expected, restored)
        }

    /**
     * Persisted-size witness for the 48 KB gateway cap (Desktop
     * `groupChatGatewayJsonSize`, limit 48000). The mirror is parsed from a
     * maximally-capped SOURCE payload — 20 rooms × 16 entries × the largest
     * per-message budget that still fits the cap — asserted against the
     * Desktop estimator itself before caching. The persisted payload may only
     * rearrange that bounded data (never add unbounded copies), so it must
     * stay under the same 48000; on top of it the disk state carries at most
     * WATERMARK_CAP read-watermark records (~90 bytes each; 128 leaves room
     * for long room keys), which bounds the whole file.
     */
    @Test
    fun `persisted cache stays below the 48 KB gateway budget at cache caps`() =
        runTest {
            fun gatewayBytesOf(jsonText: String): Int = GatewaySizeEstimator.gatewayBytesOf(jsonText)

            // 13 chars: the largest 20×16-entry payload that still fits 48 KB
            // (44,130 bytes by the Desktop estimator).
            val body = "message body "

            fun buildMirror(): String {
                val rooms =
                    (0 until RoomCacheOps.PAYLOAD_CAP).joinToString(",") { idx ->
                        val entries =
                            (0 until 16).joinToString(",") { e ->
                                """{"id":"room-$idx-entry-$e","from":{"kind":"member","name":"member-${e % 8}"},""" +
                                    """"text":"$body","at":${1787737400000L + e}}"""
                            }
                        """"id:room-$idx":{"name":"room-$idx","roomId":"room-$idx","revision":1,""" +
                            """"members":[${(0 until 8).joinToString(",") { m -> """{"name":"member-$m"}""" }}],""" +
                            """"log":[$entries]}"""
                    }
                return """{"version":3,"rooms":{$rooms},"deleted":{}}"""
            }

            val mirrorJson = buildMirror()
            val sourceBytes = gatewayBytesOf(mirrorJson)
            assertTrue(
                "source mirror ($sourceBytes bytes by the Desktop estimator) must fit the 48000-byte gateway budget",
                sourceBytes <= 48_000,
            )

            val snapshot = RoomMirrorParser.parse(json.parseToJsonElement(mirrorJson))
            assertEquals(RoomCacheOps.PAYLOAD_CAP, snapshot.rooms.size)
            val watermarks =
                (0 until RoomCacheOps.WATERMARK_CAP).associate { idx ->
                    val key = if (idx % 2 == 0) "id:room-$idx" else "name:room-$idx"
                    key to
                        RoomCacheOps.WatermarkRecord(
                            roomKey = key,
                            lastOpenedAt = 1787737400000L + idx,
                            lastSeenInMirrorAt = 1787737500000L + idx,
                        )
                }
            val state =
                RoomCacheDiskState(
                    connections =
                        mapOf(
                            "connection-a" to
                                RoomCacheOps.State(
                                    snapshot.rooms.mapValues { it.value.second },
                                    watermarks,
                                ),
                        ),
                )
            val bytes = ByteArrayOutputStream()

            RoomCacheStateSerializer.writeTo(state, bytes)

            val persisted = bytes.toByteArray().size
            // Bound 2 — the disk file is the payload plus only bounded local read
            // watermarks: WATERMARK_CAP records of at most ~128 bytes each.
            assertTrue(
                "persisted cache ($persisted bytes) exceeds the payload budget plus " +
                    "${RoomCacheOps.WATERMARK_CAP} watermark records",
                persisted < 48_000 + RoomCacheOps.WATERMARK_CAP * 128,
            )

            // Bound 1 — the cached mirror payload alone rearranges a source that
            // already fit the 48 KB gateway budget, so its persisted form must too.
            val payloadOnly = ByteArrayOutputStream()
            RoomCacheStateSerializer.writeTo(
                state.copy(
                    connections = state.connections.mapValues { (_, conn) -> conn.copy(watermarks = emptyMap()) },
                ),
                payloadOnly,
            )
            assertTrue(
                "persisted payload (${payloadOnly.toByteArray().size} bytes) exceeds the 48000-byte gateway budget",
                payloadOnly.toByteArray().size < 48_000,
            )
        }
}
