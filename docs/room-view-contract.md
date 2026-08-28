# SPEC: Bot Mode room view for hermes-mobile (rev 2.3)

Rev 2 supersedes the initial SPEC (`6171b5c` in hermes-mobile); rev 2.1 syncs the
reviewed contract, rev 2.2 the oracle-pinned normalization rules, and rev 2.3 the
IEEE-754/JVM Long boundary. Every claim below is
verified against the **real** Desktop source, not the release note: release
"Hermes Agent v0.20.5" has no `v0.20.5` Git tag — its tag is **`v2026.8.19`**, file
`apps/desktop/src/plugins/hermes-bots/plugin.js`.

## Goal

Show Hermes Desktop *Bot Mode* group chats (rooms) in the Android app, read-only,
over the existing Gateway WebSocket. The app reads what the Desktop mirror currently
holds and nothing more.

## Read path

- Data source is the WS RPC `profiles.list` with params
  `{"include_sessions": false}`. **REST `GET /api/profiles` does not expose
  `ui_meta`** — neither `_profile_to_dict()` nor the fallback dicts carry it; the
  only exposure point in the entire backend is the gateway RPC
  (`tui_gateway/methods_profiles.py`). The Desktop itself reads the same RPC
  (`groupChatRemoteSnapshot` → `host.request('profiles.list', …)`).
- `profiles.list` is a global method: do **not** add it to the profile-scoped
  auto-injection set.
- Profile selection (deterministic): prefer `name == "default"`; if absent and
  exactly one profile carries `hermes-bots-groups`, fall back to it — with the
  caveat that such fallback data may be residue from a profile no Desktop is
  currently syncing; if several candidates exist, show an explicit unavailable
  state instead of picking arbitrarily.
- Polling: every 15 s while lifecycle ≥ STARTED, single request in flight at a
  time, plus pull-to-refresh.
- An RPC failure returns an error state and leaves the connection-scoped cache
  unchanged.
- A selected-profile snapshot that cannot be decoded returns the unavailable
  state and also leaves the connection-scoped cache unchanged.

## Mirror snapshot schema (v3, actual)

```json
{
  "version": 3,
  "updatedAt": 1787731411061,
  "rooms": {
    "id:rmt9ltszp-d0qr0": {
      "name": "review",
      "roomId": "rmt9ltszp-d0qr0",
      "revision": 2,
      "members": [{"name": "brokk", "handle": "brokk", "connectionId": "?",
                    "connectionKind": "?", "connectionLabel": "?", "sourceScoped": true}],
      "log": [{"id": "?",
               "from": {"kind": "user|member", "name": "loki", "source": "?"},
               "text": "...", "at": 1787731411061, "thread": "?"}],
      "image": "?"
    }
  },
  "deleted": {"id:rmt9ltszp-d0qr0": 2, "name:legacy-room": 3}
}
```

- `?` = optional; ignore unknown fields.
- `from.kind` is `user|member`: the Desktop projection collapses every non-`member`
  kind to `user`, so `system` and other values never reach the mirror. The parser
  still defends the branch defensively (any value ≠ `member` renders as user bubble).
- Log entries are **nested** (`from.kind` / `from.name` / optional `from.source`),
  never flat `{kind, name}` as rev 1 showed.
- The snapshot has **no** `needsYou`. That flag is a Desktop-local atom and never
  enters the projection; see *Unread* below for its replacement.
- Limits: **at most 16 messages per projected room**, **1200 chars per message**
  (`GROUP_CHAT_SYNC_TEXT_CHARS`); the 48 KB cap may evict whole rooms (quietest
  first) — the room list is a *Desktop-mirror window*, not the full room set.
  Rooms with empty logs are not projected, so a projected room never represents
  a zero-message payload.
- The gateway estimator charges 6 bytes per non-ASCII BMP codepoint versus its
  3-byte UTF-8 representation, so Korean-heavy mirrors consume the 48 KB budget
  faster than ASCII-heavy mirrors. This is source-derived, not a live-window
  estimate: `asgard-rooms` PR #2 runs the extracted Desktop
  `groupChatGatewayJsonSize` and records the cap-crossing thresholds in
  `tests/fixtures/GATEWAY-SIZE-EVIDENCE.json`.

## Normalization and identity

- `version < 3`: name-keyed rooms are lifted to `name:<name>` per
  `normalizeGroupChatSyncSnapshot`, and the map key replaces any stale inner
  `name`. v1 tombstones are wall-clock ms and clamp to revision 0 so they can
  never outrank a real revision and mass-delete the cache; negative v2 tombstone
  revisions also clamp to 0 before comparison. These are the **pre-v3 only**
  normalization rules.
- **v3 snapshots are trusted as-is.** For `version >= 3`,
  `normalizeGroupChatSyncSnapshot` (plugin.js L367–376) passes `rooms` and
  `deleted` through **unchanged**: it does not drop rooms whose `log` is missing
  or not an array, does not clamp negative tombstone revisions, and does not let
  the map key replace a stale inner `name`. Whatever the Desktop projection
  wrote is read back verbatim; a consumer that needs those invariants must apply
  them itself, after normalization. The fixture `v3-malformed.json` pins this
  passthrough (malformed room kept, negative tombstone kept, stale inner name
  kept); the id: tombstone rule still final-deletes such a room in the cache
  walk regardless of sign.
- Room identity: `id:<roomId>` when present, else original `name:` key.
- `entry.at` normalization follows `Number(entry.at || 0)`: missing, null,
  non-numeric strings, and boolean `false` become `0`; boolean `true` becomes `1`.
  **Negative values clamp to `0`** — a deliberate divergence from Desktop, making
  `at >= 0` a single invariant for sorting and watermark comparison. Numeric
  strings use JavaScript `Number()` grammar (`"0x10"` → `16`, `"1d"` → `0`);
  floats truncate to Long. The Long boundary is the **IEEE-754/JVM rule**: a
  decimal in the valid Kotlin Long range whose nearest double rounds *away* from
  the exact integer normalizes to that double's value (e.g. `2^53+1` → `2^53`,
  `2^53+3` → `2^53+4`, `2^62+1` → `2^62`); the largest double below `2^63`
  (`9223372036854774784`) is the last kept value; `2^63` and `Long.MAX_VALUE`
  itself normalize to `0` because `Long.MAX_VALUE.toDouble()` rounds up to
  exactly `2^63`, which cannot be a Long. This bound is a prerequisite for the
  monotonic read-through rule in *Unread*: an outlier promoted to the watermark
  could never be lowered. Display order is normalized `at`, ties broken by log
  array order.
- `entry.id`, when present, is only an auxiliary dedup key — never the primary
  identity or watermark basis.

## Cache and tombstones

- Rooms present in the current snapshot replace their cached payload wholesale.
- A room absent from the snapshot but present in cache **stays cached** and is
  surfaced as "not currently mirrored": absence alone may be 48 KB-cap eviction,
  not deletion.
- Deletion happens **only** via `deleted` tombstones:
  - `id:` tombstone ⇒ unconditional delete of the matching cached room;
  - `name:` tombstone ⇒ delete only when tombstone revision ≥ cached room revision;
  - an unmet (stale) `name:` tombstone is discarded after evaluation — it is not
    retried on every poll.
- Tombstone-triggered deletion also deletes the room's local read watermark —
  **the only path that ever deletes a watermark.** Payload eviction never touches it.
- Cached-missing rooms accumulate `lastSeenInMirrorAt`; above 20 retained room
  payloads the oldest (by `lastSeenInMirrorAt`) are evicted. Eviction removes the
  room body only — the read watermark stays, so a capped-out room that returns does
  not resurface old mentions as unread.
- Read watermarks are separate records of `(roomKey, lastOpenedAt, lastSeenInMirrorAt)`
  and outlive their room payloads; they are bounded at **200**, evicting the record
  with the smallest `max(lastOpenedAt, lastSeenInMirrorAt)` first — a recently
  evicted-from-mirror room must not be mistaken for a long-unread one.
- Cache and read-state are scoped by `(connection profile ID, room identity)` and
  never mixed across servers/profiles.

## Unread (local read-state badge)

Best-effort **boolean** per room:

```
entry.from.kind == "member"
&& entry.text matches /@user\b/i   (case-insensitive)
&& (room.lastOpenedAt == 0 ? true : normalizeAt(entry.at) > room.lastOpenedAt)
```

- Never-opened rooms (`lastOpenedAt == 0`) badge regardless of `at` — including
  entries whose `at` normalized to `0`.
- Known false negatives, accepted by design (documented, not bugs):
  - already-opened rooms cannot badge mentions whose `at` normalized to `0`;
  - the 1200-char truncation can cut a mention out of the mirrored slice while
    Desktop badged the full text — derived badge and Desktop badge may disagree.
- No unread **count**: the mirror window can be shorter than one hour, so counts
  are meaningless. If `lastOpenedAt < log[0].at` (normalized), show a
  "older history exists outside the mirror" gap marker.
- `lastOpenedAt` is a monotonically non-decreasing read-through watermark over
  observed mirrored `entry.at` values, not the phone wall-clock open time.
- Opening a room sets `lastOpenedAt = max(previousLastOpenedAt, newest normalized
  mirrored entry.at, 1L)`. `1L` is the opened sentinel when every mirrored
  timestamp normalizes to `0`; this clears both unread and gap indicators without
  mixing phone time into the comparison.
- The non-finite/out-of-Long normalization guard above is required here: once an
  outlier enters this monotonic watermark it cannot be lowered, so such values
  must normalize to `0`.
- Eviction-return: payload eviction keeps the room's read watermark (see Cache —
  tombstone matching is the only watermark deletion path), so a room that returns
  after being capped out does **not** resurface old mentions as unread.
- This is not Desktop `$groupNeedsYou` parity: the approval-blocked trigger never
  reaches the mirror, and Desktop's own clear paths are local atoms.

## Outputs

### Rooms list
Name, member initials/avatar, last message preview, relative time; sorted by most
recent activity. Header note: "Desktop 미러 기준 · 방당 최대 16개". Cached rooms
missing from the current mirror are shown distinctly ("현재 미러에 없음"). Boolean
`@user` unread indicator; gap marker where applicable.

### Room detail
User vs member bubbles from `from.kind`; `from.name`, optional `from.source`,
optional thread separators. Only the bounded transcript in the current snapshot is
shown — no unbounded history accumulation.

## Phase boundary

- **Phase 1 (this SPEC)**: Android read-only list + detail over `profiles.list`.
- **Phase 2 (separate SPEC)**: write path via `profiles.configure` CAS with
  `ui_meta: {"hermes-bots-groups": <snapshot>}`; appending
  `{"from":{"kind":"user","name":…}, …}` (nested shape) to a room's log is the only
  permitted write. Rounds run only while the Desktop is up: writes made while it is
  offline execute late — that UX expectation is specified there, not here.

## Non-goals (Phase 1)

Sending/creating/renaming rooms, member edits · `profiles.configure` and CAS write ·
approval-blocked detection · exact unread count or full history · notifications /
watcher / ntfy · any Hermes backend contract change · standalone Python parser as a
prerequisite (parser/watcher stay deferred backlog).

## Acceptance

1. Room data source is WS `profiles.list({"include_sessions":false})`; REST
   `getProfiles()` is not used for room data.
2. Parser/cache tests pass against the fixture set pinned from
   `HaeinGeek/asgard-rooms` PR #4 (head `eae33c7d`): **9 snapshot fixtures** —
   `v3-normal.json`, `v3-malformed.json`,
   `v3-capped-room-evicted/{02-before,03-after,04-gamma-returns}.json`,
   `legacy-name-key.json`, `at-normalization.json`, `legacy-v1.json`, and
   `legacy-v2.json` — asserted against `EXPECTED.json` and
   `EXPECTED-cache-walk.json` (Desktop-derived oracle). `fixture-set.sha256`
   pins every fixture byte-for-byte; `./gradlew checkFixtureParity` (part of
   `check`) fails the build on drift, and the pin is regenerated only from a
   reviewed asgard-rooms head, never by hand. Gateway-size provenance is
   a separate generated evidence artifact, not a parser fixture.
3. v3 nested `from`, optional entry/member fields, `revision`, and mixed tombstones
   parse without crashing; **normalization keeps v3 rooms verbatim — a room whose
   `log` is missing or not an array is retained as-is, not dropped** (pinned by
   `v3-malformed.json`); pre-v3 normalization drops name-keyed rooms without an
   array `log`. Malformed `at` normalizes deterministically (missing, null,
   non-numeric, boolean `false`, negative, non-finite, and at-or-above the
   IEEE-754 `2^63` boundary → `0L`; boolean `true` → `1L`; JavaScript numeric
   strings such as `"0x10"` parse; in-range decimals keep their nearest-double
   value, e.g. `2^53+1` → `9007199254740992`).
4. Snapshot-absent rooms survive in cache; only matching tombstones delete them;
   stale tombstones are dropped.
5. Only member `@user` mentions badge; user/system entries, pre-window entries, and
   truncated/no-mention slices do not; room open clears the badge; eviction-return
   does not resurrect old mentions.
6. Drawer entry opens Rooms list and detail; cached-vs-current-mirror states are
   distinguishable.
7. Polling stops off-lifecycle and never overlaps requests.
8. Live verification compares the same-instant `profiles.list` projection with the
   UI's current rooms and last messages; rooms present only in cache display in the
   separate cached state. No hard-coded room names anywhere.
9. `./gradlew assembleDebug testDebugUnitTest` passes.

## Build prerequisites (Linux pod)

JDK 21 + Android cmdline-tools under `~/android-sdk`
(`platforms;android-37.0`, `build-tools;37.0.0`); Gradle wrapper 9.7.0 verified
working. On the Linux pod, `./gradlew testDebugUnitTest ktlintCheck assembleDebug`
passes with this toolchain — mac hand-off is no longer required.
