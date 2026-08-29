# Design

## Source of truth

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
  The room parser, cache, and repository this contract relies on are already
  part of current `main` (merged PR #2); future Rooms UI reuses them without
  duplication.
- Navigation rationale follows Android's Material guidance: a
  [navigation bar](https://developer.android.com/develop/ui/compose/components/navigation-bar)
  is appropriate for three to five equal-priority destinations on compact
  windows, while
  [adaptive navigation](https://developer.android.com/develop/ui/adaptive-apps/guides/build-adaptive-navigation)
  uses a rail on larger windows.
- This file records a settled design contract. The adaptive five-destination
  bottom bar/rail is settled. Deferred work is named explicitly under Non-goals
  and must not be reopened during implementation.

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
rewrite. The settled Phase 1 design contract is:

- Primary destinations: `Chat · Kanban · Bots · Files · More`.
- Compact windows use a five-item bottom bar; medium and expanded windows use
  the same five destinations in a rail.
- Chat owns both session history and Rooms. Chat Home ships with `Chats`
  selected by default and `Rooms` as a peer mode in the same future slice; `+`
  starts a new chat.
- Chat exposes bot selection without reassigning an existing session to a different
  bot.
- Files improves scanability and adds current-directory filename search.
- Terminal support is excluded.

Discussion record, 2026-08-28:

| Topic | User direction | Design interpretation | Status |
| --- | --- | --- | --- |
| Primary navigation | Chat, Kanban, Bots, Files, More | Five equal-priority top-level destinations | Settled |
| Chat organization | Include Rooms and History; show history first; use `+` for new chat | Chat Home with `Chats / Rooms`, recent sessions as default, Rooms as a peer mode shipped in the same slice, and a new-chat action | Settled |
| Navigation control | Compare tabs with an improved drawer before proceeding | Comparison closed: bottom bar on compact windows, rail on medium/expanded windows, secondary tools in More; no drawer alternative in this phase | Settled |
| Bot selection | Bot-selection tabs in chat sessions | Visible bot selector; switching bots starts a fresh scoped session rather than mutating the current one; the switch warning applies only to a non-empty current session | Settled |
| Files | Improve visibility and add search | Clearer rows/breadcrumbs plus current-directory, filename-only, case-insensitive local filtering in phase 1; global backend search deferred | Settled |

### Goals

1. Put the five daily destinations—Chat, Kanban, Bots, Files, More—one tap away.
2. Make Chat open on a useful conversation index instead of an empty composer.
3. Combine session history and read-only Rooms under one Chat mental model without
   implying that they share the same storage or write capabilities.
4. Make the active bot visible and switching behavior predictable.
5. Make files scannable on a phone and searchable by filename within the current
   directory.
6. Preserve access to every existing management screen through More.
7. Improve the core loop without redesigning all existing screens or adding a
   terminal.

Success criteria:

- Any primary destination is reachable with one tap from a top-level screen.
- A recent session can be resumed in at most two taps after app launch.
- A new chat with the currently selected bot can be started in at most three
  taps.
- A new chat with a different bot can be started in at most four taps, including
  the explicit bot-selection tap.
- These tap bounds exclude scrolling or searching to discover an off-screen bot.
- Current-directory file search is available immediately on the Files screen.
- No existing management screen becomes unreachable.
- Existing profile, session, and room semantics remain intact.

### Non-goals

Deliberately deferred work; none of it is an open choice for implementation.

- Terminal implementation.
- A visual redesign of every existing management screen.
- An improved, global, or permanent navigation drawer as a competing
  primary-navigation presentation in this phase.
- Writable Rooms, full room history, room notifications, room backend work, or a
  duplicate parser/cache; the phase-1 Room mirror contract is unchanged.
- Recursive or global file search, directory crawling, indexing, MIME-based
  search, or a new backend endpoint in phase 1.
- A Kanban content, data, or layout redesign; Kanban changes are limited to
  top-level shell placement and insets.
- Renaming the internal, API, or storage `Profiles` concept; `Bots` is the
  user-facing term only.

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

Before the drawer is retired, the set of routes reachable from the five top-level
destinations and More must equal the current `ScreenRegistry.ALL_SCREENS` key set
exactly. At the bound `main` baseline this inventory is 26 `DrawerSection`
routes; implementation freezes the exact baseline keys and proves set equality,
not merely a count.

### Navigation decision

Use a persistent Material 3 navigation bar on compact phone layouts with exactly
five destinations:

1. **Chat** — session history by default, Rooms as a peer view, and new-chat entry.
2. **Kanban** — existing task board.
3. **Bots** — the existing Profiles capability, relabeled in the UI.
4. **Files** — file browser, current-directory search, upload, and folder actions.
5. **More** — all lower-frequency automation, configuration, inspection, and
   settings screens.

On medium and expanded widths, replace the bottom bar with a navigation rail
carrying the same five destinations. Only one primary-navigation presentation is
active at any window size; the app does not keep a second global surface for the
same five destinations.

| Option | Strengths | Costs | Decision |
| --- | --- | --- | --- |
| Five-item bottom bar | Daily destinations stay visible; matches Android guidance; no hamburger discovery cost; strong one-handed reach | Requires a shared app shell and explicit top-level back-stack behavior | **Final for this phase** |
| Improved global drawer | Smaller initial code change; can hold many destinations directly | Daily destinations remain hidden; long list and gesture state stay complex; weak current-location awareness | Rejected — recorded as rationale only, not a live alternative |

The existing drawer's grouping remains useful, but its content moves into More as
a normal searchable screen. This removes the long scrolling drawer without
discarding its taxonomy.

### Chat

Chat opens to **Chat Home**, not directly to an empty conversation. Authenticated
launch selects `Chats` and creates no unintended empty session. Chat Home has:

- A horizontally scrollable bot selector with one item per bot/profile.
- A two-way segmented control: `Chats` and `Rooms`.
- `Chats` selected by default, showing recent session history with existing search,
  pin, rename, branch, and delete capabilities available through progressive
  disclosure.
- `Rooms` showing the bounded read-only mirror defined by
  `docs/room-view-contract.md`. User copy must make the bounded mirror honest:
  up to 16 messages per room, 1,200-character message truncation, and a retained
  cache bounded to 200 records with eviction, alongside cached-missing, stale,
  unavailable, error, unread, and history-gap states.
- A `+` floating action button for a new chat. It opens a bottom sheet with the
  selected bot, optional first-message affordance, and a clear Start action.

Chat Home and Rooms ship together as one future slice; Rooms is not sequenced
behind any parser or cache premise. The room parser, cache, and repository are
inherited from current `main` (merged PR #2), and the Rooms UI reuses them
without duplication or weakening of the room contract.

The bot row is both a selector and a scope indicator:

- On Chat Home, selecting a bot uses the existing atomic profile-switch flow,
  refreshes Chats in that bot/profile scope, and makes it the default in the
  new-chat sheet. The row does not imply an unsupported cross-profile `All` view.
- In a chat detail screen, the app bar shows the bot identity as a tappable chip.
  Choosing another bot performs the existing atomic profile-switch flow and starts
  a fresh session. It never changes the owner or profile of the open session.
- The switch confirmation must say that a new session will start when the current
  chat contains messages. An empty fresh session may switch without confirmation.

Bot-switch rules are normative:

- Every bot change calls `ProfileSwitchCoordinator.switchProfile()`, starts a
  fresh target-bot session, never reassigns the open session's owner or profile,
  and blocks duplicate switches while one is in flight.
- Warn only when the current session has at least one displayed or persisted
  message. A draft-only composer is an empty session: switch without warning,
  clear the unsent draft, and never carry the draft to the target bot.
- A failed switch preserves the current bot, session, and draft, and offers
  retry.

Chats and Rooms sit on different scope axes:

- Chats are Hermes-profile scoped. The Rooms cache, list, and unread state are
  local-connection (`connectionId`) scoped and never bot-filtered.
- In `Rooms` mode the bot selector and the New chat FAB are hidden or disabled
  rather than applying a misleading filter.
- A bot switch performed from another surface does not invalidate, re-filter, or
  refresh the same-connection Rooms state; returning to `Chats` loads the
  selected profile scope.
- A connection switch selects and refreshes the new connection's cache partition
  and never presents the prior connection's entries as current.

Phone sketch:

```text
┌──────────────────────────────┐
│ Chat                    ●    │
│ [Bot A] [Bot B] [Bot C] →    │
│ [ Chats ] [ Rooms ]           │
│ Search conversations          │
│                              │
│ Recent                       │
│ Bot A  Release review    2m  │
│ Bot B  Search indexing  18m  │
│ ...                          │
│                         (+)  │
├──────────────────────────────┤
│ Chat Kanban Bots Files More  │
└──────────────────────────────┘
```

### Kanban

Keep the existing Kanban feature, content, data, and layout in this contract.
The navigation change makes it a first-class destination; beyond top-level shell
placement and insets, Kanban is unchanged. A Kanban-specific mobile redesign is
deliberately deferred (see Settled decisions and deferred work) and is not an
option within this contract.

### Bots

`Bots` is the user-facing label for the existing server-side Hermes profile model.
Internal route names, API names, and persistence keys remain `Profiles` unless a
separate migration is justified.

Top-level and core labels use the literal token `Bots` in the English, Korean,
and Chinese resources. `Profiles` or its localized equivalent is reserved for
advanced server, API, and storage explanation; it is not a competing top-level
label.

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

Phase 1 search is an immediate, case-insensitive filter over entries already
loaded for the current folder. It matches filename only; MIME type never
participates. Search makes no network call, never recurses or indexes, and clears
and recomputes results when the directory scope changes. The current API exposes
only `GET /api/files?path=...`, so recursive server-wide search would require
repeated network traversal or a new backend endpoint. Global search is deferred
and must not be promised in copy; a future global search requires an explicit
backend contract, cancellation, result limits, and path-bearing results.

Search copy says `Search this folder` and distinguishes loading, empty directory,
no match, refresh, and error states. Existing upload, create, open, delete, auth,
and path behavior remains intact.

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

More is searchable by localized label and stable feature keywords; every moved
route remains named and reachable.

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
- Support system font scaling to 200% without clipping the bottom navigation
  labels or hiding file metadata required to distinguish results.
- Layouts must work in right-to-left (RTL) locales without mirrored-only fixes.
- Search provides clear, loading, empty-result, and error announcements.
- Relative times have absolute timestamps in accessibility text.
- Respect system reduced-motion settings where Compose APIs expose them.

## Responsive behavior

- **Compact portrait/landscape:** bottom navigation bar with five labeled items.
- **Medium, expanded, and very-wide windows:** the same five destinations in a
  navigation rail for this phase; secondary tools remain in More.
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
- Use **Bots** in user-facing navigation and explanatory copy, including the
  literal token `Bots` in English, Korean, and Chinese top-level/core resources;
  use “profile” only when exposing a server/API concept or in advanced
  configuration help.
- Use **Chats** for Hermes session history and **Rooms** for Desktop Bot Mode rooms.
- Avoid calling the Rooms mirror “full history.” State its limits plainly:
  “Desktop mirror · up to 16 messages per room.”
- Search placeholders state scope: “Search this folder,” not “Search files.”
  Global search is deferred and must not be implied.
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
  reachable from either a top-level destination or More, proven by exact key-set
  equality with `ScreenRegistry.ALL_SCREENS`.
- Reuse `SessionsViewModel`, `ProfilesViewModel`, `FilesViewModel`, shared search and
  state components, and theme tokens. Extract content composables where the current
  screen boundary prevents reuse.
- Bot selection must use `ProfileSwitchCoordinator.switchProfile`. It performs a
  server profile change, clears chat state, reconnects WebSocket, and creates a
  fresh session; UI must not simulate a local-only bot switch.
- Keep connection profiles (different gateway hosts) distinct from Bots (server
  profiles on one gateway).
- Room parser/cache/repository is inherited from current `main` through PR #2.
  Rooms UI reuses it and must not duplicate or weaken
  `docs/room-view-contract.md`.
- Files phase 1 search is local to `FilesUiState.entries`. Do not implement recursive
  network crawling against `listManagedFiles` as a hidden approximation of global
  search.
- Preserve all six themes, dynamic color, minSdk 26, targetSdk 37, and existing
  authenticated file operations.
- Add or update tests for top-level route deduplication/back behavior, tab state
  restoration, bot-switch fresh-session semantics, Chat/Rooms mode state, file
  filtering/sorting, and More reachability. Add Compose UI screenshots or golden
  references for the five primary screens before broad visual polish.

Implementation slices:

1. **Shell + More:** `AppNavigationShell`, five destinations, searchable More,
   route reachability proven by exact key-set equality, Back/state behavior, and
   drawer retirement only after the reachability proof.
2. **Chat Home + Rooms + bot switching:** ship Chats default and Rooms peer mode
   together, reusing the inherited room data layer and the atomic profile-switch
   coordinator.
3. **Files:** filename-only local search, sorting, row hierarchy, breadcrumbs, and
   action menus.
4. **Adaptive/accessibility/device QA:** accessibility pass, adaptive rail
   verification, screenshots, and device QA against the acceptance criteria.

## Acceptance criteria for future implementation

These criteria are the verification oracles for the future implementation slices.
They are recorded here as contract; recording them does not claim any has been
executed.

- **AC-04 — Adaptive shell:** deterministic compact fixtures show exactly five
  labeled bottom destinations; medium/expanded fixtures show the same five in a
  rail; only one presentation is active and no global/permanent drawer remains.
- **AC-05 — Route reachability:** an enumerated test proves exact key-set equality
  between `ScreenRegistry.ALL_SCREENS` and top-level/More reachability, plus route
  de-duplication, top-level Back behavior, and per-destination in-process state.
- **AC-06 — Chat/Rooms launch:** launch opens Chats-default Chat Home without
  unintended session creation; Rooms ships as peer mode using deterministic
  current, cached-missing, unavailable/ambiguous, error, unread, and history-gap
  fixtures.
- **AC-07 — Scope-axis cross cases:** Rooms is not bot-filtered; Rooms
  selector/FAB behavior, external bot switch behavior, return-to-Chats behavior,
  and connection-switch cache partitioning match the profile-versus-connection
  rules in this document.
- **AC-08 — Bot switching:** empty, draft-only, at-least-one-message,
  duplicate-in-flight, success, and failure tests prove the warning predicate,
  draft handling, coordinator use, fresh target session, no ownership mutation,
  and failure preservation.
- **AC-09 — Interaction bounds:** instrumentation proves recent-session ≤2 taps,
  selected-bot new chat ≤3 taps, and different-bot new chat ≤4 taps under the
  stated preconditions.
- **AC-10 — Files:** tests prove case-insensitive filename-only current-directory
  matching, no MIME/network/recursion/index, correct directory transition,
  folder-first locale sorting, and distinct loading/empty/no-match/refresh/error
  states.
- **AC-11 — More/Bots/Kanban:** More reaches every lower-frequency route by
  localized label or stable keyword; literal `Bots` is used in en/ko/zh
  top-level/core resources; Kanban data/content/layout is unchanged.
- **AC-12 — Accessibility:** evidence proves at least 48 dp controls, visible
  bar/rail labels, TalkBack role/selection/focus order, non-color-only cues,
  absolute-time accessibility text, keyboard/rotary access, 200% font, RTL,
  reduced motion, and no clipping/overlap.
- **AC-13 — Device QA:** compact portrait/landscape, medium/expanded, resize/fold,
  split screen, gesture/three-button insets, IME, and process recreation preserve
  a safe destination/mode without duplicate routes or unintended sessions.

Slice mapping: slice 1 verifies AC-04, AC-05, and the More portion of AC-11;
slice 2 verifies AC-06–AC-09 and the Bots portion of AC-11; slice 3 verifies
AC-10; slice 4 verifies AC-12–AC-13 plus full AC-04–AC-11 regression.

## Settled decisions and deferred work

- The adaptive five-destination bottom bar on compact windows and navigation rail
  on medium/expanded windows is final for this phase; no improved, global, or
  permanent drawer alternative is under consideration.
- Chat Home (Chats default) and Rooms peer mode ship together as one future
  product slice on the inherited room data layer; Rooms is not delayed by any
  parser/cache premise.
- The bot-switch warning applies only when the current session has at least one
  displayed or persisted message; empty and draft-only sessions switch without
  warning and the unsent draft is cleared, never carried over.
- Phase-1 Files search is current-directory, filename-only, case-insensitive
  local filtering; global backend search is deferred until an explicit backend
  contract exists.
- Kanban content, data, and layout are out of scope for redesign beyond top-level
  shell placement and insets.
- Top-level/core user-facing copy uses the literal token `Bots` in English,
  Korean, and Chinese resources; `Profiles` remains the internal/API/storage term.

## Risks and honest limitations

- **Route loss:** prove exact key-set reachability before drawer retirement.
- **Rooms scope/boundedness:** profile and connection are different axes; show
  truncation, cache, gap, and stale limits and never imply full history.
- **Bot context/draft loss:** use only the atomic coordinator, the exact warning
  predicate, explicit draft handling, the duplicate guard, and failure
  preservation.
- **Localization ambiguity:** literal `Bots` is frozen for current en/ko/zh
  top-level/core copy.
- **Local search overstatement:** filename-only scope copy and
  no-network/no-recursion/no-MIME tests.
- **Adaptive/accessibility regressions:** AC-12 and AC-13 remain mandatory future
  evidence, not a docs-only completion claim.
- **Kanban/visual scope creep:** reject changes beyond shell placement and insets.
