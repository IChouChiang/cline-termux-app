# Auto-approve control: design pass (TODO A1)

Design only, no code (2026-10-07), for the owner's A1 replies 2 and 3 under P1 rule 3 (the
terminal's mechanism and the kernel's global setting; the placement is the app's). Sizes
marked ≈ are estimates from component specs; previews at 320, 360 and 412 dp settle them.

## 1. Behaviour model

1. **Global default:** `toolAutoApprove` in `global-settings.json` seeds every session the terminal (and now the app) creates or resumes; a missing key means **on** (`apps/cli/src/main.ts:892`); the desktop never reads it.
2. **Per session, fixed:** set at `session.create` (`toolPolicies["*"].autoApprove`, echoed as `metadata.autoApproveTools`); only a restart with the session's messages changes it.
3. **Run boundary:** after a flip the session restarts with the new value when its run ends (completed, failed or stopped by the user), or, if idle, just before its next send. To auto acts at once anyway (the app answers new requests itself); to ask cannot reach the run in progress (the app has no hook), so that run keeps auto-approving.
4. **Two states:** "Auto-approve all" runs every tool call; "Ask each time" still runs the seven read-only tools and asks before the rest (the terminal's definition, `help-dialog.tsx:221-229`).
5. **Indicator:** always the global mode, plus a transient "Ask from the end of this run" while the open session's run still auto-approves after a switch to ask; stopping the run ends it at once, with the same motion as a normal end.

## 2. Placements

Header budget today at 360 dp: navigation ≈ 52 dp, New session + overflow ≈ 100 dp, so the
title slot is ≈ 208 dp. The title must keep at least 96 dp (`HeaderWidthRule`), which leaves
≈ 112 dp for chips: either the agents chip (≈ 88 dp with its gap) or the diff chip (≈ 80 dp)
fits, not both. On the S25U (412 dp) the slot is ≈ 260 dp and the chips get ≈ 164 dp.

Legend: `S` = `shield` (outlined), `G` = `gpp_good` (filled, a new Material Symbol); bare =
no container, `( )` = 1 dp `tertiary` ring (outlined style), `[ ]` = `tertiaryContainer` disc.

### A. Top-app-bar action, on Home and in every chat

```
Chat, 360 dp                                   state
┌────────────────────────────────────────────┐
│ ≡  ● Fix the login crash…       S   +   ⋮  │  Ask each time
│ ≡  ● Fix the login crash…      [G]  +   ⋮  │  Auto-approve all
│ ≡  ● Fix the login crash…      (S)  +   ⋮  │  Ask from the end of this run
└────────────────────────────────────────────┘
Home, 360 dp (empty title, so no cost)
┌────────────────────────────────────────────┐
│ ≡                                     [G]  │
└────────────────────────────────────────────┘
Tap opens the menu:  ┌──────────────────────────────────────────┐
                     │ Tool approvals                           │ labelMedium
                     │ G  Auto-approve all                    ✓ │
                     │    Every tool call runs                  │
                     │ S  Ask each time                         │
                     │    Read-only tools still run             │
                     │ Shared with Cline in the terminal.       │ bodySmall
                     │ This run keeps auto-approving until it   │ pending only
                     │ ends. Stop to switch now.                │
                     └──────────────────────────────────────────┘
```

- **Component.** One `IconButton` (48 dp target, 40 dp container) in `TopAppBar(actions = …)`,
  before New session (or the subagent back chip), wrapped in `TooltipBox` + `PlainTooltip`.
  Tap opens a `DropdownMenu` of two `DropdownMenuItem`s (leading icon, trailing `check` on
  the current one). The three states use three M3 icon-button emphasis styles, through
  `IconButtonDefaults.iconButtonColors(containerColor, contentColor)` plus
  `Modifier.border(…, CircleShape)`.
  - Rejected: a `FilledTonalIconToggleButton` that flips on tap. A mis-tap next to "+"
    would turn on auto-approve for every new session, the terminal's too, and an Undo snackbar
    comes too late if a tool already ran. Two deliberate taps are the phone-safe Shift+Tab.
- **States and colours.** Ask: `shield` in `onSurfaceVariant`, no container (standard).
  Auto: filled `gpp_good` in `onTertiaryContainer` on a `tertiaryContainer` disc (filled
  tonal). Pending: `shield` in `tertiary` inside a 1 dp `tertiary` ring (outlined). Before
  the first read: `shield` at the disabled alpha; with Termux commands unavailable it is
  disabled, tooltip "Start kernel to change this."
  - Why `tertiary`: green is running, amber is approval required (DS §1.6), blue is unread,
    and `secondaryContainer` marks the selected Plan|Act and the navigation highlight.
    `tertiary` is M3's contrasting accent role and stays a distinct hue under dynamic
    colour. Emphasis rises with how automatic the state is, and the icon goes from outlined
    (off) to filled (on), as M3 toggles do.
- **Touch.** Tap opens the menu; picking an item applies it (the menu is the confirmation
  step); the current item only closes it. Long-press: tooltip "Tool approvals: <state>"
  (DS §4.1 #14). State layers are the component's own (ripple, 10 % press and focus).
- **Accessibility.** Content description "Tool approvals", role Button, `onClickLabel`
  "Change"; `stateDescription` "Auto-approve all", "Ask each time" or "Ask each time from
  the end of this run; this run keeps auto-approving"; each applied state is announced by
  `LiveRegionAnnouncer` (polite). Contrast: the ring ≥ 3:1 on `surface`;
  `onTertiaryContainer` on `tertiaryContainer` ≥ 4.5:1.
- **Width cost.** +48 dp in the actions (3 trailing actions including the overflow, within
  M3's guidance for a small top app bar). At 360 dp the title slot shrinks to ≈ 160 dp, so
  neither chip fits and both move to the overflow (today one fits). At 412 dp (≈ 212 dp)
  one chip still fits, as today. Expanded windows and Home cost nothing.

### B. Pinned status chip in the header's chip row

```
Chat, 360 dp
│ ≡  ● Fix the lo…  [S Ask]           +   ⋮  │  Ask each time
│ ≡  ● Fix the lo…  [G Auto]          +   ⋮  │  Auto-approve all (selected)
│ ≡  ● Fix the lo…  (S Ask ⏲)         +   ⋮  │  pending (tertiary border, schedule icon)
Home: the same chip in the empty title slot
```

- **Component.** A `FilterChip` (`selected = auto`), leading 18 dp icon, label "Auto" /
  "Ask"; selected in `tertiaryContainer` via `FilterChipDefaults.filterChipColors`; pending
  unselected with a `tertiary` border and a trailing 16 dp `schedule`. Tap opens A's menu;
  semantics merged into one node, as in A. The width rule must pin it (never in the overflow).
- **Width cost.** ≈ 76 dp. At 360 dp the title keeps ≈ 124 dp and both session chips move to
  the overflow; at 412 dp one fits.
- **Against.** It sits among the session's own facts (agents, diff), so it reads as a
  property of this session, which the owner wants to avoid. M3 chips are for input,
  filters and actions, not app-wide status. It costs more width than A.

### C. Drawer switch plus a passive header glyph

```
Drawer top (app level)                  Chat header, 360 dp
│ G  Auto-approve all         [==●] │   │ ≡  ● G Fix the login…    +   ⋮ │
│    Shared with the terminal       │   (16 dp glyph after the dot; S in a ring
│ Sessions …                        │    while pending; nothing when Ask)
```

- **Component.** A `ListItem` with a trailing `Switch` at the top of the sessions drawer
  (and of the list pane on expanded windows); in the chat a decorative 16 dp `Icon` in
  `tertiary` in the title row, folded into the title's content description. Width ≈ 20 dp.
- **Against.** Changing the mode mid-chat means opening the drawer; a glyph beside the
  status dot reads as session status; indicator and control are split, against the
  owner's "indicator and method".

## 3. The approval-sheet caret

```
┌ OutlinedCard ──────────────────────────────┐
│ run_commands                       12:04  │
│ $ npm test                                 │
│              ( Reject )  [ Approve ][▾]    │
└────────────────────────────────────────────┘
      [▾] opens  ┌──────────────────────────────────────────────┐
                 │ Approve and auto-approve from now on          │
                 │ Turns on Auto-approve all (shared with the    │ bodySmall
                 │ terminal)                                     │
                 └──────────────────────────────────────────────┘
```

- **Component.** A split button. material3 1.4.0 (BOM 2026.09.00) ships only the
  split-button tokens, not a public `SplitButtonLayout`, so it is two `Button`s in a `Row`:
  "Approve" and a trailing 48 dp `arrow_drop_down` part (CD "More approve options"), 2 dp
  apart with `shapes.extraSmall` inner corners per the M3 spec; the chevron turns 180°
  while its `DropdownMenu` (one two-line item) is open (`Short4`, `Standard`). Move to
  `SplitButtonLayout` once it is stable. Reject stays an `OutlinedButton`; the trailing part
  hides while the card is busy. At 360 dp: ≈ 258 of ≈ 280 dp, so it fits.
- **What it does.** (1) Approves this request (`respondToApproval(id, true)`). (2) Writes
  `toolAutoApprove: true`. (3) From then on the app answers every *new* request itself in
  the sessions it has open; requests already on screen stay for the user, as in the
  terminal, where Shift+Tab does nothing while its approval dialog is open
  (`use-root-keyboard.ts:101`), so the caret is "y" then Shift+Tab. (4) When the run ends,
  the session restarts with its messages and the new value, silently; the header disc grew
  in at step 2. If the write fails, the approval stands, a snackbar says "Couldn't turn on
  Auto-approve all: <reason>", and neither the global nor the app's answering changes.

## 4. The Settings switch

```
Kernel
  Cline Hub @25463 · Connected                                    >
  G  Auto-approve all                                          [●==]
     Run every tool call without asking. When off, read-only tools
     still run and the rest ask first. This is the kernel's setting,
     shared with Cline in the terminal; Cline Desktop always
     auto-approves. The running session switches when its run ends.
```
(The last sentence shows only while a session is pending.)

- **Section and component.** Settings home, "Kernel" group, under the Kernel row: it is the
  kernel's setting and the terminal's `/settings` row "Auto-approve all"
  (`config-view.tsx:484-488`). A `PrefSwitchRow` with the Telemetry row's saving spinner and
  rollback error; disabled with "Start kernel to change this." without Termux commands.
- **Effect on open sessions.** As from the header: write the setting; each open session
  follows at its next run boundary (§6).

## 5. Motion

One small motion lives in the header button. It plays when the pending state applies,
and also when the mode turns on.

- **Entering pending** (switch to ask during a run): the disc empties into the ring
  (container alpha → 0 as a 1 dp border fades in, `Short4`, `Standard`).
- **Applies** (the run ends, or the user taps Stop in the composer or "Stop agent" in the
  sheet): the `tertiary` ring contracts and fades (scale 1 → 0.8, alpha 1 → 0,
  `ClineMotion.Short4` 200 ms, `EmphasizedAccelerate`, the M3 exit curve), while the icon
  colour settles from `tertiary` to `onSurfaceVariant` (`animateColorAsState`, `Short4`,
  `Standard`). The user sees the ring let go of the shield, and TalkBack says "Ask each
  time is on".
  - Stopped by the user: the same motion and timing, because it is the same run boundary;
    the announcement becomes "Run stopped. Ask each time is on." No snackbar, since the
    transcript already shows "Cancelled".
- **Turning on:** the `tertiaryContainer` disc grows from the icon's centre (scale 0.6 → 1
  with a fade, `Medium2` 300 ms, `EmphasizedDecelerate`, the M3 enter curve). The icon
  cross-fades from `shield` to `gpp_good` (`AnimatedContent`, fade + `scaleIn(0.8f)`,
  `Short3`, the same recipe as the Send ⇄ Stop morph, DS §6).
- **Turning off while idle:** the disc shrinks out with the exit curve (200 ms); no ring.
  Reduced motion (`LocalReducedMotion`): instant swaps, announcements stay. A restart in
  flight (one `session.create`) holds a send, as a resume already does (`creating`).

### As built after A15 (2026-10-08)

The owner confirmed placement A′ and changed the glyphs (decided A15), so the disc, the ring
and their motion above are gone. Ask each time is the ticked shield (`gpp_good`, fill 1): the
guarded state carries the tick. Auto-approve all is the bolt (`bolt`). Both are plain
standard icon buttons in `onSurfaceVariant`, with no container. Pending draws the state it
switches to, the ticked shield, at low emphasis (`onSurfaceVariant` at 60 %), with a small M3
`Badge` dot in `tertiary` at the glyph's corner. When the switch applies at the run's end or on
Stop, the dot scales out (`Short4`, `EmphasizedAccelerate`) and the glyph returns to full
emphasis; turning the mode on or off cross-fades the two glyphs (`Short3`, the Send ⇄ Stop
recipe). The menu items, the approval sheet's "Approve and auto-approve from now on" and the
Settings switch use the same two glyphs. The batch-1 debug extra that showed placement A for
comparison is removed; states, announcements and wording are unchanged.

All of them take the glyph from one place, `ClineIcons.Approval` (`Ask` = `ms_gpp_good_fill`,
`Auto` = `ms_bolt`), and the comparison switch went with its code (`ApprovalPlacementOverride`
in both build types). Batch 2 checked it on the S25U (V-ICONS, docs/11-batch2-report.md): in the
48 dp target the only colours are the glyph and the top bar, so there is no disc or ring; the
pending glyph measured exactly 60 % of `onSurfaceVariant` over the bar colour, with a dot of
about 6 dp in `tertiary`; the dot went and the glyph returned to full emphasis at the run's end,
when the run-boundary restart resumed the session. Choosing Ask in the menu wrote
`"toolAutoApprove": false` to the phone's `global-settings.json`, and the next tool call asked
for approval.

## 6. Terminal and desktop

- **When the app re-reads `global-settings.json`** (through the existing `cat` command; no
  polling, no file watch): on return to the foreground, on opening a session, at the end of
  each run in an open session (before deciding on a restart), when the menu or Settings
  opens, and after the Hub reconnects. The kernel itself re-reads the file by mtime and
  size (`global-settings.ts:186-215`).
- **The terminal flipped the setting while the app was open.** At the next re-read the
  header plays the motion above, and a short snackbar says "Auto-approve all turned on
  outside the app" (or "turned off"). The terminal is the only other writer.
- **What the app restarts.** Only at the end of a run the app started, or just before the
  app sends (idle sessions, and changes made outside the app); never a session merely being
  viewed, nor a run another client drives. Skipped when `session.get` already shows the
  global value (a terminal that restarted the same session itself).
- **The other way round.** The terminal reads the setting only at startup
  (`main.ts:891-897`), so a flip in the app reaches an already-open terminal only when that
  terminal restarts. The Settings supporting text does not promise more.
- **Desktop.** Its sessions are created with auto-approve on (`constants.ts:39`), and it has
  no control. A desktop session keeps its value in the app while the desktop drives it; the
  menu then adds "This session auto-approves: it was started in Cline Desktop." If the app
  later sends to it, the resume takes the global value, as a terminal resume would.

## 7. Recommendation and cost

**Recommendation: A, the top-app-bar action.** It sits in the same spot on Home and in
every chat, which is how Android shows app-wide state, and it is both the indicator and the
control. Its 48 dp only costs something at 360 dp, where the session chips already overflow;
on the S25U nothing changes in practice. It uses M3's own icon-button emphasis styles,
tooltip and menu, and the menu step guards against a mis-tap enabling auto-approve.

**Cost, roughly**: one new Termux command, about four new files, about twelve edited files,
about thirty JVM tests, two Compose flow tests and one new Hub fixture.

- **Kernel I/O.** `CommandOutputs.kt`: add `toolAutoApprove` to `GlobalSettingsView` (a
  missing key means on). `ClineCommands.kt`: a new `writeToolAutoApprove(enabled)`, a
  `bash -c` script (precedent: the attachment scripts, `ClineCommands.kt:319`) that uses the
  port's Bun to merge the one key into a temporary file, `mv` it into place and print the
  result; document it in `scripts/gen-docs.py` (docs/02). The settings helper has
  `get_global_settings` but no setter for this key (`apps/cline-hub/src/server/desktop-commands.ts:200-216`),
  so it is no alternative without a port revision.
- **Data.** A new `data/approvals/ApprovalModeRepository` (read triggers, write with
  rollback). A run-boundary coordinator: when a run the app started ends, stop the session
  and recreate it through the existing `SessionStore.resume` path (the terminal's
  `stopCurrentSession` + `startFreshSession`). A client-side auto-answer in
  `ApprovalController.kt`. Replace `prefs.autoApproveNow()` in `AppGraph.kt`,
  `ChatViewModel.kt` (246, 362, 789), `SessionActions.kt`, `SessionStore.kt:521` and
  `CheckpointViewModel.kt:98`, and delete the `tools.autoApprove(.<id>)` keys (docs/07 §7).
  For Ask, send the terminal's safe-tool policies (`apps/cli/src/runtime/tool-policies.ts:3-11`)
  from `HubSessionApi.toolPolicies`.
- **UI.** A new `ui/approval/ApprovalModeButton.kt` (button, menu, motion);
  `ConversationHeader.kt` and `ChatScreen.kt`'s `HomeTopBar`; `ApprovalSheet.kt`; the
  Settings home screen, ViewModel and sources; strings; `ms_gpp_good_fill.xml` and its
  `ClineIcons` entry. Spec updates in home-chat §6, composer-approvals C3 and docs/07 §15
  (a new D20).
- **Tests.** JVM: settings parse (missing, true, false, garbage), the write command
  builder, the repository (re-reads, rollback), the coordinator (idle, completed, failed,
  stopped, a run another client drives, already matching), the auto-answer (only new
  requests, only open sessions), the header width rule at 320, 360 and 412 dp, and the
  pending-state derivation. `CriticalFlowsTest` against `FixtureHub` with a new restart
  recording (`tools/hub-fixtures`): flip during a run → ring → run end →
  `session.create {resumeSessionId}` → ring gone. One S25U pass against the developer Hub
  (`hello-from-mock` triggers the approval).
- **Open for the owner.** Following the terminal means a missing key reads as Auto-approve
  all, so the app's first-run default changes from off to on unless the file says false.
