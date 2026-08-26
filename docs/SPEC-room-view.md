# SPEC: Bot Mode room view for hermes-mobile

## Goal
Show Hermes Desktop *Bot Mode* group chats (rooms) in the Android app, read-only first, so the user can follow multi-bot conversations from a phone. Writing into rooms is phase 2.

## Background (verified against hermes-agent v0.20.5 source)
- Rooms are orchestrated by the Desktop plugin `apps/desktop/src/plugins/hermes-bots/plugin.js`. The gateway does not run rounds.
- Desktop mirrors every room into the **default profile's** `ui_meta` under key `hermes-bots-groups` (see `GROUP_CHAT_SYNC_META_KEY`, `groupChatSyncSnapshot`). Size-capped at 48 KB; newest rooms/messages win; 16 messages per room, 1200 chars per message.
- Snapshot schema (v3):
  ```json
  {"version": 3, "updatedAt": 1787731411061,
   "rooms": {"id:rmt9ltszp-d0qr0": {"name": "review", "roomId": "rmt9ltszp-d0qr0",
       "members": [{"name": "brokk", "handle": "brokk"}, ...],
       "log": [{"kind": "user"|"member", "name": "loki", "text": "...", "at": 1787731411061, "thread": "tmt9syjit-9wmm2", "source": "<connection label, remote members only>"}, ...],
       "needsYou": true|false }},
   "deleted": {"id:...": <revision>}}
  ```
  Treat unknown fields as optional; `rooms` keys are `id:<roomId>` (older: `name:<name>`).
- Read path: `GET /api/profiles` (dashboard REST, authenticated) returns profiles with `ui_meta` and `ui_meta_revisions`. Pick the profile named `default` (or the one carrying the key). Live updates: poll every 10–15 s while the Rooms screen is visible; WS push is optional.
- Phase 2 write path: `profiles.configure` RPC with `ui_meta: {"hermes-bots-groups": <snapshot>}` and `ui_meta_expected_revisions` (CAS). Desktop merges remote snapshots (`mergeRemoteGroupChatSnapshotIntoRooms`) and runs the rounds while it is up. Appending a `{"kind":"user", ...}` entry to `rooms[id].log` is the only write; never touch other rooms' entries.

## Phase 1 — read-only (this PR)
1. New bottom-nav/drawer entry **Rooms**.
2. Rooms list: name, member avatars/initials, last message preview, `needs you` badge, relative time. Sort by last activity.
3. Room detail: chat-style transcript from `log` (user vs member bubbles, member name, device badge from `source` when present, thread separators optional). Show "mirror: last 16 messages" hint.
4. Data layer: `RoomsRepository` reading `GET /api/profiles`, parsing the snapshot defensively (version < 3 → normalize like `normalizeGroupChatSyncSnapshot`). Cache last snapshot locally for offline display.
5. Polling with lifecycle awareness; manual pull-to-refresh.
6. Empty state when no snapshot / no rooms ("Rooms appear once Hermes Desktop has synced a group chat").
7. Unit tests: snapshot parser (v3 sample above, v2/v1 fallback, size-capped/partial rooms), sorting, needsYou.

## Non-goals (phase 1)
- Sending messages, creating rooms, editing members.
- Notifications for room activity (later: local notification when `needsYou` flips).

## Acceptance
- `./gradlew assembleDebug testDebugUnitTest` passes.
- With the pod dashboard connected, the Rooms tab lists `intake / review / research / TP report` and opens the review transcript matching Desktop.
- No regression in existing screens; new code follows the project's existing Compose + repository patterns (see `data/session/ProfileSwitchCoordinator.kt` for the profile-scoped request style).

## Build prerequisites on the pod
JDK 21 + Android cmdline-tools/SDK (API 35) under `~/android-sdk`, no root needed. If unavailable, hand the build/verify step to `@user` on the mac.
