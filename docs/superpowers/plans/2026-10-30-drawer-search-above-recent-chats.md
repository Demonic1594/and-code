# Drawer: the chat search field moves next to Recent chats

Status: **not started**. Written down to build later; nothing in the app has been changed.

## Goal

The drawer's chat search field moves from the top of the drawer to directly above the
**Recent chats** section — the list it filters. Today it sits between the Inbox row and the
Agents/Projects sections, far from what it acts on; after the move the tool sits next to its
target, the way it does in most chat apps.

## What exists today

`AppDrawerContent.kt:270-299` lays the drawer out in this order:

1. `DrawerHeader`
2. `NewChatRow`
3. `InboxRow`
4. `DrawerSearchField` (`:273-275`) — shown only while `recentSessions.isNotEmpty()`
5. Agents switcher (when there is more than one)
6. Projects header + chips
7. **Recent chats** section header (`:294-299`, collapsible) with the pinned chats, the
   filtered chat list, and the `DrawerSearchMessagesRow` ("Search messages for …") inside.

The search query filters `recentSessions` inside section 7 (`filterDrawerSessions`), and the
"search inside messages" row appears there too. The field itself is a plain outlined input
bound to `searchQuery`.

## Design

- The `DrawerSearchField` call moves from `:273-275` to sit between the Projects chips and the
  **Recent chats** section header, inside the same `recentSessions.isNotEmpty()` guard the
  section uses (one condition now, not two): with no chats at all there is nothing to search
  and neither the field nor the section shows.
- The field stays **outside** the section's `AnimatedVisibility`, so it is visible even while
  Recent chats is collapsed — but typing into it should expand the section, otherwise the
  results are filtered into a list the user cannot see. The cheapest correct behavior: a
  non-blank `searchQuery` forces the section open (the collapse state stays as chosen once the
  query is cleared). The forced-open condition applies to both the `AnimatedVisibility`'s
  `visible` **and** the header's `collapsed` argument — otherwise the content shows while the
  chevron still says collapsed — and it must not write to `collapsedSections`, which is the
  user's own choice.
- Nothing else moves: the agents switcher, the Projects row, the Inbox row, and everything
  inside the section keep their order. The Projects-header redesign in
  `2026-10-28-chat-and-drawer-qol.md` touches the section directly above; the two compose —
  that plan adds the project filter inside the Projects header, this plan only relocates the
  chat search field below it.
- No new strings; `DrawerSearchField`, `drawer_recent_chats` and the hint string are reused.

## Tasks

- Move the `DrawerSearchField` block below `DrawerProjectChips`, merging its guard into the
  Recent chats guard; make a non-blank query expand a collapsed "recent" section.
- Screenshot of the drawer through the existing harness: empty query, typed query (field
  visible, section expanded, filtered list), collapsed section with the field still visible.
- Per repo convention: `./gradlew detekt spotlessCheck :app:lintGithubDebug
  :app:testGithubDebugUnitTest`, and the pre-PR review.

## Confirm on a device

1. With chats present, the field sits directly above the **Recent chats** header; typing
   filters the list under it.
2. Collapsing Recent chats keeps the field; typing into it expands the section.
3. A fresh install with no chats shows neither the field nor the section.

## Out of scope

- The Projects header layout and its project filter (the 2026-10-28 plan).
- Search behavior itself (what it matches, the "search inside messages" row).
