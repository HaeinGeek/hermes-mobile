package com.m57.hermescontrol.data.rooms

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
