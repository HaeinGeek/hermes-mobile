package com.m57.hermescontrol.data.rooms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull

/**
 * Wire models for the Hermes Desktop Bot Mode room mirror snapshot (v3),
 * per docs/room-view-contract.md rev 2.1.
 *
 * The Desktop projects its live rooms into the default profile's `ui_meta`
 * under `hermes-bots-groups`; the app reads it via the gateway WS RPC
 * `profiles.list({include_sessions:false})`. Unknown fields are ignored —
 * every optional wire field is nullable with a default.
 */
@Serializable
data class RoomsSnapshot(
    val version: Int = 0,
    val updatedAt: Long? = null,
    /** v3 keys are `id:<roomId>` or legacy `name:<name>`; pre-v3 envelopes use bare names. */
    val rooms: Map<String, JsonElement> = emptyMap(),
    /** Tombstones: key -> revision (v1 wall-clock ms, clamped during normalization). */
    val deleted: Map<String, Long> = emptyMap(),
)

@Serializable
data class RoomMember(
    val name: String,
    val handle: String? = null,
    @SerialName("connectionId") val connectionId: String? = null,
    @SerialName("connectionKind") val connectionKind: String? = null,
    @SerialName("connectionLabel") val connectionLabel: String? = null,
    @SerialName("sourceScoped") val sourceScoped: Boolean? = null,
)

/**
 * A single log entry. The wire shape is nested (`from.kind` / `from.name` /
 * optional `from.source`) — never flat. `kind` is `user|member` on the wire;
 * the Desktop projection collapses anything non-`member` to `user`, and the
 * parser defends unknown kinds as user bubbles too ([LogEntry.isMember]).
 */
@Serializable
data class LogEntry(
    val id: String? = null,
    val from: EntryFrom? = null,
    val text: String? = null,
    val at: JsonElement? = null,
    val thread: String? = null,
) {
    @Serializable
    data class EntryFrom(
        val kind: String? = null,
        val name: String? = null,
        val source: String? = null,
    )

    /** Anything that is not exactly `member` renders as a user bubble. */
    val isMember: Boolean get() = from?.kind == MEMBER_KIND

    /**
     * Normalized `at` per SPEC Normalization rules: numeric coercion missing/
     * null/non-numeric to 0, numeric strings parse, floats truncate, negative
     * clamps to 0 (deliberate divergence from Desktop's `Number(at || 0)`).
     */
    val normalizedAt: Long by lazy { AtNormalizer.normalize(at) }

    companion object {
        const val MEMBER_KIND = "member"
    }
}

/** Defensive `at` coercion shared by parsing, sorting, and watermark comparison. */
object AtNormalizer {
    fun normalize(raw: JsonElement?): Long {
        val prim = raw as? kotlinx.serialization.json.JsonPrimitive ?: return 0L
        // Mirrors oracle normAt: Number(entry.at || 0), then clamp NaN/negative to 0.
        val value =
            when {
                prim.isString ->
                    when (prim.content) {
                        "", "null", "true", "false" -> null // JS Number("") = 0 but these are not real timestamps
                        else -> prim.content.toDoubleOrNull()
                    }
                prim.booleanOrNull != null -> if (prim.booleanOrNull == true) 1.0 else null // Number(true)=1
                else -> prim.content.toDoubleOrNull()
            } ?: return 0L
        if (value.isNaN() || value < 0) return 0L
        return value.toLong()
    }
}

@Serializable
data class Room(
    @SerialName("name") val name: String? = null,
    @SerialName("roomId") val roomId: String? = null,
    val revision: Long? = null,
    val members: List<RoomMember> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val image: String? = null,
)
