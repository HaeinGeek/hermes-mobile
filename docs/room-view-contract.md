# SPEC: Bot Mode room view for hermes-mobile (rev 2)

Rev 2 supersedes the initial SPEC (`6171b5c` in hermes-mobile). Every claim below is
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
- Limits: **at most 16 messages per room** (fewer or zero possible when the 48 KB
  cap bites), **1200 chars per message** (`GROUP_CHAT_SYNC_TEXT_CHARS`), whole rooms
  may be evicted by the cap (quietest first) — the room list is a *Desktop-mirror
  window*, not the full room set. Rooms with empty logs are not projected.
- Non-ASCII costs ~2× its UTF-8 size against the cap (gateway counts 6 bytes per
  BMP codepoint), so Korean-heavy mirrors rotate faster than English ones; live
  measurement showed a sub-hour window.

## Normalization and identity

- `version < 3`: name-keyed rooms are lifted to `name:<name>` per
  `normalizeGroupChatSyncSnapshot`; v1 tombstones are wall-clock ms and are clamped
  to revision 0 so they can never outrank a real revision and mass-delete the cache.
- Room identity: `id:<roomId>` when present, else original `name:` key.
- `entry.at` normalization: numeric coercion of missing/null/non-numeric to `0`
  (Kotlin safe conversion replicating `Number(entry.at || 0)`), **negative values
  clamp to `0`** — a deliberate divergence from Desktop, making `at >= 0` a single
  invariant for sorting and watermark comparison. Numeric strings parse; floats
  truncate to Long; display order is normalized `at`, ties broken by log array order.
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
- Opening a room updates its `lastOpenedAt` and clears the badge.
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
2. Parser/cache tests pass against the fixture set in
   `HaeinGeek/asgard-rooms@feat/parser-fixtures` (PR #2): **7 fixtures** —
   `v3-normal.json`, `v3-capped-room-evicted/{02-before,03-after,04-gamma-returns}.json`,
   `legacy-name-key.json`, `legacy-at-only.json`→`at-normalization.json`,
   `legacy-v1.json`, `legacy-v2.json` — asserted against `EXPECTED.json` and
   `EXPECTED-cache-walk.json` (Desktop-derived oracle).
3. v3 nested `from`, all optional fields, `revision`, and mixed tombstones parse
   without crashing; malformed `at` normalizes deterministically (non-numeric and
   negative → `0L`).
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

JDK 17 (Temurin, sdkman) + Android cmdline-tools under `~/android-sdk`
(platforms;android-36, build-tools;36.0.0); Gradle wrapper 9.7.0 verified working.
`assembleDebug` builds green on the pod — mac hand-off is no longer required.
