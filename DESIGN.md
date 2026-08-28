# Design

## Source of truth

- **Status:** Draft — implementation must wait for approval of the navigation
  recommendation and the open sequencing questions.
- **Last updated:** 2026-08-28.
- **Product surfaces:** Android phone and adaptive Android layouts for Chat,
  Kanban, Bots, Files, and secondary management tools.
- This document is the product and UI contract for the first focused mobile UX
  refresh: primary navigation, Chat information architecture, bot selection, and
  Files legibility/search.
- Product framing comes from `README.md`: Hermes Mobile is a native Android
  companion that gives pocket control over a Hermes gateway through chat,
  automation, configuration, and operations.
- Existing implementation constraints come from `AGENTS.md`, `Navigation.kt`,
  `NavigationController.kt`, `ScreenRegistry.kt`, `ui/common/HermesScaffold.kt`,
  `ui/sessions/`, `ui/chat/`, `ui/profiles/`, and `ui/files/`.
- Room behavior and limitations come from `docs/room-view-contract.md`. Rooms are
  a bounded, read-only Desktop mirror in phase 1, not ordinary Hermes sessions.
- Navigation rationale follows Android's Material guidance: a
  [navigation bar](https://developer.android.com/develop/ui/compose/components/navigation-bar)
  is appropriate for three to five equal-priority destinations on compact
  windows, while
  [adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
  uses a rail on larger windows.
- This file separates user-requested goals from design recommendations. The five
  destination structure is requested; the bottom navigation bar is recommended
  over a revised drawer and remains subject to product approval. Unresolved
  decisions are listed under Open questions and must not be guessed during
  implementation.

## Brand

Hermes Mobile should feel like a calm, capable control surface for an AI agent:
fast enough for daily chat, trustworthy enough for operations, and compact without
looking like a desktop admin console squeezed onto a phone.

The app is not a Discord clone. It should borrow Discord's strengths—clear
recency, obvious conversation identity, predictable navigation, and strong empty
states—while retaining Hermes-specific workflows such as bot configuration,
Kanban, files, and gateway status.

## Product goals

### Phase 1 design target

Phase 1 is a focused redesign of the mobile core loop, not a full-app visual
rewrite. The target agreed for discussion is:

- Primary destinations: `Chat · Kanban · Bots · Files · More`.
- Chat owns both session history and Rooms. It opens on history by default; `+`
  starts a new chat.
- Chat exposes bot selection without reassigning an existing session to a different
  bot.
- Files improves scanability and adds search.
- Terminal support is excluded.
- Navigation presentation—bottom tabs versus an improved drawer—must be decided
  from a draft before implementation. This document recommends bottom tabs.

Discussion record, 2026-08-28:

| Topic | User direction | Design interpretation | Status |
| --- | --- | --- | --- |
| Primary navigation | Chat, Kanban, Bots, Files, More | Five equal-priority top-level destinations | Requested |
| Chat organization | Include Rooms and History; show history first; use `+` for new chat | Chat Home with `Chats / Rooms`, recent sessions as default, and a new-chat action | Requested; detailed interaction proposed |
| Navigation control | Compare tabs with an improved drawer before proceeding | Bottom bar on phones, rail on larger screens, secondary tools in More | Recommended; approval pending |
| Bot selection | Bot-selection tabs in chat sessions | Visible bot selector; switching bots starts a fresh scoped session rather than mutating the current one | Requested; safety behavior derived from current data model |
| Files | Improve visibility and add search | Clearer rows/breadcrumbs plus current-folder filtering in phase 1 | Requested; search scope constrained by current API |

### Goals

1. Put the five daily destinations—Chat, Kanban, Bots, Files, More—one tap away.
2. Make Chat open on a useful conversation index instead of an empty composer.
3. Combine session history and read-only Rooms under one Chat mental model without
   implying that they share the same storage or write capabilities.
4. Make the active bot visible and switching behavior predictable.
5. Make files scannable on a phone and searchable within the current directory.
6. Preserve access to every existing management screen through More.
7. Improve the core loop without redesigning all existing screens or adding a
   terminal.

Initial success criteria:

- Any primary destination is reachable with one tap from a top-level screen.
- A recent session can be resumed in at most two taps after app launch.
- A new chat with a chosen bot can be started in at most three taps.
- Current-directory file search is available immediately on the Files screen.
- No existing management screen becomes unreachable.
- Existing profile, session, and room semantics remain intact.

### Non-goals

- Terminal implementation.
- A visual redesign of every existing management screen.
- Writable Rooms or changes to the phase-1 Room mirror contract.
- Recursive/global file search without an explicit backend API.
- A Kanban mobile-layout redesign in the same implementation slice.

## Personas and jobs

### Daily operator

- Resume the last conversation quickly.
- Check whether a bot or room needs attention.
- Inspect a task board or retrieve a generated file while away from a computer.

### Bot maintainer

- See which bot/profile is active.
- Switch to another bot without accidentally mixing session context.
- Open bot configuration when deeper changes are needed.

### System maintainer

- Reach logs, processes, providers, skills, keys, and settings when diagnosing a
  problem.
- Accept that these lower-frequency tools live behind More in exchange for a
  simpler daily interface.

## Information architecture

### App shell and More

The **app shell** is not a separate destination or user feature. It is the common
outer frame around the five top-level screens. It owns:

- the bottom navigation bar on phones and navigation rail on larger layouts;
- which top-level destination is selected;
- top-level back-stack and per-destination state restoration;
- system insets and placement of shared connection status;
- showing primary navigation on top-level screens and hiding it on drill-down
  screens such as chat detail, room detail, and editors.

`HermesScaffold` remains the inner screen scaffold that owns each screen's app bar,
refresh behavior, snackbar, and content. The app shell sits outside it; it is not a
replacement screen and must not create nested scaffolds.

**More** is the fifth top-level destination rendered inside that shell. It is a
normal searchable screen containing the existing lower-frequency destinations now
listed in the drawer. It is not a hamburger menu, a generic overflow menu for the
current screen, or a place where features disappear. Every moved destination must
remain named, searchable, and directly navigable.

### Navigation decision

Use a persistent Material 3 navigation bar on compact phone layouts with exactly
five destinations:

1. **Chat** — session history by default, Rooms as a peer view, and new-chat entry.
2. **Kanban** — existing task board.
3. **Bots** — the existing Profiles capability, relabeled in the UI.
4. **Files** — file browser, current-directory search, upload, and folder actions.
5. **More** — all lower-frequency automation, configuration, inspection, and
   settings screens.

On medium and expanded widths, replace the bottom bar with a navigation rail. Do
not retain a second global drawer containing the same five destinations.

| Option | Strengths | Costs | Decision |
| --- | --- | --- | --- |
| Five-item bottom bar | Daily destinations stay visible; matches Android guidance; no hamburger discovery cost; strong one-handed reach | Requires a shared app shell and explicit top-level back-stack behavior | **Recommended** |
| Improved global drawer | Smaller initial code change; can hold many destinations directly | Daily destinations remain hidden; long list and gesture state stay complex; weak current-location awareness | Rejected as the primary phone navigation |

The existing drawer's grouping remains useful, but its content moves into More as
a normal searchable screen. This removes the long scrolling drawer without
discarding its taxonomy.

### Chat

Chat opens to **Chat Home**, not directly to an empty conversation. Chat Home has:

- A horizontally scrollable bot selector with one item per bot/profile.
- A two-way segmented control: `Chats` and `Rooms`.
- `Chats` selected by default, showing recent session history with existing search,
  pin, rename, branch, and delete capabilities available through progressive
  disclosure.
- `Rooms` showing the bounded read-only mirror defined by
  `docs/room-view-contract.md`, including unread, stale-cache, and history-gap
  indicators.
- A `+` floating action button for a new chat. It opens a bottom sheet with the
  selected bot, optional first-message affordance, and a clear Start action.

The bot row is both a selector and a scope indicator:

- On Chat Home, selecting a bot uses the existing atomic profile-switch flow,
  refreshes Chats in that bot/profile scope, and makes it the default in the
  new-chat sheet. The row does not imply an unsupported cross-profile `All` view.
- In a chat detail screen, the app bar shows the bot identity as a tappable chip.
  Choosing another bot performs the existing atomic profile-switch flow and starts
  a fresh session. It never changes the owner or profile of the open session.
- The switch confirmation must say that a new session will start when the current
  chat contains messages. An empty fresh session may switch without confirmation.

Rooms remain separate from bot filters unless the room contract later provides a
stable bot/profile association. The bot row is hidden or disabled while `Rooms` is
selected rather than applying a misleading filter.

Phone sketch:

```text
┌──────────────────────────────┐
│ Chat                    ●    │
│ [Odin] [Norn] [Loki] →       │
│ [ Chats ] [ Rooms ]           │
│ Search conversations          │
│                              │
│ Recent                       │
│ Odin   Release review    2m  │
│ Norn   Search indexing  18m  │
│ ...                          │
│                         (+)  │
├──────────────────────────────┤
│ Chat Kanban Bots Files More  │
└──────────────────────────────┘
```

### Kanban

Keep the existing Kanban feature and data behavior in this slice. The navigation
change makes it a first-class destination. A later Kanban-specific mobile pass may
replace wide horizontal columns with a status-filtered vertical list, but that is
not required for this implementation.

### Bots

`Bots` is the user-facing label for the existing server-side Hermes profile model.
Internal route names, API names, and persistence keys remain `Profiles` unless a
separate migration is justified.

The top-level Bots screen reuses and progressively simplifies `ProfilesScreen`:

- Bot identity, description, model/provider, and active state are visible first.
- Selecting `Chat` on a bot opens a new chat using the same atomic profile switch.
- Rename, clone, soul, model, setup command, and delete remain secondary actions.
- Creating a bot continues to use the existing profile builder.

### Files

Files retains the current managed-files contract and gains a clearer hierarchy:

- A sticky search field directly below the app bar.
- Breadcrumbs rendered as a horizontally scrollable path rather than compressed
  text.
- Folders before files, then locale-aware alphabetical ordering within each group.
- Denser list rows with a consistent file-type icon, prominent filename, and
  secondary metadata (`type · size · modified`).
- Row overflow menus for destructive or secondary actions; delete is not a bright
  inline icon on every row.
- Upload and New folder actions grouped in the app bar or a single action sheet.

Phase 1 search is an immediate, case-insensitive filter over entries already loaded
for the current folder. It matches filename first and may also match MIME type.
Search does not silently traverse subdirectories: the current API exposes only
`GET /api/files?path=...`, so recursive server-wide search would require repeated
network traversal or a new backend endpoint. A future global search must have an
explicit backend contract, cancellation, result limits, and path-bearing results.

```text
┌──────────────────────────────┐
│ Files                 ⋮      │
│ Search this folder           │
│ Home / projects / asgard     │
│                              │
│ 📁 docs                  ›   │
│ 📁 fixtures              ›   │
│ 📄 DESIGN.md      MD · 8 KB ⋮│
│ 📄 README.md      MD · 6 KB ⋮│
├──────────────────────────────┤
│ Chat Kanban Bots Files More  │
└──────────────────────────────┘
```

### More

More is a full screen, not a drawer. It starts with `Find a tool` search and retains
the existing categories:

- **Automate:** Cron, Webhooks.
- **Configure:** Skills, Toolsets, Plugins, Config, MCP, Memory, Models, Pairing,
  Keys, Channels, Providers.
- **Inspect:** Gateway, System, Logs, Processes, Analytics, Billing, Achievements.
- **App:** Settings and connection management.

Frequently used or recently opened tools may appear in a short row above the
groups after usage evidence exists. Do not invent favorites or reorder tools based
on assumptions in the first release.

### Navigation behavior

- The primary navigation component is visible only on the five top-level screens.
- Chat detail, room detail, builders, editors, and settings subpages are drill-down
  screens with a back arrow and no bottom bar.
- Selecting the current top-level item scrolls its primary list to top. A second tap
  must not create a duplicate route.
- Selecting another top-level item preserves that destination's list/scroll state
  during the current process lifetime. Returning to Chat Home must not create an
  empty session.
- Android system Back closes sheets/search/detail before changing a top-level
  destination. At a top-level root, Back follows normal Android app-exit behavior;
  it does not redirect Chat to a separate History screen.

## Design principles

1. **Recency before configuration.** The first screen answers “where was I?”
   before offering settings.
2. **Identity is never implicit.** Bot, server connection, room, and session scope
   are visible where a mistaken action would be costly.
3. **Frequent actions stay visible.** Daily destinations and New chat do not hide
   behind a hamburger or overflow menu.
4. **Capability follows frequency.** Advanced management stays available through
   More without competing with the core loop.
5. **Honest boundaries.** Read-only Rooms, cached mirror gaps, and folder-scoped
   search are labeled rather than presented as complete data.
6. **Progressive disclosure over dense dashboards.** Show identity and status
   first; put maintenance actions in contextual menus or detail screens.
7. **Reuse before replacement.** Extend existing screens, ViewModels, theme tokens,
   and routes rather than creating parallel versions.

## Visual language

- Continue using Material 3 and all existing theme presets, including dynamic
  color. No new color system is introduced in this slice.
- Use `MaterialTheme.colorScheme` for surfaces and content and
  `LocalHermesStatusColors` only for semantic success/warning/error states.
- Prefer low-elevation tonal surfaces and spacing over card borders around every
  item. Conversation and file lists should read as continuous lists, not a grid of
  floating admin cards.
- Keep typography roles semantic: `titleLarge`/`titleMedium` for screen and item
  identity, `bodyMedium` for primary content, and `bodySmall`/`labelMedium` for
  metadata.
- Use existing `LocalSpacing` tokens. New one-off spacing values require a reason
  in code review.
- Motion should clarify navigation or state changes: short crossfade between Chats
  and Rooms, sheet transition for new chat/bot selection, and no decorative motion.

## Components

### AppNavigationShell

Owns the adaptive navigation bar/rail, selected destination, top-level state
restoration, connection indicator, and Navigation3 integration. It replaces the
global `ModalNavigationDrawer`; it does not replace `HermesScaffold`.

### TopLevelDestination

A stable definition for Chat, Kanban, Bots, Files, and More containing route, label,
icon, and selection rules. It must be distinct from the registry of every routable
screen.

### ChatHomeScreen

Composes existing session-history behavior with the Rooms list. Prefer extracting
reusable session list content from `SessionsScreen` over nesting one scaffold inside
another or cloning the screen.

### BotFilterRow and BotPickerSheet

Use the profile list as their data source. The row supports browsing/filtering; the
sheet explains that switching bots starts a fresh session and routes all mutations
through `ProfileSwitchCoordinator`.

### ConversationRow

Shows bot identity, title/preview, source, relative recency, pin/unread state, and a
context menu. It should keep the session tree's parent/branch information without
making every row visually heavy.

### RoomRow

Implements exactly the visual data and warnings in `docs/room-view-contract.md`.
It must distinguish current mirror data from retained cache data.

### FileSearchBar and FileRow

The search bar exposes folder scope in its placeholder or supporting text. FileRow
shows file identity and metadata with an overflow action. Both are reusable for a
future backend search result screen.

### MoreScreen

Renders the existing drawer taxonomy as searchable grouped rows. Search matches
localized title and stable feature keywords; it navigates to the existing route.

## Accessibility

- Every navigation destination has an always-visible text label and a selected
  state announced to TalkBack.
- Touch targets are at least 48 dp. Compact visual rows may use invisible padding
  to maintain the target.
- Do not communicate connection, unread, stale mirror, selected bot, or file type
  by color alone; pair color with text, icon, shape, or semantics.
- Bot tabs and segmented controls expose role, selected state, and a useful content
  description. Horizontal rows must remain keyboard/rotary navigable.
- Support system font scaling without clipping the bottom navigation labels or
  hiding file metadata required to distinguish results.
- Search provides clear, loading, empty-result, and error announcements.
- Relative times have absolute timestamps in accessibility text.
- Respect system reduced-motion settings where Compose APIs expose them.

## Responsive behavior

- **Compact portrait/landscape:** bottom navigation bar with five labeled items.
- **Medium/expanded:** navigation rail with the same five destinations; list-detail
  may show Chat Home plus chat detail or Files list plus preview when implemented.
- **Very wide/desktop-like windows:** a permanent navigation drawer is allowed as
  an adaptive presentation of the same five destinations, not as a list of every
  tool.
- The More screen remains the home for secondary tools at every width.
- When the software keyboard opens in chat, keep composer content visible. The
  bottom navigation may hide only on chat detail, where it is already outside the
  top-level shell.

## Interaction states

### App shell

- **Connected:** small status indicator is visible but not dominant.
- **Connecting/reconnecting:** persistent non-blocking status with detail on tap.
- **Disconnected/auth expired:** explicit banner and recovery action; primary
  navigation remains usable for cached content where supported.

### Chat Home

- **Loading:** skeleton conversation rows; filters remain stable.
- **Empty:** “No conversations yet” with New chat action.
- **Search empty:** preserve query and selected bot; offer clear-search action.
- **Bot switch in progress:** block duplicate switches and show the target identity.
- **Bot switch failed:** keep the current bot/session and present retryable feedback.
- **Rooms unavailable/ambiguous/stale:** use the exact room contract states rather
  than an empty generic list.

### Chat detail

- **Fresh:** selected bot visible before the first message.
- **Streaming:** preserve current cancellation and tool-call behavior.
- **Switch requested with content:** confirmation explains that a fresh session
  will open under the chosen bot.
- **Offline:** composer disabled or queues only if an explicit queueing contract is
  later added; never imply a message was sent.

### Files

- **Loading initial folder:** skeleton rows.
- **Refreshing:** preserve current list and search text.
- **Searching:** filter locally with no network spinner.
- **No match:** “No files in this folder match …” and clear-search action.
- **Empty folder:** keep New folder and Upload available.
- **Operation busy:** disable only the affected row/action when possible.
- **Error:** retain breadcrumbs and last successful entries when safe, with retry.

## Content voice

- Use short, direct labels: Chat, Kanban, Bots, Files, More.
- Use **Bots** in user-facing navigation and explanatory copy; use “profile” only
  when exposing a server/API concept or in advanced configuration help.
- Use **Chats** for Hermes session history and **Rooms** for Desktop Bot Mode rooms.
- Avoid calling the Rooms mirror “full history.” State its limits plainly:
  “Desktop mirror · up to 16 messages per room.”
- Search placeholders state scope: “Search this folder,” not “Search files,” until
  global search exists.
- Destructive confirmations name the object and consequence.
- All new strings must be resources and localized consistently with the existing
  app; do not embed production copy in composables.

## Implementation constraints

- Continue using Navigation3. All route changes go through
  `NavigationController`; UI callbacks must not edit the back stack directly.
- Introduce explicit top-level navigation semantics instead of overloading the
  current `primaryScreens` drawer behavior. The controller must deduplicate tab
  selection and support state restoration.
- Keep `HermesScaffold` as the common screen scaffold. Extend it with an optional
  bottom/navigation-shell integration point if needed; do not nest scaffolds.
- Remove the global drawer only after every current `ScreenRegistry` entry is
  reachable from either a top-level destination or More.
- Reuse `SessionsViewModel`, `ProfilesViewModel`, `FilesViewModel`, shared search and
  state components, and theme tokens. Extract content composables where the current
  screen boundary prevents reuse.
- Bot selection must use `ProfileSwitchCoordinator.switchProfile`. It performs a
  server profile change, clears chat state, reconnects WebSocket, and creates a
  fresh session; UI must not simulate a local-only bot switch.
- Keep connection profiles (different gateway hosts) distinct from Bots (server
  profiles on one gateway).
- Room UI work depends on the parser/cache branch described by
  `docs/room-view-contract.md`. Merge or rebase that work before wiring Rooms into
  Chat Home; do not duplicate its parser or cache.
- Files phase 1 search is local to `FilesUiState.entries`. Do not implement recursive
  network crawling against `listManagedFiles` as a hidden approximation of global
  search.
- Preserve all six themes, dynamic color, minSdk 26, targetSdk 37, and existing
  authenticated file operations.
- Add or update tests for top-level route deduplication/back behavior, tab state
  restoration, bot-switch fresh-session semantics, Chat/Rooms mode state, file
  filtering/sorting, and More reachability. Add Compose UI screenshots or golden
  references for the five primary screens before broad visual polish.

Suggested implementation slices:

1. **Shell:** `AppNavigationShell`, five destinations, More, route reachability,
   and back-stack tests.
2. **Chat Home:** extract session list, default history view, new-chat sheet, and
   bot filter/switch behavior.
3. **Rooms:** integrate list/detail after the room parser/cache work lands.
4. **Files:** local search, sorting, row hierarchy, breadcrumbs, and action menus.
5. **Polish:** accessibility pass, adaptive rail, screenshots, and device QA.

## Open questions

- [ ] Approve the recommended phone navigation: five-item bottom bar instead of an
  improved global drawer. Owner: product. Impact: app-shell architecture and the
  first implementation slice.
- [ ] Confirm whether the first implementation should ship Chat Home without Rooms
  while the room parser/cache PR is still pending, or wait and ship both together.
  Owner: product. Impact: sequencing and PR size.
- [ ] Confirm the switch warning policy: warn only when the current session contains
  messages (recommended), or on every bot switch. Owner: product. Impact: friction
  and accidental context loss.
- [ ] Decide whether a later global Files search warrants a backend endpoint.
  Owner: Hermes backend/mobile. Impact: recursive scope, latency, and API contract.
- [ ] Decide whether Kanban's phone layout gets a vertical status-list redesign in
  the next UX slice. Owner: product. Impact: scope beyond navigation.
- [ ] Validate the English label `Bots` in every supported locale while retaining
  `Profiles` in advanced/server-facing copy. Owner: localization/product. Impact:
  terminology only; no data migration.
