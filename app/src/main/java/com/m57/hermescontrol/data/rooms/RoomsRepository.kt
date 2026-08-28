package com.m57.hermescontrol.data.rooms

import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/** RPC boundary kept injectable so repository tests exercise the real request contract. */
fun interface RoomsRpc {
    suspend fun request(
        method: String,
        params: Map<String, Any>,
    ): Any?
}

object HermesRoomsRpc : RoomsRpc {
    override suspend fun request(
        method: String,
        params: Map<String, Any>,
    ): Any? = HermesWsClient.request(method, params).await()
}

/** Global gateway method used by Desktop and mobile to read profile UI metadata. */
object WsProfilesList {
    const val METHOD = WsMethods.PROFILES_LIST
    val PARAMS: Map<String, Any> = mapOf("include_sessions" to false)
}

/** Connection-scoped persistence boundary for room payloads and read watermarks. */
interface RoomCacheStore {
    suspend fun load(connectionId: String): RoomCacheOps.State

    suspend fun save(
        connectionId: String,
        state: RoomCacheOps.State,
    )
}

sealed interface RoomsRefresh {
    val state: RoomCacheOps.State

    data class Available(
        val profileName: String,
        val currentRoomKeys: Set<String>,
        val missingFromMirror: Set<String>,
        override val state: RoomCacheOps.State,
    ) : RoomsRefresh

    data class Unavailable(
        override val state: RoomCacheOps.State,
    ) : RoomsRefresh

    data class Error(
        override val state: RoomCacheOps.State,
        val message: String?,
    ) : RoomsRefresh
}

/**
 * Phase-3 room data layer. It reads the global `profiles.list` WS method, selects
 * the deterministic mirror carrier, merges it into a connection-scoped cache,
 * and serializes refreshes so requests never overlap.
 */
class RoomsRepository(
    private val rpc: RoomsRpc,
    private val store: RoomCacheStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val refreshMutex = Mutex()

    suspend fun cached(connectionId: String): RoomCacheOps.State = store.load(connectionId)

    /**
     * Marks a room read through its newest mirrored entry. The watermark stays
     * in the Desktop entry timestamp domain and is monotonically non-decreasing;
     * 1L is the opened sentinel when every mirrored timestamp normalizes to 0.
     */
    suspend fun markOpened(
        connectionId: String,
        roomKey: String,
    ): RoomCacheOps.State =
        refreshMutex.withLock {
            val state = store.load(connectionId)
            val room = state.rooms[roomKey] ?: return@withLock state
            val newestEntryAt = room.log.maxOfOrNull { it.normalizedAt } ?: 0L
            val existing = state.watermarks[roomKey]
            val record =
                RoomCacheOps.WatermarkRecord(
                    roomKey = roomKey,
                    lastOpenedAt = maxOf(existing?.lastOpenedAt ?: 0L, newestEntryAt, 1L),
                    lastSeenInMirrorAt = existing?.lastSeenInMirrorAt ?: nowMs(),
                )
            val updated = state.copy(watermarks = state.watermarks + (roomKey to record))
            store.save(connectionId, updated)
            updated
        }

    suspend fun refresh(connectionId: String): RoomsRefresh =
        refreshMutex.withLock {
            val previous = store.load(connectionId)
            try {
                val result = rpc.request(WsProfilesList.METHOD, WsProfilesList.PARAMS)
                val selection = ProfileSelection.selectResult(result)
                if (selection !is ProfileSelection.Selection.Found) {
                    return@withLock RoomsRefresh.Unavailable(previous)
                }

                val snapshot =
                    try {
                        RoomMirrorParser.parse(selection.snapshot)
                    } catch (_: SerializationException) {
                        return@withLock RoomsRefresh.Unavailable(previous)
                    }
                val merged = RoomCacheOps.merge(previous, snapshot, nowMs())
                store.save(connectionId, merged.state)
                RoomsRefresh.Available(
                    profileName = selection.profileName,
                    currentRoomKeys = merged.currentRoomKeys,
                    missingFromMirror = merged.missingFromMirror,
                    state = merged.state,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RoomsRefresh.Error(previous, error.message)
            }
        }
}
