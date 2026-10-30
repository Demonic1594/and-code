# Drawer: the Inbox row moves under the agents switcher

Status: **not started**. Written down to build later; nothing in the app has been changed.

## Goal

The drawer's Inbox row moves from directly under **New chat** to directly under the **agents**
switcher. The drawer's top then holds the actions (New chat), then what you work *with*
(agents), then where things wait for you (Inbox), then the Projects and Recent chats lists —
navigation grouped with navigation instead of sitting above the agent selection.

## What exists today

`AppDrawerContent.kt:270-299` lays the drawer out in this order:

1. `DrawerHeader` (`:270`)
2. `NewChatRow` (`:271`)
3. `InboxRow` (`:272`) — waiting/running badge counts, navigates to the Inbox screen
4. `DrawerSearchField` (`:273-275`) — while there are chats; a sibling plan moves it below
5. Agents switcher (`:280-283`) — only when there is more than one agent
6. Projects header + chips (`:286-292`)
7. **Recent chats** section (`:294-299`), with the search field planned to move directly above
   it (`2026-10-30-drawer-search-above-recent-chats.md`).

`InboxRow` itself (`:575`) is a self-contained row (label, counts, click); the move changes
only where the call sits.

## Design

- The `InboxRow` call moves from `:272` to immediately after the agents switcher block
  (`:280-283`). The badge counts and navigation are unchanged.
- The agents switcher is conditional (`agents.size > 1`). "Under the agents" means under the
  block where the switcher sits. Applied alone, the search field still sits above the switcher,
  so with a single agent the row lands between the search field and **Projects**; once the
  search-field plan lands too, that slot is directly under **New chat**. Either way the
  position is stable whether or not the switcher renders.
- Together with the two sibling plans the drawer's final order becomes: Header → New chat →
  Agents (when >1) → **Inbox** → Projects (+ its header buttons and project filter from
  `2026-10-28-chat-and-drawer-qol.md`) → chat search field
  (`2026-10-30-drawer-search-above-recent-chats.md`) → Recent chats. The three plans touch
  disjoint lines in the same composable and compose in any order.
- No new strings, no behavior change beyond ordering.

## Tasks

- Move the `InboxRow` call below the agents switcher block.
- Screenshot of the drawer through the existing harness: with more than one agent, and with
  one (row directly above Projects), badge counts visible in both.
- Per repo convention: `./gradlew detekt spotlessCheck :app:lintGithubDebug
  :app:testGithubDebugUnitTest`, and the pre-PR review.

## Confirm on a device

1. With several agents installed: New chat, agents chips, Inbox, Projects, Recent chats — in
   that order (the chat search field sits between Projects and Recent chats once its plan
   lands), counts intact.
2. With a single agent: nothing renders between New chat's block and Projects above the Inbox
   row — apart from the search field, until its own plan moves it.

## Out of scope

- The Inbox screen itself and how counts are computed.
- The Projects header and search-field plans (siblings, referenced above).
