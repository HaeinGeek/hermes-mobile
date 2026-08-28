package com.m57.hermescontrol.data.rooms

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** All cached room state, partitioned by local connection-profile ID. */
@Serializable
data class RoomCacheDiskState(
    val connections: Map<String, RoomCacheOps.State> = emptyMap(),
)

object RoomCacheStateSerializer : Serializer<RoomCacheDiskState> {
    override val defaultValue = RoomCacheDiskState()

    override suspend fun readFrom(input: InputStream): RoomCacheDiskState =
        try {
            OkHttpProvider.json.decodeFromString(
                RoomCacheDiskState.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (_: Exception) {
            defaultValue
        }

    override suspend fun writeTo(
        t: RoomCacheDiskState,
        output: OutputStream,
    ) {
        output.write(
            OkHttpProvider.json
                .encodeToString(RoomCacheDiskState.serializer(), t)
                .toByteArray(),
        )
    }
}

private val Context.roomCacheDataStore: DataStore<RoomCacheDiskState> by dataStore(
    fileName = "room_mirror_cache.json",
    serializer = RoomCacheStateSerializer,
)

/** DataStore-backed cache used by the Rooms UI in Phase 4. */
class DataStoreRoomCacheStore(context: Context) : RoomCacheStore {
    private val appContext = context.applicationContext

    override suspend fun load(connectionId: String): RoomCacheOps.State =
        withContext(Dispatchers.IO) {
            appContext.roomCacheDataStore.data
                .catch { emit(RoomCacheDiskState()) }
                .first()
                .connections[connectionId] ?: RoomCacheOps.State()
        }

    override suspend fun save(
        connectionId: String,
        state: RoomCacheOps.State,
    ) {
        withContext(Dispatchers.IO) {
            appContext.roomCacheDataStore.updateData { disk ->
                disk.copy(connections = disk.connections + (connectionId to state))
            }
        }
    }
}
