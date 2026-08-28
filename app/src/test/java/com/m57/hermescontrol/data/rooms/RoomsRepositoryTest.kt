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

            val opened = repository.markOpened("connection-a", roomKey)

            val watermark = opened.watermarks.getValue(roomKey)
            assertEquals(newestEntryAt, watermark.lastOpenedAt)
            assertFalse(UnreadBadge.anyMention(room.log, watermark.lastOpenedAt))
            assertTrue(repository.cached("connection-b").rooms.isEmpty())
            assertTrue(repository.cached("connection-b").watermarks.isEmpty())
        }

    @Test
    fun `opening a room ignores an ahead phone clock and stays in the entry timestamp domain`() =
        runTest {
            val store = InMemoryRoomCacheStore()
            val repository = RoomsRepository(normalRpc(), store) { Long.MAX_VALUE - 10L }
            val refreshed = repository.refresh("connection-a") as RoomsRefresh.Available
            val roomKey = "id:rmfixt001-aaaaa"
            val newestEntryAt = refreshed.state.rooms.getValue(roomKey).log.maxOf { it.normalizedAt }

            val opened = repository.markOpened("connection-a", roomKey)

            assertEquals(newestEntryAt, opened.watermarks.getValue(roomKey).lastOpenedAt)
        }

    @Test
    fun `opening a room never moves an existing read watermark backward`() =
        runTest {
            val connectionId = "connection-a"
            val roomKey = "id:rmfixt001-aaaaa"
            val store = InMemoryRoomCacheStore()
            val repository = RoomsRepository(normalRpc(), store)
            val refreshed = repository.refresh(connectionId) as RoomsRefresh.Available
            val existing = refreshed.state.watermarks.getValue(roomKey)
            val previousReadThrough = refreshed.state.rooms.getValue(roomKey).log.maxOf { it.normalizedAt } + 100L
            store.save(
                connectionId,
                refreshed.state.copy(
                    watermarks =
                        refreshed.state.watermarks +
                            (roomKey to existing.copy(lastOpenedAt = previousReadThrough)),
                ),
            )

            val opened = repository.markOpened(connectionId, roomKey)

            assertEquals(previousReadThrough, opened.watermarks.getValue(roomKey).lastOpenedAt)
        }

    @Test
    fun `opening an all-zero timestamp room records the opened sentinel`() =
        runTest {
            val connectionId = "connection-a"
            val roomKey = "id:zero-at"
            val store = InMemoryRoomCacheStore()
            store.save(
                connectionId,
                RoomCacheOps.State(
                    rooms =
                        mapOf(
                            roomKey to
                                Room(
                                    log =
                                        listOf(
                                            LogEntry(
                                                from = LogEntry.EntryFrom(kind = "member", name = "bot"),
                                                text = "@user review",
                                                at = null,
                                            ),
                                        ),
                                ),
                        ),
                ),
            )
            val repository = RoomsRepository(normalRpc(), store)

            val opened = repository.markOpened(connectionId, roomKey)

            assertEquals(1L, opened.watermarks.getValue(roomKey).lastOpenedAt)
            assertFalse(UnreadBadge.anyMention(opened.rooms.getValue(roomKey).log, lastOpenedAt = 1L))
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
    fun `malformed mirror envelope is unavailable and keeps the cache`() =
        runTest {
            val connectionId = "connection-a"
            val cached = RoomCacheOps.State(rooms = mapOf("id:cached" to Room(name = "cached")))
            val store = InMemoryRoomCacheStore().apply { save(connectionId, cached) }
            val malformed =
                json.parseToJsonElement(
                    """{"version":"oops","rooms":{},"deleted":{}}""",
                )
            val rpc =
                RoomsRpc { _, _ ->
                    mapOf(
                        "profiles" to
                            listOf(
                                mapOf(
                                    "name" to "default",
                                    "ui_meta" to mapOf(ProfileSelection.GROUPS_META_KEY to malformed),
                                ),
                            ),
                    )
                }

            val result = RoomsRepository(rpc, store).refresh(connectionId)

            assertTrue(result is RoomsRefresh.Unavailable)
            assertEquals(cached, result.state)
            assertEquals(cached, store.load(connectionId))
        }

    @Test
    fun `RPC failure returns stale error state without replacing the cache`() =
        runTest {
            val connectionId = "connection-a"
            val cached = RoomCacheOps.State(rooms = mapOf("id:cached" to Room(name = "cached")))
            val store = InMemoryRoomCacheStore().apply { save(connectionId, cached) }
            val repository =
                RoomsRepository(
                    rpc = RoomsRpc { _, _ -> error("gateway offline") },
                    store = store,
                )

            val result = repository.refresh(connectionId)

            assertTrue(result is RoomsRefresh.Error)
            result as RoomsRefresh.Error
            assertEquals(cached, result.state)
            assertEquals("gateway offline", result.message)
            assertEquals(cached, store.load(connectionId))
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
