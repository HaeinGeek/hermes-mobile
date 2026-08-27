package com.m57.hermescontrol.data.rooms

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): Any =
        json.parseToJsonElement(
            javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!
                .readBytes()
                .decodeToString(),
        )

    @Test
    fun `refresh reads profiles list over the global WS method and caches the default mirror`() =
        runTest {
            val calls = mutableListOf<Pair<String, Map<String, Any>>>()
            val rpc = normalRpc(calls)
            val store = InMemoryRoomCacheStore()
            val repository = RoomsRepository(rpc, store) { 1_787_737_700_000L }

            val result = repository.refresh("connection-a")

            assertEquals(listOf(WsProfilesList.METHOD to mapOf("include_sessions" to false)), calls)
            assertTrue(result is RoomsRefresh.Available)
            result as RoomsRefresh.Available
            assertEquals("default", result.profileName)
            assertEquals(setOf("id:rmfixt001-aaaaa", "id:rmfixt002-bbbbb"), result.currentRoomKeys)
            assertEquals(result.state, store.load("connection-a"))
        }

    @Test
    fun `opening a room advances the watermark through the newest entry and stays connection scoped`() =
        runTest {
            val store = InMemoryRoomCacheStore()
            val repository = RoomsRepository(normalRpc(), store) { 1_787_737_700_000L }
            val refreshed = repository.refresh("connection-a") as RoomsRefresh.Available
            val roomKey = "id:rmfixt001-aaaaa"
            val room = refreshed.state.rooms.getValue(roomKey)
            val newestEntryAt = room.log.maxOf { it.normalizedAt }

            val opened = repository.markOpened("connection-a", roomKey, openedAt = 1L)

            val watermark = opened.watermarks.getValue(roomKey)
            assertEquals(newestEntryAt, watermark.lastOpenedAt)
            assertFalse(UnreadBadge.anyMention(room.log, watermark.lastOpenedAt))
            assertTrue(repository.cached("connection-b").rooms.isEmpty())
            assertTrue(repository.cached("connection-b").watermarks.isEmpty())
        }

    @Test
    fun `default without a mirror does not fall back to residual non-default data`() =
        runTest {
            val rpc =
                RoomsRpc { _, _ ->
                    mapOf(
                        "profiles" to
                            listOf(
                                mapOf("name" to "default"),
                                mapOf(
                                    "name" to "residual",
                                    "ui_meta" to mapOf(ProfileSelection.GROUPS_META_KEY to fixture("v3-normal.json")),
                                ),
                            ),
                    )
                }
            val result = RoomsRepository(rpc, InMemoryRoomCacheStore()).refresh("connection-a")

            assertTrue(result is RoomsRefresh.Unavailable)
        }

    @Test
    fun `concurrent refreshes never overlap profiles list requests`() =
        runTest {
            val releaseFirst = CompletableDeferred<Unit>()
            var active = 0
            var maximumActive = 0
            val rpc =
                RoomsRpc { _, _ ->
                    active += 1
                    maximumActive = maxOf(maximumActive, active)
                    releaseFirst.await()
                    active -= 1
                    mapOf("profiles" to emptyList<Any>())
                }
            val repository = RoomsRepository(rpc, InMemoryRoomCacheStore())

            val first = async { repository.refresh("connection-a") }
            runCurrent()
            val second = async { repository.refresh("connection-a") }
            runCurrent()

            assertEquals(1, maximumActive)
            releaseFirst.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, maximumActive)
        }

    private fun normalRpc(calls: MutableList<Pair<String, Map<String, Any>>>? = null) =
        RoomsRpc { method, params ->
            calls?.add(method to params)
            mapOf(
                "profiles" to
                    listOf(
                        mapOf(
                            "name" to "default",
                            "ui_meta" to mapOf(ProfileSelection.GROUPS_META_KEY to fixture("v3-normal.json")),
                        ),
                    ),
            )
        }

    private class InMemoryRoomCacheStore : RoomCacheStore {
        private val states = mutableMapOf<String, RoomCacheOps.State>()

        override suspend fun load(connectionId: String): RoomCacheOps.State =
            states[connectionId] ?: RoomCacheOps.State(emptyMap(), emptyMap())

        override suspend fun save(
            connectionId: String,
            state: RoomCacheOps.State,
        ) {
            states[connectionId] = state
        }
    }
}
