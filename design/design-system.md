# Cline Termux: design system

Status: design-phase spec, 2026-10-03. Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`). Applies to every
screen in `docs/04-screens.md` and every feature row in `docs/03-feature-matrix.md`.
Screen specs (`design/screens/*.md`) build on this file and must not redefine anything
it defines. Where they need something new, add it here first.

How to read this:

- **Desktop source** is the Cline monorepo at `~/workspace/cline-termux-v3-phone`. Paths
  shown as `webview/...` are under `apps/examples/desktop-app/webview/`, and `ui/...`
  paths are under `sdk/packages/ui/` (the shared `@cline/ui` package). The task brief
  called it `packages/ui/src`, but the package actually lives at `sdk/packages/ui`.
- **Voice app** is the owner's proven Compose app at
  `~/workspace/cline-termux-voice/android/app/src/main/kotlin/dev/cline/voice`. Code copied
  from it carries the header comment
  `// Adapted from cline-termux-voice (same owner): <original path>`.
- Hex values in this file were generated with `@material/material-color-utilities` 0.4.0
  (HCT tonal palettes). Contrast ratios are WCAG 2.x and were computed with a script.
  All of them are listed in section 1.8.
- "MUST" items are acceptance criteria. Section 11 collects them.

---

## 0. Code layout for this system

```
app/src/main/kotlin/dev/cline/termux/ui/
  theme/ClineTheme.kt        ClineTheme { }, AppearancePrefs -> scheme selection
  theme/PaletteScheme.kt     copied from voice ui/theme/DynamicScheme.kt (paletteColorScheme)
  theme/BrandScheme.kt       ClineLightScheme, ClineDarkScheme (section 1.3)
  theme/ClineColors.kt       extended semantic colours + LocalClineColors (section 1.5)
  theme/Type.kt              ClineTypography + ClineMono (section 2)
  theme/Shape.kt             ClineShapes (section 3.1)
  theme/Spacing.kt           Spacing object (section 3.3)
  theme/Motion.kt            ClineMotion (section 6)
  icons/ClineIcons.kt        Material Symbols mapping, TOOL_KIND_ICONS (section 7)
  components/                one file per inventory row (section 4)
  shell/                     AppShell, adaptive scaffolds (section 5)
app/src/main/res/
  drawable/ms_*.xml          Material Symbols Rounded vector drawables (section 7.1)
  drawable/ic_launcher_*.xml, ic_stat_cline.xml, ic_cline_logo.xml (section 10)
  font/geist_mono_*.ttf      monospace face (section 2.2)
```

Dependencies to add to `app/build.gradle.kts`. Versions were checked against Google Maven
on 2026-10-03. `material3` 1.4.0 comes from BOM 2026.09.00, and the adaptive artifacts
are versioned outside the BOM:

```kotlin
implementation("androidx.compose.material3:material3-window-size-class")            // BOM -> 1.4.0
implementation("androidx.compose.material3:material3-adaptive-navigation-suite")    // BOM -> 1.4.0
implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")
implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.3.0")
implementation("androidx.core:core-splashscreen:1.2.0")   // verify latest stable when adding
```

Do **not** add `material-icons-extended`. The voice app dropped it because of its APK
size. Icons come from section 7.1.

---

## 1. Brand and colour

### 1.1 What the desktop palette is

Cline Desktop takes its colours from `ui/theme/palette.css`, which holds Radix Colors 3.0
scales: Slate becomes neutral, Violet becomes accent, Ruby becomes error, Green becomes
success, Amber becomes warning and Sky becomes info. `ui/theme/tokens.css` turns those
scales into semantic tokens, and `webview/app/globals.css` adds alternative accents
(graphite, cyan, pink, espresso, ember). The default accent is violet.

| Desktop token | Light | Dark | Role in desktop |
| --- | --- | --- | --- |
| `--primary` = accent-9 | `#6E56CF` | `#6E56CF` | buttons, running ring, links |
| `--accent-11` | `#6550B9` | `#BAA7FF` | accent text |
| `--accent-3` / `--accent` | `#F4F0FE` | `#291F43` | hover / selected fills |
| `--background` = neutral-1 | `#FCFCFD` | `#121216` | window |
| `--card`, `--popover` = neutral-2 | `#F9F9FB` | `#18191B` | cards, menus |
| `--card-surface` (user bubble) | mix(neutral-2, neutral-3) | neutral-3 `#212225` | user message |
| `--muted` / `--secondary` = neutral-3 | `#F0F0F3` | `#212225` | chips, diff stat pill |
| `--foreground` = neutral-12 | `#1C2024` | `#FCFCFD` | text |
| `--muted-foreground` = neutral-11 | `#60646C` | `#B0B4BA` | secondary text, tool rows |
| `--border` = neutral-4 | `#E8E8EC` | `#2F2F37` | hairlines |
| `--error-text` = error-11 | `#CA244D` | `#FF949D` | errors |
| `--success-9` (`--chart-2`) | `#30A46C` | `#30A46C` | `+N` additions, completed |
| `--brand-violet` | oklch(.55 .22 293) ≈ `#7C49E3` | oklch(.70 .17 293) ≈ `#A486FD` | hero art |
| `--brand-periwinkle` / `--brand-magenta` | ≈ `#8392F1` / `#E289DB` | | `AgentHeroHeading` gradient (the component is no longer mounted on the desktop welcome screen, so the app does not use these) |
| logo fill (`public/icon.svg`) | `#1C1C24` | | the mark itself |

Status colours in the desktop are hard-coded Tailwind values, not tokens. Running is
`green-500` (`lib/session-status.ts`, `AgentSessionRow`), pending is `yellow-400`,
unread is `blue-500`, error is `red-500`, the approval panel uses `amber-400/500`, and
the context ring uses primary below 50 %, `orange-500` from 50 % and `red-500` from 75 %.

### 1.2 Scheme policy

1. **Dynamic colour is on by default.** On Android 12 (API 31) and later,
   `ClineTheme` builds the `ColorScheme` from the wallpaper's tonal-palette stops with
   `paletteColorScheme(context, dark)`. That function is copied unchanged from the voice
   app's `ui/theme/DynamicScheme.kt`. Do **not** call `dynamicLightColorScheme` /
   `dynamicDarkColorScheme`: on API 34+ they read One UI's overridden role tokens, which
   give a fixed `#387AFF` primary, `onSurfaceVariant == onSurface` and surface containers
   in the wrong order. The voice app documents this in `Theme.kt`.
2. **Brand fallback.** The brand scheme in section 1.3 is used when the API level is
   below 31, when the user turns off Settings > Appearance > "Dynamic colour", and in
   every `@Preview`.
3. **Dark mode** has three choices: "System default", "Light" and "Dark". The default
   is "System default". The desktop's single "Dark mode" switch becomes this choice
   because Android has a system setting. The value is stored in DataStore under
   `appearance.theme`.
4. **Extended colours do not follow the wallpaper.** Success, warning, info, terminal
   and diff colours (section 1.5) are fixed per light/dark and are not harmonised. This
   keeps their meaning stable: running is always green and approval is always amber,
   exactly as in the desktop. The only roles that follow the wallpaper are the M3 roles.
5. **Edge-to-edge system bars.** Call `enableEdgeToEdge()` in
   `MainActivity.onCreate` before `setContent`. Status-bar icons follow the theme
   (light icons in dark mode). Set `window.isNavigationBarContrastEnforced = false` so
   that gesture navigation gets no scrim.

### 1.3 Brand fallback scheme (contrast-checked)

How it was built: the seed is `#6E56CF` (desktop accent-9, HCT hue 294.7, chroma 61.5).
The primary palette uses the seed's own chroma. Secondary uses the same hue at chroma 16.
Tertiary uses the desktop's `--brand-magenta` (hue 333.4) at chroma 36. Neutral and
neutral-variant use the Radix Slate hue (244.3) at chroma 4 and 8, because the desktop
greys are slightly cool. Light primary is HCT T40 `#6249C2`, not the seed `#6E56CF`.
The seed reaches only 4.41:1 on `surfaceContainerHigh`, which is where `AlertDialog`
buttons sit. T40 reaches 5.28:1.

| Role | Light | Dark |
| --- | --- | --- |
| primary | `#6249C2` | `#CBBEFF` |
| onPrimary | `#FFFFFF` | `#320A93` |
| primaryContainer | `#E6DEFF` | `#4A2EA9` |
| onPrimaryContainer | `#4A2EA9` | `#E6DEFF` |
| inversePrimary | `#CBBEFF` | `#6249C2` |
| secondary | `#605B71` | `#CAC3DC` |
| onSecondary | `#FFFFFF` | `#322E41` |
| secondaryContainer | `#E6DFF9` | `#484458` |
| onSecondaryContainer | `#484458` | `#E6DFF9` |
| tertiary | `#7F4D7B` | `#F0B3E7` |
| onTertiary | `#FFFFFF` | `#4B1F4A` |
| tertiaryContainer | `#FFD7F7` | `#653662` |
| onTertiaryContainer | `#653662` | `#FFD7F7` |
| error | `#BA1A1A` | `#FFB4AB` |
| onError | `#FFFFFF` | `#690005` |
| errorContainer | `#FFDAD6` | `#93000A` |
| onErrorContainer | `#93000A` | `#FFDAD6` |
| background / surface / surfaceBright(light) | `#F9F9FC` | `#111416` |
| onBackground / onSurface | `#1A1C1E` | `#E2E2E5` |
| surfaceVariant | `#DEE3EA` | `#42474D` |
| onSurfaceVariant | `#42474D` | `#C1C7CE` |
| surfaceTint | `#6249C2` | `#CBBEFF` |
| inverseSurface | `#2F3133` | `#E2E2E5` |
| inverseOnSurface | `#F0F0F3` | `#2F3133` |
| outline | `#72787E` | `#8C9198` |
| outlineVariant | `#C1C7CE` | `#42474D` |
| scrim | `#000000` | `#000000` |
| surfaceBright | `#F9F9FC` | `#37393C` |
| surfaceDim | `#D9DADD` | `#111416` |
| surfaceContainerLowest | `#FFFFFF` | `#0C0E11` |
| surfaceContainerLow | `#F3F3F6` | `#1A1C1E` |
| surfaceContainer | `#EDEEF1` | `#1E2022` |
| surfaceContainerHigh | `#E8E8EB` | `#282A2D` |
| surfaceContainerHighest | `#E2E2E5` | `#333537` |

`BrandScheme.kt` (paste as is):

```kotlin
package dev.cline.termux.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Cline brand fallback: seed #6E56CF (desktop accent-9), Radix-slate neutrals. See design-system.md §1.3. */
val ClineLightScheme = lightColorScheme(
    primary = Color(0xFF6249C2), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF4A2EA9),
    inversePrimary = Color(0xFFCBBEFF),
    secondary = Color(0xFF605B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6DFF9), onSecondaryContainer = Color(0xFF484458),
    tertiary = Color(0xFF7F4D7B), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F7), onTertiaryContainer = Color(0xFF653662),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FC), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF9F9FC), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDEE3EA), onSurfaceVariant = Color(0xFF42474D),
    surfaceTint = Color(0xFF6249C2),
    inverseSurface = Color(0xFF2F3133), inverseOnSurface = Color(0xFFF0F0F3),
    outline = Color(0xFF72787E), outlineVariant = Color(0xFFC1C7CE), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FC), surfaceDim = Color(0xFFD9DADD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3F6),
    surfaceContainer = Color(0xFFEDEEF1), surfaceContainerHigh = Color(0xFFE8E8EB),
    surfaceContainerHighest = Color(0xFFE2E2E5),
)

val ClineDarkScheme = darkColorScheme(
    primary = Color(0xFFCBBEFF), onPrimary = Color(0xFF320A93),
    primaryContainer = Color(0xFF4A2EA9), onPrimaryContainer = Color(0xFFE6DEFF),
    inversePrimary = Color(0xFF6249C2),
    secondary = Color(0xFFCAC3DC), onSecondary = Color(0xFF322E41),
    secondaryContainer = Color(0xFF484458), onSecondaryContainer = Color(0xFFE6DFF9),
    tertiary = Color(0xFFF0B3E7), onTertiary = Color(0xFF4B1F4A),
    tertiaryContainer = Color(0xFF653662), onTertiaryContainer = Color(0xFFFFD7F7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111416), onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF111416), onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF42474D), onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceTint = Color(0xFFCBBEFF),
    inverseSurface = Color(0xFFE2E2E5), inverseOnSurface = Color(0xFF2F3133),
    outline = Color(0xFF8C9198), outlineVariant = Color(0xFF42474D), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393C), surfaceDim = Color(0xFF111416),
    surfaceContainerLowest = Color(0xFF0C0E11), surfaceContainerLow = Color(0xFF1A1C1E),
    surfaceContainer = Color(0xFF1E2022), surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333537),
)
```

### 1.4 Desktop token to M3 role

Use this table whenever a screen spec quotes a desktop class name.

| Desktop token | M3 role (via `MaterialTheme.colorScheme`) |
| --- | --- |
| `--background`, window | `surface` (screens), `background` (never different here) |
| `--card`, `--popover`, `--sidebar` | `surfaceContainerLow` (cards, drawer sheet, bottom sheet), menus use the M3 default `surfaceContainer` |
| `--card-surface` (user bubble) | `surfaceContainerHigh` |
| `--muted`, `--secondary` (chips, `+N -N` pill, `Files: N` badge) | `surfaceContainerHighest` |
| composer surface (`surface-2`) | `surfaceContainerHigh` |
| code block / tool code (`background` 60 %) | `surfaceContainer` |
| inline code chip | `surfaceContainerHigh` |
| `--foreground` | `onSurface` |
| `--muted-foreground` | `onSurfaceVariant` |
| thinking text: `color-mix(foreground 75 %, muted-foreground)` | `lerp(onSurfaceVariant, onSurface, 0.75f)` |
| `--border` | `outlineVariant` |
| `--input` (field borders) | `outline` |
| `--primary` / `--primary-foreground` | `primary` / `onPrimary` |
| `--accent` / `--accent-foreground` (selected row, hover) | `secondaryContainer` / `onSecondaryContainer` (M3 drawer/rail indicator) |
| `--surface-hover`, `--surface-hover-lighter` | the M3 state layer (ripple and hover overlay). Never paint it by hand |
| `--ring` (focus) | 2dp `primary` outline on keyboard focus only (section 8) |
| `--destructive`, `--error-*` | `error`, `errorContainer`, `onErrorContainer` |
| `--success-*`, `--chart-2` | `ClineColors.success.*` |
| `--warning-*`, amber/yellow/orange literals | `ClineColors.warning.*` |
| `--info-*`, blue-500 literals | `ClineColors.info.*` |
| `--brand-*` | brand hero art only (section 1.7 "Brand hero art"), never UI chrome |

### 1.5 Extended semantic colours (`ClineColors`)

Every status colour has a **`color`** role (text and icons, 4.5:1 or better on every
surface container), an **`onColor`**, a **`container`** with its **`onContainer`**, and a
**`dot`** role for 8dp status dots, which are non-text and need 3:1. Hue and chroma come
from the desktop hues: success `#30A46C`, warning amber-500 `#F59E0B`, info blue-500
`#3B82F6`. `color` and `dot` come from a chroma-48 palette (56 for warning and info).
Containers come from a softer palette (chroma 24 / 32 / 28), so filled chips do not glare.

| Group | Role | Light | Dark |
| --- | --- | --- | --- |
| success | color | `#006D42` | `#76DAA1` |
| | onColor | `#FFFFFF` | `#003920` |
| | container | `#C4ECD0` | `#2B4E39` |
| | onContainer | `#002111` | `#C4ECD0` |
| | dot | `#198856` | `#5ABE87` |
| warning | color | `#855300` | `#FFB95F` |
| | onColor | `#FFFFFF` | `#472A00` |
| | container | `#FFDDB8` | `#643F07` |
| | onContainer | `#2A1700` | `#FFDDB8` |
| | dot | `#A76A00` | `#EC9914` |
| info | color | `#1C5BB9` | `#ADC6FF` |
| | onColor | `#FFFFFF` | `#002E6A` |
| | container | `#D8E2FF` | `#35466A` |
| | onContainer | `#051A3C` | `#D8E2FF` |
| | dot | `#3E75D4` | `#81AAFF` |
| diff | addedLine (successContainer @ 60 % over surfaceContainerLow) | `#D7EFDF` | `#23352C` (@ 50 %) |
| | removedLine (errorContainer @ 60 % / 50 %) | `#FAE4E3` | `#570E14` |
| | addedText / addedMarker | = success.color | = success.color |
| | removedText / removedMarker | = `error` | = `error` |
| terminal (same in both themes, as in the desktop's `bg-black/90 text-zinc-100`) | background | `#0E0E12` | `#0E0E12` |
| | foreground | `#E4E4E7` | `#E4E4E7` |
| | cursor | `#E4E4E7` | `#E4E4E7` |

ANSI palette for `terminal`, indices 0 to 15, contrast on `#0E0E12` shown in brackets:
`#52525B`(2.5, dim black by design) `#F87171`(7.0) `#4ADE80`(11.1) `#FACC15`(12.6)
`#60A5FA`(7.6) `#C084FC`(7.3) `#22D3EE`(10.7) `#E4E4E7`(15.2) `#71717A`(4.0)
`#FCA5A5`(10.1) `#86EFAC`(13.7) `#FDE047`(14.6) `#93C5FD`(10.7) `#D8B4FE`(10.9)
`#67E8F9`(13.3) `#FAFAFA`(18.5). Bold maps 0–7 to 8–15. 256-colour and truecolour
SGR values are rendered as given.

```kotlin
@Immutable data class StatusColors(val color: Color, val onColor: Color, val container: Color, val onContainer: Color, val dot: Color)
@Immutable data class ClineColors(
    val success: StatusColors, val warning: StatusColors, val info: StatusColors,
    val diffAddedLine: Color, val diffRemovedLine: Color,
    val terminalBackground: Color, val terminalForeground: Color, val ansi: List<Color>, // 16 entries
)
val LocalClineColors = staticCompositionLocalOf { ClineLightColors }
// ClineTheme provides ClineLightColors or ClineDarkColors with the same light/dark decision as the ColorScheme.
// Access: ClineTheme.colors.success.dot  (object ClineTheme { val colors @Composable get() = LocalClineColors.current })
```

### 1.6 Semantic status mapping (one table, used everywhere)

Desktop rule (`lib/session-status.ts`): "Keeping the mapping in one place means a session
cannot look green in one view and grey in another." Implement this as
`fun SessionVisual.of(status, flags): SessionVisual` in `ui/components/SessionStatus.kt`.
No other file may choose a status colour.

The kernel status comes from `session.updated {session|snapshot}.status`, from the
`session.list` records, and from the `run.*` terminal events. The app flags are derived
from `approval.requested`/`approval.resolved`, from
`capability.requested` (ask_question) and its response, from the in-flight
`session.create`, and from a per-session "last seen sequence".

| Visual state | Derived from | Dot (8dp) | Icon where one is shown | Label (sessions screen, details) | TalkBack |
| --- | --- | --- | --- | --- | --- |
| Failed | status `failed`, or `run.failed` | `error` | `error` (filled) in `error` | "Failed" | "Session status: failed" |
| Approval required | open `approval.requested` | `warning.dot` | `gpp_maybe` in `warning.color` | "Approval required" | "Session status: approval required" |
| Question asked | open `capability.requested` with `capabilityName == "tool_executor.askQuestion"` | `warning.dot` | `contact_support` in `warning.color` | "Question asked" | "Session status: question asked" |
| Pending | status `pending` | `warning.dot` | `pending` in `warning.color` | "Pending" | "Session status: pending" |
| Starting | `session.create` in flight; kernel "connecting" | `warning.dot`, **pulsing** (section 6) | none | "Starting" | "Session status: starting" |
| Running | status `running` | `success.dot` | 16dp `CircularProgressIndicator` | "Running" | "Session status: running" |
| Unread | idle-ish (idle/completed/aborted) and new events since the session was last shown | `info.dot` | none | "Unread" | "Session status: unread" |
| Completed | status `completed` | none (desktop hides neutral) | `check_circle` in `success.color` | "Completed" | "Session status: completed" |
| Cancelled | status `aborted`, or `run.aborted` | none | `cancel` in `onSurfaceVariant` | "Cancelled" | "Session status: cancelled" |
| Idle | status `idle` | none | none | "Idle" | "Session status: idle" |

Precedence, highest first: Failed > Approval required > Question asked > Pending >
Starting > Running > Unread > Completed / Cancelled / Idle. Pending actions win over
runtime status, and unread applies only to idle sessions, as in the desktop's
`AgentSessionRow`. The visible copy uses "Cancelled" for the kernel's `aborted`,
because that is the desktop's word (`ChatSessionStatus`, agents roster).

**Kernel connection** (`desktop-client.ts` states plus the Termux case) uses the same
dot component:

| State | Trigger | Dot | Line 1 | Line 2 (`error` colour) |
| --- | --- | --- | --- | --- |
| connected | WebSocket open, `client.register` replied | `success.dot` | "Cline Hub @25463" (port from `/health.url`) | none |
| connecting | first connect, `cline hub start` running | `warning.dot`, pulsing | "Connecting..." | none |
| reconnecting | socket dropped, backoff loop | `warning.dot`, pulsing | "Reconnecting..." | none |
| unavailable | `/health` refused after `hub start` | `outline` | "Cline Hub @unknown" | "Cline Hub is not connected." |
| Termux asleep | `/health` fails and Termux resolvable | `outline` | "Termux asleep" | "Start kernel to continue." |

### 1.7 Other semantic colour decisions

- **Tool kinds have no hue.** The desktop draws every tool row in `muted-foreground` and
  keeps colour for status (`agent-chat.css`: "the spinner alone signals activity. Errors
  go red"). Kinds differ only by icon (section 7.2). Rows use `onSurfaceVariant`; error
  rows use `error`.
- **Diff add/remove.** Counts (`+N` / `-N`) everywhere use `success.color` / `error`
  in `ClineMono.small`. The desktop's `AgentChangedFile` header draws additions in
  `--primary`, but its conversation header and tool rows use green. The app uses
  `success.color` everywhere so that one colour has one meaning. This is the only
  deliberate colour deviation.
- **Approval.** The surface is the sheet (`surfaceContainerLow`). Each request card is
  an `OutlinedCard` with a 1dp border in `warning.color.copy(alpha = 0.4f)`. The header
  icon `gpp_maybe` uses `warning.color`. Approve is `Button` (`primary`) and Reject is
  `OutlinedButton`. These match the desktop's `border-amber-400/40` + `ShieldAlert
  text-amber-500` + primary/outline buttons.
- **Errors.** Use the M3 `error` roles. Error messages in the transcript are a `Surface`
  in `errorContainer` with `onErrorContainer` text (desktop `error-surface` +
  `error-text`). Inline field and action errors are `error` text in `bodySmall`.
- **Context ring.** Below 50 % use `primary`, from 50 % `warning.color`, from 75 %
  `error`. The track is `onSurfaceVariant @ 20 %`. In the details sheet, the uncached
  input segment is `primary`, the cached segment `primary @ 60 %` and the output segment
  `info.color`.
- **Brand hero art.** Brand literals (`--brand-*`, the What's new hero's oklch
  conversions) appear only in hero art and only under the brand scheme, as resources rather
  than `Color(0x…)` literals. With dynamic colour the same art uses `primary` →
  `tertiary` (and `primaryContainer` for a base). Home has no gradient text: the desktop's
  `AgentHeroHeading` (gradient cycling verb) is no longer mounted (upstream 35583ee8b,
  "interactive welcome hero graphic"), so the welcome hero is the bot illustration alone
  (section 4.2 WelcomeHero).
- **Notification accent.** `NotificationCompat.Builder.setColor()` uses the voice app's
  `Tone.notificationColor` approach: `system_accent1_600` / `_200` on API 31+, otherwise
  `#6249C2` (light) / `#CBBEFF` (dark). Errors use `#B3261E` / `#F2B8B5`.

### 1.8 Contrast report (brand fallback, WCAG ratio)

| Pair | Light | Dark |
| --- | --- | --- |
| onPrimary / primary | 6.46 | 7.72 |
| onPrimaryContainer / primaryContainer | 7.21 | 7.21 |
| onSecondaryContainer / secondaryContainer | 7.26 | 7.26 |
| onTertiaryContainer / tertiaryContainer | 7.23 | 7.23 |
| onErrorContainer / errorContainer | 7.24 | 7.24 |
| onSurface / surface | 16.26 | 14.31 |
| onSurface / surfaceContainerHighest | 13.22 | 9.53 |
| onSurfaceVariant / surface | 8.92 | 10.86 |
| onSurfaceVariant / surfaceContainerHighest | 7.25 | 7.23 |
| primary / surface | 6.15 | 10.88 |
| primary / surfaceContainerHigh | 5.28 | 8.46 |
| primary / surfaceContainerHighest | 5.00 | 7.24 |
| error / surfaceContainerHigh | 5.28 | 8.48 |
| inversePrimary / inverseSurface (snackbar action) | 7.68 | 5.00 |
| outline / surface (field borders, non-text ≥ 3) | 4.25 | 5.83 |
| success.color / surface · / surfaceContainerHigh | 6.12 · 5.26 | 10.84 · 8.44 |
| success.onContainer / container | 13.26 | 7.22 |
| success.dot / surface · / surfaceContainerLow | 4.25 · 4.04 | 8.06 · 7.45 |
| warning.color / surface · / container | 6.18 · 5.04 | 10.88 · 5.48 |
| warning.dot / surfaceContainerLow | 4.03 | 7.43 |
| info.color / surface | 6.16 | 10.83 |
| info.dot / surfaceContainerLow | 4.03 | 7.43 |
| onSurface / diff addedLine · removedLine | 14.08 · 14.06 | 10.05 · 10.99 |
| success.color (marker) / addedLine | 5.30 | 7.61 |
| terminal foreground / background | 15.18 | 15.18 |
| launcher foreground `#FCFCFD` / background `#1C1C24` | 16.50 | n/a |

`outlineVariant` (1.62 / 1.97) is used only for decorative dividers and never as the
only boundary of an interactive control.

**Rule:** never set `primary` text on `primaryContainer`. Use `onPrimaryContainer`.
Under dynamic colour the voice app's stop-based scheme keeps M3 tone pairs, so the same
role pairings stay valid. Do not tint text with arbitrary `alpha` below 0.87 on
`onSurfaceVariant`. The desktop uses `muted-foreground/70` for timestamps, but the app
uses `onSurfaceVariant` at full opacity, because the desktop value fails 4.5:1.

---

## 2. Typography

### 2.1 Type scale

This is the M3 baseline scale with the voice app's two CJK changes, copied from
`ui/theme/Theme.kt`:

1. `letterSpacing = 0.sp` on every style, because M3 tracking spreads Hanzi apart.
2. `titleMedium`, `titleSmall`, `labelLarge`, `labelMedium` and `labelSmall` use
   `FontWeight.Normal` instead of Medium, because Samsung's CJK fallback has no Medium
   face. Emphasis anywhere uses `FontWeight.SemiBold`, which both scripts have.

Text uses the system face (`FontFamily.Default`: SamsungOne/Roboto plus the system CJK
fallback). Inter is **not** bundled. The desktop uses Inter for the web, and on Android
the platform face is the native equivalent and keeps CJK metrics consistent.

| M3 role | Size / line | Weight | Used for (desktop equivalent) |
| --- | --- | --- | --- |
| displaySmall | 36 / 44 | SemiBold (override) | Onboarding welcome title on medium/expanded, large account figures |
| headlineMedium | 28 / 36 | SemiBold (override) | Onboarding welcome and plain titles on compact. Home has no visible heading (section 4.2 WelcomeHero) |
| headlineSmall | 24 / 32 | Normal | Empty-state titles on full screens, sheet titles on expanded |
| titleLarge | 22 / 28 | Normal | Top app bar titles, dialog titles (desktop `PageHeader` `text-3xl`) |
| titleMedium | 16 / 24 | Normal | Conversation title in the app bar, sheet titles, card titles, question text |
| titleSmall | 14 / 20 | Normal | Section headers ("Pinned", "Scheduled", "Tasks"), list group headers |
| bodyLarge | 16 / 24 | Normal | Assistant markdown body, user bubbles, list headlines, text fields |
| bodyMedium | 14 / 20 | Normal | Tool row labels, work row, thinking body, supporting text, notices |
| bodySmall | 12 / 16 | Normal | Errors under controls, meta lines, captions |
| labelLarge | 14 / 20 | Normal | Buttons, chips, drawer items, session rows in the drawer |
| labelMedium | 12 / 16 | Normal | Timestamps, counts beside section headers, badges |
| labelSmall | 11 / 16 | Normal | Overlines ("Output", "Input"), keyboard hints |

Markdown inside messages comes from the voice app's renderer
(`ui/markdown/Markdown.kt` + `MarkdownModel.kt`, copied). It is the body face at a
larger size and SemiBold: h1 22/28, h2 19/26, h3 17/24, h4 16/24. Block spacing is
12dp, code/table/rule spacing 16dp, and headings have 20–24dp above and 8dp below.
Links are `primary` and underlined.

### 2.2 Monospace (`ClineMono`)

The desktop uses Geist Mono for code, commands, diffs, file names inside tool labels,
model ids, token and cost numbers, and `+N -N`. The app bundles **Geist Mono** (SIL OFL
1.1) as static TTFs `res/font/geist_mono_regular.ttf` (400) and `geist_mono_medium.ttf`
(500), with the license at `app/src/main/assets/licenses/GeistMono-OFL.txt`. If the files
are absent in a build, `ClineMono` must fall back to `FontFamily.Monospace`. CJK inside
code falls back to the system CJK face automatically.

| Style | Size / line | Weight | Used for |
| --- | --- | --- | --- |
| `ClineMono.body` | 13 / 20 | 400 | code blocks, command output, approval detail (`inputJson`), tool details, diff lines |
| `ClineMono.label` | 13 / 20 | 400 | `code: true` segments inside a tool label (`ToolLabelPart`). The label row stays `bodyMedium` |
| `ClineMono.small` | 12 / 16 | 400 | `+N -N`, token/cost values, model ids in details, paths in Changes headers, "Files: N" |
| `ClineMono.tiny` | 11 / 16 | 400 | diff gutter line numbers, letter keys (A, B, C) in ask-question |

All `ClineMono` styles use `letterSpacing = 0.sp`, `fontFeatureSettings = "calt 0"` (no
ligatures) and `TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))`.

### 2.3 CJK and mixed-script rules

- Never set `letterSpacing` other than 0.
- Use `FontWeight.SemiBold` for emphasis. Never use Medium (500) on mixed text. Geist
  Mono Medium is allowed only on pure-ASCII mono labels such as counters.
- Line breaking: `LineBreak.Paragraph` for markdown prose, `LineBreak.Simple` for
  single-line labels. Hyphenation off.
- A user line may take up to the bubble max width (section 4, MessageBubble). Long
  unbroken tokens such as URLs and paths wrap anywhere, like the desktop's
  `overflow-wrap: anywhere`: use `softWrap = true` and pass a zero-width-space-aware
  `AnnotatedString` for paths, as the voice app's `Markdown.kt` does.
- Ellipsized single-line labels (tool rows, session titles) use
  `overflow = TextOverflow.Ellipsis, maxLines = 1`. The full text is always available in
  the expanded view and in the TalkBack `contentDescription`.

### 2.4 Font size setting

This copies the desktop's `lib/app-font-size.ts`: sizes 12–20 with default 15. Settings >
Appearance > "Font size" shows a stepper with `IconButton`s "Decrease font size" and
"Increase font size" and the value in `labelLarge` ("15"). The app multiplies every
`ClineTypography` and `ClineMono` size and line height by `n / 15f` (range 0.8–1.333).
This multiplier stacks on top of the system font scale and is applied by building the
`Typography` in `ClineTheme`, never by overriding `LocalDensity`. It is stored in
DataStore under `appearance.fontSize` (Int).

---

## 3. Shape, elevation, spacing, touch targets

### 3.1 Shape (`ClineShapes` = M3 defaults)

| Token | Corner | Used for |
| --- | --- | --- |
| extraSmall | 4dp | inline code chip, ask-question letter key, `Files: N` badge |
| small | 8dp | tool code blocks, diff hunk containers, menu items' focus, image thumbnails |
| medium | 12dp | markdown code blocks (voice precedent), error message surface, cards, approval request card |
| large | 16dp | user message bubble, composer container, prompt-queue strip, setup notice |
| extraLarge | 28dp | bottom sheets (top corners, M3 default), dialogs (M3 default 28dp), search bar |
| full (`CircleShape`) | 50 % | status dots, send/stop buttons, scroll-to-latest button, avatars |

The desktop's 0.5rem radius (about 7.5px) is translated to the M3 scale above, not
copied.

### 3.2 Elevation

The app uses M3 tonal elevation, with no shadows except where the components draw them
by default (FAB, menus).

| Level | dp | Surfaces |
| --- | --- | --- |
| 0 | 0 | screens (`surface`), transcript, lists |
| 1 | 1 | `ModalDrawerSheet`, `ModalBottomSheet` (container `surfaceContainerLow`), cards |
| 2 | 3 | `NavigationBar` (not used), composer (container `surfaceContainerHigh`, no shadow), scrolled top app bar (container stays `surface`; a 1dp `outlineVariant` hairline fades in over 150ms, voice `PageScaffold`) |
| 3 | 6 | FAB, `SmallFloatingActionButton` (scroll to latest) |
| 4/5 | 8/12 | none |

### 3.3 Spacing scale (`Spacing`)

The scale is a 4dp grid: `xxs 2`, `xs 4`, `s 8`, `m 12`, `l 16`, `xl 20`, `xxl 24`,
`xxxl 32`, `huge 48`.

| Context | Value |
| --- | --- |
| Screen horizontal gutter | compact 16dp, medium and expanded 24dp |
| Transcript max content width | 840dp centred (desktop `max-w-(--breakpoint-lg)` = 64rem). The composer shares the same column |
| Gap between transcript blocks | 4dp (A24: the desktop's `gap-2` is 8px; the compact rows need less). A user message adds 16dp above (desktop `mt-4`). An assistant answer directly under its working rows pulls up by the gap, so it sits flush (desktop `-mt-2`) |
| Tool rows inside a run / inside an expanded work block | 0dp (rows are 40dp tall, A24) |
| Disclosure rail | rail line under the icon's centre, 9dp from the row start (half the 18dp icon), content inset 12dp past it, so 21dp (A24; the voice `WorkRows` rail was 7.5dp and 16dp + 7.5dp) |
| Compact row tokens (A24) | `DisclosureRowTokens` (`ui/chat/rows/TranscriptContext.kt`) holds the density metrics of the tool, thinking, reasoning and "Worked for…" rows and the "Thinking..." indicator in one place: height 40dp, icon 18dp, spinner 14dp (2dp stroke), icon-to-label gap 8dp, label `bodySmall` with `ClineMono.small` code parts, block gap 4dp, rail line 9dp and content 12dp past it. The owner reviews 40dp on the device; 44dp is the fallback, a change of `Height` alone |
| List items | M3 `ListItem` defaults: 16dp horizontal padding, 56/72/88dp heights |
| Sheet content | 24dp horizontal, 16dp between groups, a bottom inset of `navigationBars` plus 16dp |
| Card internal padding | 16dp (approval card 12dp as desktop `p-3`, scaled up to 16dp on medium and above) |

### 3.4 Touch targets

- Every interactive element has a touch target of at least **48×48dp**
  (`Modifier.minimumInteractiveComponentSize()`), except the dense transcript rows
  below. This holds even where the visible glyph is smaller: the 20dp context ring, the
  16dp copy-path icon, and the 8dp status dot when it is tappable (it never is on its own).
- Changed-file headers are full-width rows with `heightIn(min = 48.dp)`.
- Tool rows, work rows and the reasoning trigger are dense full-width rows (A24): 40dp,
  the Material 3 dense-list height. The whole row is the touch target, 40dp tall (allowed
  for dense lists) and as wide as the column. `DisclosureRowTokens.Height` sets it; the
  reviewed fallback is 44dp.
- Session rows in the drawer are 48dp tall. Sessions screen rows are 72dp (three-line
  `ListItem`).
- Adjacent targets do not overlap. Icon buttons in a row are spaced so that their 48dp
  boxes touch but do not intersect.
- Destructive actions are never the default focus and never sit in the corner nearest
  the thumb without a confirm. "Delete session?" always asks, as the desktop does.

---

## 4. Component inventory

Each row names the desktop component, the M3 component and Compose API, sizes, states,
kernel bindings and copy. Strings are the desktop's verbatim unless marked *(Android)*.
They live in `res/values/strings.xml` (section 9).

### 4.1 Core inventory

| # | Desktop component (source) | M3 translation / Compose API | Sizes | States | Kernel binding | Copy (exact) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **ThreadItem / AgentSessionRow** (`webview/components/agent-sidebar.tsx`, `ui/components/agent-session-row.tsx`) | `NavigationDrawerItem`-styled custom row in the drawer list pane: `Surface(onClick, shape = CircleShape)` with `Modifier.combinedClickable(onLongClick)`. Selected uses `secondaryContainer` (M3 drawer indicator) | height 48dp. Label `labelLarge` 1 line, ellipsis. Trailing: status dot 8dp, pin glyph `push_pin` 16dp (filled), time `labelMedium` `onSurfaceVariant`. Leading: `schedule` 16dp when scheduled | default · selected (active session) · pressed (state layer) · disabled while a menu action is pending · rename mode: an `OutlinedTextField` replaces the row, Enter commits, Back cancels | `session.list {limit, rootOnly:true}`; `session.created`/`session.updated` events; `session.update {title, metadata.pinned}`; `session.delete`. Status from section 1.6 | TalkBack: "<title>, <status label>, pinned, <time>". Long-press menu: section 4.1 #15. Empty: "No sessions found in history." Loading: "Loading session history..." Paging: "Show more", "Show more in <project>" |
| 2 | **ToolCallRow / ToolMessageBlock** (`webview/.../messages/tool-message-block.tsx`, `ui/components/agent-chat/index.tsx` `ToolActivity*`, `tool-summary/index.ts`) | Custom disclosure row: `Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(role = Role.Button))` + `AnimatedVisibility` body under a rail; every size from `DisclosureRowTokens` (A24, section 3.3) | Leading 18dp icon slot. While pending or running it holds a `CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = LocalContentColor)` so the label never shifts. Label `bodySmall` with `ClineMono.small` code parts, 1 line. Trailing `+N -N` `ClineMono.small`. The desktop hides the chevron on tool rows (`showDisclosureIcon={false}`), and the app does too | pending/running (spinner + shimmer label, section 6) · success (kind icon) · error (`error` icon, label in `error`) · expandable or not (no body means no click and no ripple) · collapsed/expanded. Starts collapsed, always (A23): the desktop's auto-open rule (file diffs, `submit_and_exit` text, output media, running with output or a detachable command) is dropped. Only the user's tap opens a row, and it stays open while the row updates | `tool.started {toolCallId, toolName, input}`, `tool.updated {update}`, `tool.finished {output, error?}`. Ignore the hook-derived duplicates without ids. History: `session.messages` `tool_use`+`tool_result` pairs. `run.proceed_while_running {sessionId, toolCallId}` | Labels from `buildToolSummary` (port `tool-summary/*.ts` to Kotlin 1:1): "Read file `app.tsx (10–80)`", "Read 3 files", "Ran command `bun test`", "Running command", "Edited file", "Searched", "Explored 2 searches", "Fetched", "Spawned agent: …", "Used skill …", "Asked \"…\"", "Called MCP tool", "Completing scheduled task" / "Scheduled task completed" / "Scheduled task failed". Body: details (`$ <command>` for commands, mono; the `$ <command>` lines of a multi-command output print the call input's command, never the kernel's 200-character echo, trial row 18), "Output" overline, "Input" overline (debug only), "Proceed while running", error "Could not detach the running command." |
| 3 | **ReasoningBlock / ThinkingBlock** (`webview/.../messages/reasoning-block.tsx`, `ui/.../agent-chat/index.tsx`) | Same disclosure row as #2 with a `neurology` icon, plus a body capped at **192dp** height (desktop `max-h-12rem`) and scrollable (`verticalScroll`, nested-scroll aware) | Row 40dp, 18dp icon (A24). Label `bodySmall` `onSurfaceVariant`. Body `bodyMedium`, colour `lerp(onSurfaceVariant, onSurface, .75f)`, markdown | streaming (label "Thinking" with shimmer) · done ("Thought for Ns") · redacted (body "[redacted]") · collapsed by default · expanded | `reasoning.delta {text, redacted}`, `reasoning.finished {reasoning}`. Duration = finished − first delta | `formatThoughtLabel`: "Thinking" while the duration is unknown; "Thought for 0s" when it is exactly 0; otherwise "Thought for {max(1, round(ms/1000))}s" |
| 4 | **WorkBlock / WorkActivity** (`webview/.../messages/work-block.tsx`) | Disclosure row. The chevron `expand_more` is rotated −90° when collapsed (pointing to the end) and rotates to 180° when expanded (desktop: −90° → −180°). The body re-shows the run's rows at transcript level with no rail | Row 40dp, label `bodySmall` `onSurfaceVariant`, 18dp chevron (A24) | collapsed (default after a run ends) · expanded | built client-side when `run.completed` arrives: tool rows + thinking + narration of that run fold into one row. Live runs show the rows unfolded | `formatWorkActivityLabel`: "Worked for 4m 12s and made 14 tool calls", "Worked for 3s", "Made 1 tool call", "Worked". Duration format `formatWorkDuration`: "3s", "4m 12s", "1h 3m", "1h" |
| 5 | **MessageBubble** (`webview/.../messages/message-bubble.tsx`, `ui/.../agent-chat.css`) | **User**: `Surface(color = surfaceContainerHigh, shape = large)` aligned to the end. **Assistant**: no container, markdown at full column width. **Error**: `Surface(color = errorContainer, shape = medium)`. **System/status**: `bodyMedium` `onSurfaceVariant` | User: max width 85 % of the column on compact, 60 % on medium and above (desktop 85 % / 50 % at ≥48rem). Padding 16dp × 10dp (voice `ChatRows`). Images: thumbnails up to 225dp, `shapes.small`. Action row: 40dp-high icon buttons (48dp targets) + time `labelMedium` | user · assistant streaming (no actions) · assistant done · last assistant (actions always visible, desktop `isLastAssistantMessage`) · error with credential action · action pending (spinner in the button) · action error (`bodySmall` `error`, end-aligned) · copied (icon `check` for 2s) | `assistant.delta/finished`, `assistant.media`, `session.messages` history. Edit = `session.restore` + new prompt. Restore = `session.restore {checkpointRunCount}`. Fork = `session.messages` → `session.create {initialMessages, metadata.fork}` | Content descriptions: "Copy user message" / "Copied user message", "Edit user message", "Restore checkpoint", "Copy assistant message" / "Copied assistant message", "Fork session". Tooltips: "Copy message", "Edit message and restart from this point", "Copy raw assistant output", "Fork session - copy full message history into a new session". Credential buttons: "Sign in to Cline", "Open API providers". Attachments: "Expand attachment N", alt "Attachment N" |
| 6 | **ChatInputBar / AgentComposer** (`webview/.../chat/chat-input-bar.tsx`, `ui/components/agent-composer.tsx`) | `Surface(shape = large, color = surfaceContainerHigh, border = 1dp outlineVariant` (primary when focused, desktop `focus-within:border-primary/50`)`)` containing (a) a `BasicTextField` (`TextFieldDefaults.DecorationBox`) and (b) a settings row: `IconButton` attach, `SegmentedButton` Plan/Act, model chip (#7), reasoning chip, spacer, usage ring (#8), send/stop `FilledIconButton` | Min height 56dp. Text field 1 → 5 lines (desktop `PROMPT_INPUT_MAX_ROWS = 5`), then it scrolls. Settings row 48dp. Chips are `AssistChip`, 32dp visible. Send/stop: 40dp `FilledIconButton` (48dp target), icon 20dp | empty (send disabled) · has text (send enabled) · busy (stop shown, send hidden unless there is a draft, placeholder changes) · busy with queue · readOnly (session not live / kernel down: field disabled, a "Start kernel" `TextButton` replaces the settings row) · attachments (an `InputChip` per image above the field) · model lacks image support (`bodySmall` `error` notice) · IME open | `session.send_input {prompt, mode, delivery:'queue'\|'steer', attachments.userImages}` with `timeoutMs:null`; `run.abort {sessionId}`; `session.update_connection` for the chips; `session.pending_prompts` | Placeholders: welcome "Ask to make changes, @mention files, reference #PRs, or run /commands."; conversation "Enter your question or type / for commands or @ for context"; busy "Agent is working... submit to queue another message"; busy with queue "Agent is working... submit to queue another message, or Enter to send the first message from the queue". CDs: "Send message", "Stop agent", "Attach files", "Remove <name>", "Thinking level". Tooltips: "Send (Enter)", "Stop the agent (Esc)". Errors: "This model doesn’t support the attached images. Remove them or choose a model that supports images before sending." Snackbar "Add a message to go with your attachments" / "Describe what you want Cline to do with the attached files before sending." Slash menu (no "/fork", A19): "/team" "Start the task with an agent team", "Skill command", "Workflow command", "Plugin command", "Loading commands...", "No matching commands". Mentions: "Searching files...", "No matching files" |
| 7 | **ModelSelector** (`chat-input-bar.tsx` `ModelSelector`, `SearchCombobox`) | Trigger: an `AssistChip` with `memory` icon and the label "<model>". On compact the label truncates to 16 characters, and the full "Provider / Model" goes in the TalkBack label. Picker: `ModalBottomSheet` with a `SearchBar`-style `OutlinedTextField` and two sections. "Provider" is a `LazyRow` of `FilterChip`s, configured providers first, ending with an `AssistChip` "Set up another provider". "Model" is a `LazyColumn` of `ListItem`s grouped under section headers (Recommended, Free, "Current model") with a trailing `check` on the selected one. Reasoning: `DropdownMenu` anchored to a second `AssistChip` (`neurology` + effort label) | Sheet up to 90 % height. Model rows 56dp: headline name (`bodyLarge`), supporting id (`ClineMono.small`), trailing capability badges (`Badge` "Images", "Reasoning") | loading catalog (`LinearProgressIndicator` under the search) · no providers (empty: "No providers found." + "Set up another provider") · no models ("No models found.") · busy (chips disabled while a turn runs, desktop `disabled={isBusy}`) · reasoning disabled when the model lacks it (chip disabled, tooltip) · catalog fallback (bundled list + free-text "Use model id" row) | dashboard `list_provider_catalog`, `loadModels {providerId}`; fallback bundled `FALLBACK_PROVIDER_MODELS`; `session.update_connection {providerId, modelId, thinking, reasoningEffort}`. Local persistence keys mirror `cline.code.model-selection.v1` and `cline.code.reasoning-selection.v1` | "Model and provider" (CD), "Provider", "Model", "Search providers", "Search models", "No providers found.", "No models found.", "Current model", "Set up another provider", "Close model selector". Effort: "None", "Low", "Medium", "High", "Extra" (default "Low"). Tooltip when unsupported: "The selected model does not report reasoning support" |
| 8 | **TokenUsageRing / AgentContextUsage** (`webview/.../chat/token-usage-ring.tsx`, `ui/components/agent-context-usage.tsx`) | `IconButton` holding a `Canvas` ring. Its details open as a `ModalBottomSheet` (no anchored popover on touch) | Ring 20dp, stroke 3.5dp, round cap, starting at −90°. Shown at 65 % alpha at rest, as in the desktop (`opacity-65`) | hidden when `contextWindow` is unknown or the token total is 0 (desktop returns null) · <50 % · ≥50 % · ≥75 % (section 1.7) | `usage.updated {delta, totals, aggregateUsage}`, `session.get {includeSnapshot:true}`; `contextWindow` from the model catalog or a bundled table | CD: "Context window: 12,345 of 200,000 tokens used (6%)". Sheet: "Context window" with a "12.3k / 200k (6%)" mono value, a 4dp segmented bar, rows "Input tokens", "Output tokens", "Cached tokens", "Cost" (`formatCostUsd`: below $0.01 "$0.0042", below $1 "$0.123", otherwise "$1.23") |
| 9 | **ApprovalCard / ToolApprovalPanel** (`ui/components/agent-approval-card.tsx`, `webview/.../messages/tool-approval-panel.tsx`) | `ModalBottomSheet` (`skipPartiallyExpanded = false`). Header row `gpp_maybe` + title. A `LazyColumn` of `OutlinedCard`s, one per request. Each card has the title (tool name, `titleMedium`), meta (`schedule` + time, `labelMedium`), description `bodySmall`, detail as a mono block (`surfaceContainer`, `shapes.small`, max 176dp then scroll), an error line, and a `Row` with `Button` "Approve" + `OutlinedButton` "Reject". The footer is a `TextButton` "Stop agent". Dismissing the sheet (swipe or back) **does not reject**: it collapses to a persistent `ApprovalBanner` above the composer (`Surface warning.container`, `onContainer` text, button "Review") | Sheet max width 640dp (M3 default). Buttons 40dp high (48dp targets), Approve end-aligned on compact | waiting · approving ("Approving...", spinner, both disabled) · rejecting ("Rejecting...") · error (inline `error` text, buttons enabled again) · multiple requests (stacked cards) · resolved elsewhere (`approval.resolved` removes the card, and the sheet closes when none remain) · cancelled by abort | `approval.requested {approvalId, toolCallId, toolName, inputJson, iteration}` (dedupe by `approvalId`, re-issued on every subscribe); `approval.respond {approvalId, approved, reason?}`; `approval.resolved`; `run.abort`. Reject reason: "This tool call was rejected by the user and not executed." | "Tool approval required", "Review each tool call and approve or reject it before execution.", "Request <id> · Iteration <n>", "Pending now" (bad timestamp), "Approve", "Reject", "Approving...", "Rejecting...", "Stop agent". Banner *(Android)*: "Tool approval required", button "Review" |
| 10 | **AskQuestion** (`ui/components/agent-ask-question.tsx`) | `ModalBottomSheet`. Question `titleMedium`, "Select all that apply." `bodyMedium` for multiple choice, description `bodyMedium` `onSurfaceVariant`, optional meta `Badge`. Options are `Surface(onClick)` rows, min 56dp, with a leading letter key (24dp, `shapes.extraSmall`, 1dp `outline` border, `ClineMono.tiny`) and the label `bodyLarge`. Selected (multiple) uses `secondaryContainer` with a trailing `check`. Footer: `OutlinedTextField` "Or type your own answer…" (single choice only) + `FilledTonalButton` "Submit" | Options list scrolls inside the sheet. Footer pinned above the IME (`imePadding`) | idle · single choice: a tap answers immediately (desktop) · multiple: toggle, then Submit (disabled until at least one is chosen) · custom text (Submit enabled when non-blank) · sending ("Sending…", all disabled) · error (`role=alert` → `LiveRegionMode.Assertive`) · cancelled because the app disconnected (sheet closes, snackbar) | `capability.requested {requestId, targetClientId, payload{executor:'askQuestion', args}}` → `capability.respond {requestId, ok:true, payload:{result}}` with `envelope.clientId = targetClientId`. The Hub cancels it if the app unregisters | "Follow-up question" (sheet pane title), "Answer options", "Select all that apply.", "Or type your own answer…", "Custom answer" (CD), "Submit", "Sending…". Hardware keyboard: letters A–Z pick, arrows move, Enter submits |
| 11 | **SessionsView row** (`webview/components/views/sessions/sessions-view.tsx`) | Compact and medium: three-line `ListItem`. headlineContent = title. supportingContent = "<workspace> · <model>" (`bodyMedium`) and a second line "<tokens in>/<tokens out> · <cost>" (`ClineMono.small`). leadingContent = 8dp status dot in a 24dp box. trailingContent = time `labelMedium` + overflow `IconButton` `more_vert`. Expanded (≥840dp): a table-like `Row` with weighted columns Title 3, Workspace 2, Model 2, Tokens 1, Cost 1, Time 1, Actions 48dp, plus a sticky header row (`labelLarge` `onSurfaceVariant`) | Row 72dp (compact) / 56dp (expanded). Pinned: `push_pin` 16dp before the title | default · pending action · rename (inline field with "Save title"/"Cancel rename") · deleting · empty "No sessions yet." · filtered empty "No sessions match the current filters." · loading (3 skeleton rows) | `session.list`, `session.get` for usage, `session.update`, `session.delete`, `session.search {query, limit}` | Column headers "Title", "Workspace", "Model", "Tokens", "Cost", "Time". Fallbacks: "No model", "No workspace", "-" (no tokens). Tokens use `formatCompactTokens` in lowercase as the desktop does ("12.5k/3.2k", "1.2m"). CD "Session actions for <title>", "Rename <title>". Menu: "Pin"/"Unpin", "Rename", "Fork", "Delete" |
| 12 | **Settings section** (`webview/.../settings/settings-view.tsx`, `views/page-layout.tsx`, `sections.ts`) | Settings home: `LazyColumn` of `ListItem`s (leading Material Symbol 24dp, headline, supporting summary, trailing chevron) grouped by `titleSmall` `primary` headers. Section pages: `PageScaffold` (voice `ui/Components.kt`: `MediumTopAppBar`, back arrow, hairline on scroll). The page description (desktop `PageHeader.description`) is the first item (`bodyMedium` `onSurfaceVariant`, 16dp gutters). Rows: `ListItem` + `Switch` / `RadioButton` / stepper; groups separated by 8dp + header | Rows 56dp (one line) / 72dp (two lines). Switches use M3 1.4 defaults with the `check` thumb icon | loading (`LinearProgressIndicator` under the app bar) · helper unavailable (inline `Surface warning.container` card: "Settings helper unavailable" *(Android)* + fallback actions) · saving (row disabled + 16dp spinner trailing) · error (snackbar) | per-page bindings live in the screen specs. Appearance is DataStore. Telemetry: dashboard `get_global_settings`/`set_telemetry_opt_out` | Home title "Settings". Description "Manage desktop preferences for this browser and CLI environment." becomes *(Android)* "Manage preferences for this app and the Cline kernel in Termux." Sections, in desktop order where present: "General" (becomes "Appearance" + "Notifications"), "Kernel" *(Android)*, "Providers", "Account", "Customize" ("Installed"), "Marketplace", "Schedules", "About". "Dark mode", "Font size", "App icon" copied |
| 13 | **HoverCard** (session overview `AgentSessionOverview`, logo Hub card in `agent-sidebar.tsx`) | No hover on touch. **Session overview** becomes the "Session details" `ModalBottomSheet`, opened from the row menu "Details" *(Android)*, from tapping the conversation title, or from the header overflow. Rows: label `bodyMedium` `onSurfaceVariant` / value `ClineMono.small` `onSurface`, two columns (`max-content` + flexible). Long-press a value to copy it. **Hub card** becomes the Kernel status sheet (screen spec), opened from the kernel chip in the drawer header or rail | Details rows 40dp visible, 48dp targets | loading · values missing (row omitted, as in the desktop `filter(Boolean)`) | `session.get {includeSnapshot:true}`; `/health`, `/version`, `hub.status` | Labels: "Schedule", "Run", "Workspace", "Branch", "Provider", "Model", "Tokens", "Cost", "Source". Hub: "Cline Hub @<port>", "Cline Hub is not connected.", "Version <x>", "Version unavailable" |
| 14 | **Tooltip** (`title=` attributes) | `TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(..) } })` on every icon-only button. It appears on long-press | M3 default | none | none | the desktop `title` strings (listed per component) |
| 15 | **Context menu** (`SessionContextMenuContent`, message actions) | `DropdownMenu` anchored at the touch point (`DpOffset` from the long-press position) **and** the same items behind an always-visible overflow `IconButton` where a row has one. `DropdownMenuItem(leadingIcon, text)`. Destructive items use `MenuDefaults.itemColors(textColor = error, leadingIconColor = error)` | Item height 48dp, menu width 200dp min (`widthIn(min=200.dp)`) | item pending (text "Renaming..."/"Forking..."/"Deleting...", leading 16dp spinner, all items disabled) | as #1 | "Pin" / "Unpin", "Rename" / "Renaming...", "Fork" / "Forking...", "Delete" / "Deleting...", "Details" *(Android)*. Every menu action is also exposed as a TalkBack custom action on the row |
| 16 | **Dialog / AlertDialog** (`ui/alert-dialog.tsx` usage) | Confirmations: `AlertDialog(title, text, confirmButton = TextButton(colors = error), dismissButton = TextButton)`. Editors (MCP server, schedule, add provider) on compact are full-screen dialogs: `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` hosting a `Scaffold` with `TopAppBar(navigationIcon = close, actions = TextButton("Save"))`. On medium and above they are a `BasicAlertDialog` with max width 560dp | M3 defaults (28dp corners, `surfaceContainerHigh`) | idle · confirming (confirm shows a spinner + "Deleting...", both disabled, dismiss blocked) · error (inline `bodySmall` `error`) | as the caller | "Delete session?", "This removes \"<title>\" from local history.", "Cancel", "Delete", "Deleting..."; checkpoint dialogs "Revert to this checkpoint?", "Edit and restart from here?" (screen spec) |
| 17 | **Toast** (`ui/toast.tsx`, `use-toast.ts`, `TOAST_LIMIT = 1`) | `Snackbar` via one app-wide `SnackbarHostState` (`LocalSnackbarHost`, voice). Each screen's `Scaffold` hosts it, so it sits above that screen's FAB or composer. Only one at a time: show with the voice helper `showReplacing` | M3 default. Two lines max: "<title>. <description>" | info (`SnackbarDuration.Short`) · error (`Long`) · actionable (`Indefinite` + action, e.g. "Retry") | none | per call site, e.g. "Could not steer queued message", "Switched to browser speech recognition" (not used), "Speech input failed" |

### 4.2 Additional components required by the screens

| Component | Desktop source | M3 / Compose | Key specs and copy |
| --- | --- | --- | --- |
| **SessionStatusDot** | `ui/components/session-status.tsx` | `Box(Modifier.size(8.dp).background(color, CircleShape))` inside a 24dp box; `semantics { contentDescription = "Session status: <s>" }` when standalone | Colours and pulse from sections 1.6 and 6. Neutral states render nothing (desktop `visibility: hidden`) but keep the 24dp box so titles align |
| **ConversationHeader** | `webview/components/agent-header.tsx`, `ui/.../agent-conversation-layout.tsx` | `TopAppBar` (64dp). navigationIcon: `menu` (compact/medium, opens the drawer) or `left_panel_open`/`left_panel_close` (expanded, toggles the list pane). title: `Row(status dot, Text(title, titleMedium, 1 line))`, clickable → "Session details" sheet. actions: agents chip, diff chip, `add` "New session" or the subagent back chip, `more_vert` "Session actions" | Title fallback "New Session". Diff chip: `AssistChip` with label `+N` (`success.color`) `-N` (`error`), CD "Open diff: N additions, N deletions", shown only when additions ≠ 0 (desktop). Agents chip: `smart_toy` + total + running spinner / `check` completed / `error` failed / `schedule` other, CD = `describeAgentActivity` ("3 agents: 1 running, 2 completed"). Subagent: "Main Agent Session", CD "Back to the main agent session". Overflow menu: "Rename", "Pin"/"Unpin", "Fork", "Details", "Export" *(Android, CLI-only feature)*, "Delete session" / "Deleting..." |
| **ScrollToLatestButton** | `ConversationScrollButton` | `SmallFloatingActionButton(containerColor = surfaceContainerHigh, contentColor = onSurface)` with `arrow_downward`, end-aligned 16dp above the composer | Shown when more than 120dp from the bottom, auto-stick within 24dp (desktop `SCROLL_BUTTON_THRESHOLD_PX`, `STICK_TO_BOTTOM_THRESHOLD_PX`). CD "Scroll to latest message" |
| **PromptQueueStrip** | `ui/components/agent-prompt-queue.tsx` | `Surface(shape = large top corners, color = surfaceContainer)` docked on top of the composer: `schedule` icon + "N prompts queued" + `expand_more`. Tap opens the Prompt queue sheet. Rows have `IconButton`s for steer `subdirectory_arrow_left`, edit `edit`, remove `delete`; edit mode adds `check`/`close` | Copy: "1 prompt queued" / "N prompts queued" (plurals). CDs "Steer queued prompt", "Edit queued prompt", "Remove queued prompt", "Save queued prompt", "Cancel editing queued prompt". Errors "Could not update the queued prompt.", "Could not steer the queued prompt.", "Could not remove the queued prompt." Binding: `session.pending_prompts`, `session.update_pending_prompt`, `session.remove_pending_prompt`, `session.steer_first_pending_prompt`, events `session.pending_prompts`, `session.pending_prompt_submitted` |
| **CommandOutput** | `ui/components/agent-command-output.tsx` | "Output" overline (`labelSmall` uppercase, `onSurfaceVariant`) + `Surface(color = terminalBackground, shape = small)` with `SelectionContainer` `Text` in `ClineMono.body` (`terminalForeground`), max 256dp then scrolls, auto-scroll pinned within 24dp of the bottom, plus a blinking 6×12dp cursor block while running | ANSI SGR → `AnnotatedString` with the section 1.5 palette (open question 8 decides chunk shape). CD "Command output". Long output is capped at 64 KiB with a "[output truncated]" tail (desktop `capText`) |
| **ToolFileDiff / DiffHunk** | `ui/.../agent-chat/tool-diff.tsx`, `webview/.../chat/diff-view.tsx` | `Column` of lines in `ClineMono.body`: gutter (old/new numbers, `ClineMono.tiny`, hidden for fragments), marker column (`+`/`-` in the marker colours), line background `diffAddedLine`/`diffRemovedLine`. Horizontal scroll for long lines; no wrap | One diff per hunk (desktop: "re-diffing concatenated hunks pairs deletions with unrelated additions"). Syntax highlight is out of scope for v1 (plain mono), which is listed in the open questions |
| **ChangedFileHeader / ChangesPanel** | `ui/components/agent-changes.tsx` | Header row 48dp: `expand_more`/`chevron_right`, path `ClineMono.small` ellipsized in the middle, copy `IconButton` (always visible on touch), spacer, `+N` `-N`. The panel header is a `TopAppBar` titled "Uncommitted changes" with a "Files: N" badge | Copy: "Uncommitted changes", "Files: N", "No file changes in this session yet.", CD "Copy file path for <path>", tooltip "Copy file path", "Close diff view" |
| **WelcomeHero** (+ Home's sr-only heading) | `ui/components/agent-welcome-hero.tsx`; `webview/.../chat/welcome-chat.tsx` (`<h1 className="sr-only">`) | The bot illustration only, no visible heading (the desktop stopped mounting `AgentHeroHeading` in upstream 35583ee8b): `ic_cline_logo` at 72dp (compact) / 96dp (expanded) drawn in `primary` at 13 % fill and 12 % stroke opacity over a faint 40dp grid (`outlineVariant` @ 20 %), decorative. Home wraps it in a node with `heading()` and `contentDescription` "What would you like to build?" (the sr-only `<h1>`); 24dp (`Spacing.xxl`) to the workspace chips (desktop `mt-11` below a frame the bot fills) | The desktop eyes follow the mouse pointer; touch has no hovering pointer, so they stay centred (the desktop's reduced-motion pose). The hero is hidden on height-compact windows, where the heading node remains as a 1dp full-width box |
| **WelcomeSetupNotice** | `webview/.../chat/welcome-setup-notice.tsx` | `Surface(shape = large, color = primaryContainer @ 35 %, border = 1dp primary @ 30 %)` with a 32dp icon tile (`cable`, `primary` on `primary @ 15 %`), title `titleSmall` SemiBold, body `bodyMedium`, `Button` "Connect a model" + `TextButton` "Model settings". Announced as a polite live region | "Connect a model to start building", "Sign in with Cline or add an API key — it takes under a minute." Binding: `cat providers.json` (no configured provider) |
| **SessionNotice row** | `session.notice` rendering in `chat-messages.tsx` | `Row`: 20dp icon (`info` for status, `autorenew` for recovery / auto_compaction, `pause_circle` for stop) + `bodyMedium` `onSurfaceVariant` message | Binding `session.notice {message, noticeType, reason}`. `api_error` becomes an error message (#5) with a credential action |
| **ImageLightbox / Carousel** | `webview/.../messages/image-lightbox.tsx`, `image-carousel.tsx`, `ui/components/agent-image-lightbox.tsx` | Full-screen `Dialog(usePlatformDefaultWidth = false)` on a `scrim` @ 95 %, a `HorizontalPager` of zoomable images (`transformable`), a top close `IconButton`. Carousel in the transcript: `LazyRow` of 160dp thumbnails | CD "Expanded attachment", "Close". Predictive back scales the image to 0.9 and fades the scrim with progress |
| **KernelChip** | sidebar logo HoverCard trigger | `AssistChip` (drawer header) / rail item: `ic_cline_logo` 18dp + 8dp dot + "Hub" *(Android)* | Opens the Kernel status sheet. CD "Cline Termux home" on the logo (desktop: "Cline home") and "Kernel status: <state line 1>" on the chip |
| **EmptyState** | `ConversationEmptyState`, `PageEmptyState` | Centered `Column`: 48dp icon `onSurfaceVariant`, `titleMedium`, `bodyMedium` `onSurfaceVariant`, optional `FilledTonalButton` | Defaults "No messages yet" / "Start a conversation to see messages here." |
| **Loading skeleton** | `ui/skeleton.tsx` | `Box` blocks `surfaceContainerHighest` with alpha shimmer 0.6 → 1 over 1000ms (disabled under reduced motion) | Used for list first loads only. Detail panes use a `LinearProgressIndicator` |

### 4.3 Button and chip policy

| Desktop variant | M3 |
| --- | --- |
| default/primary (`bg-primary`) | `Button` (filled) |
| outline | `OutlinedButton` |
| secondary (`bg-secondary`) | `FilledTonalButton` |
| ghost / text / sidebarText | `TextButton` |
| destructive | `Button(colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = onError))` in dialogs only. Elsewhere `TextButton` with `error` content |
| icon-sm / icon | `IconButton` (48dp target, 24dp icon). Use `FilledIconButton` only for send/stop |
| Badge | `Badge` (count) or a `Surface(shape = extraSmall, color = surfaceContainerHighest)` with `labelSmall` text for tags |
| Select | `ExposedDropdownMenuBox` in forms; an `AssistChip` + `DropdownMenu` in the composer |
| Switch | `Switch` |
| Tabs (Customize: Tools / Plugins / Skills / Rules / MCP / Hooks) | `PrimaryScrollableTabRow` |
| Segmented filter (sidebar "All" / "Running", Plan / Act) | `SingleChoiceSegmentedButtonRow` |

---

## 5. Layout

### 5.1 Window classes

Use `currentWindowAdaptiveInfo().windowSizeClass` (material3-adaptive 1.3).

| Class | Width | Devices | Shell |
| --- | --- | --- | --- |
| Compact | < 600dp | S25U portrait (about 412dp) | **ModalNavigationDrawer + single pane** |
| Medium | 600–839dp | Tab S7+ portrait (about 778dp), S25U split screen | **NavigationRail + single pane**; session list in a modal drawer |
| Expanded | ≥ 840dp | Tab S7+ landscape (about 1244dp), S25U landscape (about 891dp), DeX | **NavigationRail + list pane + detail pane** (+ supporting pane at ≥ 1200dp) |
| Height-compact modifier | height < 480dp | phones in landscape | Expanded width but **no list pane** (rail + single pane), hero hidden, and the composer settings row scrolls horizontally |

### 5.2 Navigation structure

Top-level destinations come from the desktop sidebar: the session list plus the
"Session", "Schedule" and "Customize" actions and the "Settings"/Account footer.

| Destination | Icon (outlined / filled when selected) | Route |
| --- | --- | --- |
| Chats *(Android label for the session/chat area)* | `chat_bubble` | `chat/{sessionId?}` (null = Home/welcome) |
| Schedules | `schedule` | `schedules` |
| Customize | `extension` | `customize` |
| Settings | `settings` | `settings/{section?}` |

The search, sessions (full) and changes screens are pushed routes, not destinations.

**Compact (phone).** `ModalNavigationDrawer` with `ModalDrawerSheet` (max width 360dp,
`surfaceContainerLow`). Its content, top to bottom, mirrors `agent-sidebar.tsx`:

1. A header row with `ic_cline_logo` `IconButton` ("Cline Termux home": opens Home), "Cline Termux"
   in `titleMedium`, the KernelChip, and a search `IconButton` ("Search sessions").
2. `NavigationDrawerItem`s: "New session" (`add`, tooltip "Start a new session"),
   "Schedules" (`schedule`), "Customize" (`extension`).
3. The list header: "Sessions" or "Projects" (tap → Sessions screen), a sort toggle
   (CD "Sort sessions: Time" / "Sort sessions: Project") and a filter (CD "Filter
   sessions", a menu with "Status" "All"/"Running").
4. Collapsible sections "Pinned", "Scheduled" and "Tasks" with counts, or project groups.
5. A footer: an account row (`account_circle`, name or organisation, else "Personal")
   and "Settings" (`settings`).

The drawer opens from the app bar `menu` button and by an edge swipe (`gesturesEnabled`
only when the chat is at its root and not in rename mode). Choosing a session closes it.
There is no navigation bar: the desktop has no tab bar, and the session list is the
primary navigator.

**Medium.** `NavigationRail` (80dp, `surface`). Its header holds `menu` ("Open
sessions", which opens the same `ModalNavigationDrawer`) and a `FloatingActionButton`
"New session" (`edit_square`). The items are the four destinations, with the KernelChip
as the bottom item. The content is single pane.

**Expanded.** The rail as above, then a **list pane** for Chats: the drawer content
without the destination items, width 360dp, or 400dp at ≥ 1200dp. Then the **detail
pane** (chat). Build it with `NavigableListDetailPaneScaffold` (adaptive-navigation 1.3),
which supports predictive back between panes. The rail's `menu` button collapses and
expands the list pane, matching the desktop "Expand sidebar"/collapse. At ≥ 1200dp,
"Changes" opens as a **supporting pane** of 400dp on the right
(`SupportingPaneScaffold`), matching the desktop's side panel. Below 1200dp it is a
pushed full-screen route. Settings on expanded uses `NavigableListDetailPaneScaffold`
(sections list 360dp + section detail). On compact and medium it uses list → push.

### 5.3 App bars

- Chat: `TopAppBar` (small, 64dp) as in the ConversationHeader. Its container stays
  `surface` and gets a hairline on scroll. It does not collapse, because the transcript
  scrolls under a stable header (desktop `h-12` header).
- Home/welcome: `TopAppBar` with only the `menu` (compact) and an empty title. The hero
  fills the body.
- Settings, Customize, Schedules, Sessions, Changes, Providers and other pushed pages:
  voice `PageScaffold` (`MediumTopAppBar`, `exitUntilCollapsedScrollBehavior`, back
  arrow with CD "Back").
- Search: `SearchBar` + `ExpandedFullScreenSearchBar` (M3 1.4) on compact. On
  expanded it is a docked `SearchBar` at the top of the list pane. Placeholder "Search
  all session history…" (desktop SessionCommandBar). Empty state "No matching session
  history." Group heading "Session history".

### 5.4 FAB policy

- **Chat: no FAB.** The composer is the primary action. The only floating button is
  ScrollToLatest.
- Home: no FAB (the composer is there).
- Sessions (full screen): `ExtendedFloatingActionButton` "New session" (`add`),
  collapsing to icon-only on scroll (`expanded = listState.isScrollingUp()`).
- Schedules: ExtendedFAB "New Schedule" (desktop verbatim). MCP servers: "Add MCP Server". Providers: "Add
  provider". On a height-compact window these stay icon-only beside an 88dp end lane in
  the list (customize-and-rest §0.6, `FabLane`).
- Rail (medium/expanded): the FAB in the rail header is "New session". With the rail
  present, the Sessions screen does not show its own FAB.

### 5.5 Bottom sheets

Use `ModalBottomSheet` for approval, ask question, model picker, prompt queue, context
usage, session details, kernel status, workspace picker and attachment source. The
sheet max width is 640dp, centred on tablets (M3 default `BottomSheetDefaults.SheetMaxWidth`).
Every sheet has a `DragHandle`, a title row (`titleMedium`, 24dp gutters), a content
`LazyColumn`, and a bottom inset of `WindowInsets.navigationBars` + 16dp. Sheets that
hold a text field (ask question, prompt-queue edit) add `Modifier.imePadding()` to the
footer. Approval and question sheets reopen automatically when a new request arrives
and the app is in the foreground on that session. On another session they raise the
session's status dot and a snackbar "Tool approval required" with action "Review".

### 5.6 Edge-to-edge insets

- `enableEdgeToEdge()`. Every `Scaffold` uses
  `contentWindowInsets = ScaffoldDefaults.contentWindowInsets` and passes `innerPadding`
  to lists as `contentPadding`, so lists draw under the system bars.
- The bottom safe area is `navigationBars ∪ mandatorySystemGestures(bottom) ∪ 16dp`
  (voice `bottomSafe`: "in gesture mode this phone reports a zero navigation-bar inset
  while its handle still sits over the last line").
- Display cutout: `WindowInsets.displayCutout` is included via `safeDrawing` on the
  rail and the list pane in landscape.
- Drawer and rail: `ModalDrawerSheet` and `NavigationRail` handle `systemBars` insets
  themselves (M3 defaults).

### 5.7 IME handling for the composer

1. The manifest keeps `android:windowSoftInputMode="adjustResize"` (already set). With
   edge-to-edge, Compose receives `WindowInsets.ime`.
2. The chat `Scaffold` sets `contentWindowInsets = WindowInsets.systemBars.only(Top + Horizontal)`.
   The composer container applies
   `Modifier.windowInsetsPadding(WindowInsets.ime.union(bottomSafe))`, so it sits on the
   IME when the IME is open and on the nav bar when it is closed. No double padding.
3. The transcript is a `LazyColumn(reverseLayout = true)` (newest at index 0). When the
   IME opens, the visible bottom stays anchored and no scroll correction is needed. The
   stick-to-bottom logic follows section 6.
4. `Modifier.imeNestedScroll()` is **not** used on the transcript. Swiping the
   transcript must not close the keyboard mid-read. The keyboard closes on send (only
   when the session is busy: on an idle send it stays open for follow-ups, as in the
   desktop flow) and on back.
5. Hardware keyboard (Tab S7+ Book Cover): Enter sends, Shift+Enter inserts a newline,
   Esc stops the agent when it can abort (desktop key map). Ctrl+K / Ctrl+P opens search
   (desktop "Search sessions (Cmd/Ctrl+P)"). Up/Down in the slash and mention menus move
   the selection, Enter/Tab picks.

### 5.8 Predictive back

`android:enableOnBackInvokedCallback="true"` is already set. The back order is fixed.
Each item consumes back only while it is showing:

1. Open `DropdownMenu` / tooltip: closes (M3).
2. Open `ModalBottomSheet`: predictive collapse (M3 1.4 built-in). Dismissing the
   approval sheet collapses it to the banner and does not reject.
3. Image lightbox: `PredictiveBackHandler` scales the content from 1 to 0.9 and fades
   the scrim from 95 % to 0 with progress, then closes on commit.
4. Inline rename / prompt-queue edit: `BackHandler` cancels the edit (desktop Esc).
5. Slash or mention menu open in the composer: closes it.
6. Search expanded: collapses (M3 `SearchBar`).
7. `ModalNavigationDrawer` open: predictive close (M3 built-in).
8. Detail → list on expanded `NavigableListDetailPaneScaffold`: pane back (built-in).
9. NavHost pop: shared-axis-X seekable transition (section 6) driven by back progress
   (navigation-compose 2.10 `popEnterTransition`/`popExitTransition` are seekable).
10. At Home with nothing open: do not intercept. Back leaves the app (system
    back-to-home animation). The foreground service, if a turn runs, keeps the kernel
    connection (kernel contract).

**Never** register an always-on `BackHandler`. That breaks the system back-to-home
preview.

---

## 6. Motion (`ClineMotion`)

M3 tokens (`Motion.kt`). This extends the voice `ui/Navigation.kt` `Motion` object:

```kotlin
object ClineMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Pulse = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)          // Tailwind animate-pulse, used by the desktop
    const val Short2 = 100; const val Short3 = 150; const val Short4 = 200
    const val Medium2 = 300; const val Long2 = 500
}
```

| Motion | Spec | Desktop source |
| --- | --- | --- |
| Disclosure expand (tool row, reasoning, work block, changed file) | `expandVertically(tween(Short4, easing = Standard), expandFrom = Top) + fadeIn(tween(Short3, delayMillis = 50))`; collapse `shrinkVertically(tween(Short4, easing = Standard)) + fadeOut(tween(Short2))` | 180ms symmetric `cubic-bezier(.45,0,.55,1)` |
| Chevron rotation | `animateFloatAsState(tween(Short4, easing = Standard))` | 180ms |
| Running label shimmer | `rememberInfiniteTransition` + `Brush.linearGradient(listOf(c.copy(alpha=.55f), c, c.copy(alpha=.55f)))` with the offset moving 200 % → −200 % over **2250ms linear**, applied to the label text (`TextStyle(brush=…)`) | `cline-chat-title-shimmer 2.25s linear infinite` |
| Tool spinner | `CircularProgressIndicator` indeterminate (M3 default speed) | 800ms spin |
| Status dot pulse (Starting, Connecting, Reconnecting only) | alpha 1 → 0.5 → 1, period **2000ms**, easing `Pulse`, `RepeatMode.Restart` via `keyframes` | Tailwind `animate-pulse` on `provisioning`. Running is steady |
| Streaming text | No per-character animation. Coalesce `assistant.delta`/`reasoning.delta` into the message state at most every 50ms (`conflate` + `sample(50)`). Markdown re-parses only the last block | Streamdown, no caret |
| Auto-scroll while streaming | while pinned (within 24dp of the bottom), `scrollToItem(0)` instantly on growth. A user drag up releases the pin, and reaching the bottom restores it. Tapping ScrollToLatest calls `animateScrollToItem(0)` | `Conversation` stick-to-bottom logic |
| ScrollToLatest button | `scaleIn(initialScale = .8f) + fadeIn`, `Short3`; exit `Short2` | appears and disappears instantly |
| Send ⇄ Stop morph | `AnimatedContent(fadeIn(tween(150)) + scaleIn(tween(150), .8f) togetherWith fadeOut(tween(90)) + scaleOut(tween(90), .8f))` | voice `Composer.kt` |
| Welcome ⇄ conversation swap | `fadeIn(tween(180, easing = StandardDecelerate)) + slideInVertically(tween(180)) { 4.dp.px }` | `cline-view-enter 180ms ease-out`, translateY 4px |
| Welcome hero | Static: no verb cycle (the desktop no longer shows `AgentHeroHeading`) and no eye tracking (the desktop eyes follow a hovering mouse pointer, which touch does not have) | `agent-welcome-hero.css`, `agent-welcome-hero-pointer.ts` |
| Bottom sheets, drawer, dialogs, menus, snackbar | M3 1.4 component defaults. No overrides | |
| Push / pop between routes | Shared axis X: slide 30dp + incoming fade over the last 210ms, outgoing fade in the first 90ms, total 300ms with `Standard`. Mirrored on pop. Seekable for predictive back | voice `Motion.sharedAxisX` |
| Top-level destination switch | Fade through: out 90ms, in 210ms (delay 90ms) with `Standard`, plus a scale 0.92 → 1 on in | voice `fadeThrough` |
| Context ring / bar changes | `animateFloatAsState(tween(120))` | `transition-[width] duration-120` |
| Skeleton shimmer | alpha 0.6 ⇄ 1.0, 1000ms, `Standard` | |

**Reduced motion.** Read `Settings.Global.ANIMATOR_DURATION_SCALE` (via `ContentResolver`,
re-read on resume). At `0f`, disable the shimmer (static label), the pulse (static
dot), the skeleton shimmer and the cursor blink.
Disclosure and navigation transitions then complete instantly, which Compose does by
itself. Expose this as `LocalReducedMotion`.

---

## 7. Iconography

### 7.1 Source and delivery

The icons are **Material Symbols Rounded**: weight 400, grade 0, optical size 24, fill 0
(outlined) by default and fill 1 for selected navigation items and status icons that
say "filled" below. The Rounded style is the closest match to the desktop's Lucide set
(round caps, 2px strokes). Export each glyph from fonts.google.com/icons as an
**Android vector drawable** and save it as `res/drawable/ms_<name>.xml`
(`ms_<name>_fill.xml` for fill 1). Use them through `ImageVector.vectorResource(R.drawable.ms_x)`,
collected in `object ClineIcons`. Icons are 24dp in buttons and list items, 20dp in tool
rows and chips, and 16dp in meta lines. Directional icons (`arrow_back`,
`chevron_right`, `subdirectory_arrow_left`, `left_panel_*`) set
`android:autoMirrored="true"`.

### 7.2 TOOL_KIND_ICONS (`webview/.../messages/tool-icons.ts`)

`getToolNameIcon(name) = TOOL_NAME_ICONS[normalized] ?: TOOL_KIND_ICONS[classifyTool(normalized)]`.
Port `normalizeToolName` (aliases `bash`→`run_commands`, `edit`/`edit_file`→`editor`,
`read_file`→`read_files`, `search`→`search_codebase`, `web_fetch`→`fetch_web_content`, …)
and `classifyTool`, including its fuzzy fallbacks, verbatim.

| ToolKind | Lucide | Material Symbol |
| --- | --- | --- |
| read | `FilesIcon` | `file_copy` |
| edit | `PencilIcon` | `edit` |
| command | `TerminalIcon` | `terminal` |
| search | `SearchCodeIcon` | `manage_search` |
| web | `PanelsTopLeftIcon` | `web` |
| spawn | `UserIcon` | `person` |
| team | `UsersIcon` | `group` |
| skill | `LibraryIcon` | `library_books` |
| mcp | `BoxIcon` | `deployed_code` |
| question | `MessageCircleQuestionMarkIcon` | `contact_support` |
| other | `WrenchIcon` | `build` |
| *name override* `plugins` | `BlocksIcon` | `extension` |
| *name override* `submit_and_exit` | `SquareArrowRightIcon` | `exit_to_app` |
| *any kind, error* | `AlertCircle` | `error` (fill 1, `error` colour) |
| thinking (ReasoningBlock) | Brain | `neurology` |

### 7.3 Session and kernel states

| State | Symbol | Notes |
| --- | --- | --- |
| Running | none in rows (dot). Header and roster: `CircularProgressIndicator` 16dp | desktop `Loader2` spin |
| Approval required | `gpp_maybe` (fill 1) | desktop `ShieldAlert` |
| Question asked | `contact_support` | |
| Pending | `pending` | |
| Completed | `check_circle` (fill 1) | roster uses `check` |
| Cancelled | `cancel` | roster "cancelled/unresolved" uses `schedule` (desktop `Clock3`) |
| Failed | `error` (fill 1) | |
| Scheduled session | `schedule` | desktop `Clock3` |
| Pinned | `push_pin` (fill 1) | |
| Kernel | `ic_cline_logo` (section 10) | desktop `ClineLogo` |
| Termux | `terminal` | |

### 7.4 Chrome and actions

| Use | Symbol |
| --- | --- |
| Send (`ArrowUp`) | `arrow_upward` |
| Stop (`CircleStop`) | `stop` (inside the filled button) |
| Attach (`Paperclip`) | `attach_file` |
| Model (`Cpu`) | `memory` |
| Reasoning chip (`Brain`) | `neurology` |
| Plan / Act | text only (segmented) |
| Copy / copied | `content_copy` / `check` |
| Edit message | `edit` |
| Restore checkpoint (`Undo`) | `undo` |
| Fork (`Split`, `GitFork`) | `fork_right` |
| New session (`Plus`) | `add` (app bar), `edit_square` (rail FAB) |
| Session actions (`MoreHorizontal`) | `more_vert` (M3 app-bar convention) |
| Delete (`Trash2`) | `delete` |
| Search | `search` |
| Filter / sort | `filter_list` / `sort` |
| Agents (`Bot`) | `smart_toy` |
| Diff stats | text chip |
| Customize (`Blocks`) | `extension` |
| Settings | `settings` |
| Account (`CircleUserRound`) | `account_circle` |
| Providers (`Plug`) | `power` |
| MCP | `deployed_code` |
| Marketplace | `storefront` |
| Schedules | `schedule` |
| Notifications | `notifications` |
| Appearance | `palette` |
| About (`Info`) | `info` |
| Workspace folder | `folder` / `folder_open` |
| Setup notice (`Cable`) | `cable` |
| Expand / collapse | `expand_more` (rotated) |
| Scroll to latest | `arrow_downward` |
| Steer queued prompt (`CornerDownLeft`) | `subdirectory_arrow_left` |
| Close | `close` |
| Back | `arrow_back` |
| List pane toggle (`PanelLeftOpen`) | `left_panel_open` / `left_panel_close` |
| Branch | `call_split` |
| Export / share | `share` |

---

## 8. Accessibility and i18n

### 8.1 Accessibility (MUST)

1. **Labels.** Every icon-only control has a `contentDescription` equal to the desktop
   `aria-label` (quoted in section 4). Decorative icons pass `null`.
2. **Touch targets.** 48dp minimum (section 3.4).
3. **Colour is never the only signal.** Status dots always have a TalkBack label
   ("Session status: running"). The Sessions screen also shows the label text in
   compact rows' supporting line when the status is Failed, Approval required or Question
   asked. Error rows show the `error` icon as well as the colour.
4. **Live regions.** The transcript is not a live region per delta. One invisible
   status `Text` per conversation, `liveRegion = LiveRegionMode.Polite`, announces
   "Agent is working…" *(Android)* on `run.started`, then the first 200 characters of
   the assistant text on `run.completed`, "Run failed: <error>" on `run.failed`, and
   "Tool approval required" / "Follow-up question" when those sheets open. The setup
   notice is polite. Ask-question errors are assertive.
5. **Disclosure semantics.** Tool, reasoning and work rows set `role = Role.Button`,
   `stateDescription = "Expanded"/"Collapsed"`, and `contentDescription = full label +
   status` ("Ran command bun test, error").
6. **Custom actions.** Every row or message with a long-press menu exposes the same
   items through `semantics { customActions = … }`.
7. **Focus.** Keyboard focus shows a 2dp `primary` outline offset 2dp
   (`Modifier.focusable` + `indication`). Focus order follows reading order. Opening a
   sheet moves focus to its title. Closing returns it to the trigger.
8. **Text scaling.** Layouts must work at system font scale 2.0 (Android 14 non-linear)
   **times** the in-app font size 20/15. No fixed heights on text containers. Rows use
   `heightIn(min = …)`. App-bar titles ellipsize. The composer grows up to 5 lines.
9. **Contrast.** The pairs in section 1.8 are the only allowed text/background pairs
   for the brand scheme. Under dynamic colour, only M3 role pairs (on-X over X,
   onSurface / onSurfaceVariant over any surface container) are allowed.
10. **Motion.** Reduced-motion rules from section 6.
11. **Selection.** Message bodies, code blocks, command output, diff lines and approval
    details are inside `SelectionContainer` (desktop `user-select: text` list). Chrome
    is not selectable.
12. **TalkBack reading of mono content.** Commands and paths are read as written:
    `semantics { contentDescription = raw }`. No ligature glyphs.

### 8.2 Internationalisation (MUST)

1. All user-visible strings live in `res/values/strings.xml`. The source language is
   English and the text is the desktop copy verbatim. Strings marked *(Android)* are
   new. A `values-zh-rCN/strings.xml` translation is planned. Nothing is hard-coded in
   composables, and the lint check `HardcodedText` is set to error.
2. Counts use `plurals`: "%d prompt queued" / "%d prompts queued", "Read %d file(s)",
   "Made %d tool call(s)", "%d agent(s)". Port the desktop `pluralize` rules
   (`tool-summary/team.ts`), for example "searches" for search.
3. Do not concatenate translatable fragments. Use positional formats: "Thought for
   %1$ds", "Worked for %1$s and made %2$s", "Request %1$s · Iteration %2$d",
   "Cline Hub @%1$s".
4. Durations ("4m 12s"), compact tokens ("12.5k", sessions screen lowercase "1.2m") and
   costs ("$0.0123") use the desktop formatting functions, ported unchanged. They are
   not localised, because they are technical units shared with the CLI.
5. Dates and times: the localized short time for message timestamps (desktop
   `toLocaleTimeString` hour:minute), on the user's "Use 24-hour format" setting: every time
   of day goes through `data/schedules/ClockFormat`, never bare `ofLocalizedTime`, which
   ignores that setting. Session rows use relative time
   ("2m", "3h", "Yesterday") via `DateUtils.getRelativeTimeSpanString(…, FORMAT_ABBREV_RELATIVE)`.
   Numbers in usage details use `NumberFormat.getIntegerInstance()` (desktop
   `toLocaleString`).
6. RTL: `supportsRtl="true"` (already set). The layout mirrors, and directional icons
   are auto-mirrored. Code, diffs, command output and paths are forced LTR
   (`CompositionLocalProvider(LocalLayoutDirection provides Ltr)` +
   `TextDirection.Ltr`).
7. CJK typography rules from section 2.3. Model output and user text are never
   truncated mid-grapheme (use `BreakIterator` for the 16-character chip truncation).
8. Kernel-supplied text (tool names, errors, `session.notice.message`, model names) is
   shown untranslated, as data.

---

## 9. Desktop parity principles

**Copied verbatim:**

- **Copy.** Every label, placeholder, empty state, error, confirmation, tooltip and
  accessibility label that the desktop has is reused word for word, including its
  punctuation (for example "Agent is working... submit to queue another message",
  "Or type your own answer…", "Thought for 12s").
- **Information hierarchy.** The order and grouping of the sidebar (header → actions →
  Pinned / Scheduled / Tasks or Projects → account/settings), the transcript (user turn
  → thinking → tool rows → answer, with finished runs folding into "Worked for…"), the
  composer controls, the approval card fields, and the session overview items.
- **States and their precedence.** The status mapping (section 1.6), the
  hidden-when-unknown context ring, enabled/disabled rules (model chip
  disabled while busy, reasoning disabled without support), pending action labels
  ("Deleting...") and the scroll thresholds (24dp / 120dp).
- **Behaviour.** A single-choice question answers on tap. A busy composer queues with
  `delivery: queue`. Rejecting sends the desktop rejection reason. Fork copies the full
  history. Deleting always confirms.
- **Formatting functions.** `buildToolSummary`, `formatThoughtLabel`,
  `formatWorkActivityLabel`, `formatWorkDuration`, `formatCompactTokens`,
  `formatCostUsd` and `describeAgentActivity` are ported to Kotlin with unit tests copied
  from the desktop `*.test.ts` files where they exist.

**Translated, not imitated:**

| Desktop idiom | Android |
| --- | --- |
| Permanent, collapsible sidebar | Modal drawer (compact), rail + modal drawer (medium), rail + list pane (expanded) |
| Hover cards | Bottom sheets opened by tap / long-press |
| Hover-revealed message actions | Inline action row on the last assistant message + long-press menu + TalkBack actions. The desktop itself shows actions always on coarse pointers |
| Right-click context menu | Long-press `DropdownMenu` + visible overflow button |
| Popovers and comboboxes (model, reasoning, usage) | Bottom sheets and anchored `DropdownMenu`s |
| Tables with pagination (Sessions) | `ListItem` list with infinite scroll on compact. Column rows on expanded |
| Modal dialogs for editors | Full-screen dialogs on compact, `BasicAlertDialog` on medium and above |
| Toasts | Snackbars, one at a time |
| `title` tooltips | `PlainTooltip` on long-press |
| Keyboard-first input (Enter / Esc / Cmd+P) | Touch first, with the same keys kept for hardware keyboards |
| 28px disclosure rows whose bodies open by themselves (`shouldAutoOpen`: diffs, submit text, media, running output) | 40dp rows (A24) that start folded and open only on a tap (A23) |
| Window title bar, tray, updater, accent picker, app-icon picker | Edge-to-edge system bars. No tray or updater. Accent comes from dynamic colour with the brand fallback. App icon becomes the launcher alias choice (section 10.6) |
| Desktop-only features (cloud sessions, remote SSH, PR bar, open in editor, folder dialog, import) | Omitted per the feature matrix ("none"). No disabled placeholders anywhere |

---

## 10. Launcher icon and notification icon

**Since 2026-10-16** the app has its own name, Cline Termux, and its own mark, icon B1x: a C
followed by a terminal cursor, drawn for this app because the Cline logo belongs to Cline Bot
Inc. The mark is the launcher icon (Midnight and Classic), the splash icon, the notification
small icon and the in-app logo; the app no longer ships the Cline logo. The launcher mark is
57.5dp wide, the C is 41.13dp tall, and its farthest point is 32.85dp from the centre, inside
the 33dp safe zone. The resource files are the source of truth: the listings in 10.2, 10.3,
10.5 and 10.6 show the earlier Cline-mark version. Where this document names `ic_cline_logo`,
the app now uses `ic_app_logo` (`ClineIcons.AppLogo`), and the welcome hero strokes it with
`ic_app_logo_outline`; the sign-in and account spots use `account_circle`. The welcome screen
and About say that the app is unofficial (KS A.4, CR C10).

The desktop's default app icon is **"Midnight"** (`lib/app-icon.ts`:
`DEFAULT_APP_ICON = "midnight"`): a white filled bot on near-black. The Android launcher
icon reproduces it from the mark in `public/icon.svg`. The silhouette is the outer edge
of that file's 7.4-unit outline: body corners r 14.9, ear tips, a knob of r 12 + 3.7,
and the neck, which was derived exactly from the path's cubic segments. The two
eyes are cut out of it (`fillType="evenOdd"`), so the background shows through, as in
the Midnight icon.

Geometry check (computed): the bot is 52.0dp wide and 47.4dp tall, centred at
(54, 54) in the 108dp canvas. Its farthest point from the centre is **28.2dp**, inside
the **33dp** safe-zone radius (66dp diameter) with 4.8dp to spare, so circle, squircle
and teardrop masks never clip it.

### 10.1 `res/values/colors.xml`

Replace the scaffold's placeholder `#1E1E2E`:

```xml
<resources>
    <!-- Launcher tile: public/icon.svg fill colour (Midnight icon ground). -->
    <color name="ic_launcher_background">#1C1C24</color>
    <!-- Classic alternate (section 10.6): desktop accent-9. -->
    <color name="ic_launcher_background_classic">#6E56CF</color>
</resources>
```

### 10.2 `res/drawable/ic_launcher_foreground.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Cline mark (apps/examples/desktop-app/webview/public/icon.svg) filled, eyes cut out.
     52dp wide in the 108dp canvas; max radius 28.2dp < 33dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFCFCFD"
        android:fillType="evenOdd"
        android:pathData="M40.96,37.74 L46.69,37.74 L46.69,37.47 C46.69,33.43 50.01,30.29 54,30.29 C57.99,30.29 61.31,33.43 61.31,37.47 L61.31,37.74 L67.04,37.74 A6.94,6.94 0 0,1 73.99,44.68 L73.99,50.36 L79.29,56.33 C80,57.13 80,58.33 79.29,59.12 L73.99,65.09 L73.99,70.77 A6.94,6.94 0 0,1 67.04,77.71 L40.96,77.71 A6.94,6.94 0 0,1 34.01,70.77 L34.01,65.09 L28.71,59.12 C28,58.33 28,57.13 28.71,56.33 L34.01,50.36 L34.01,44.68 A6.94,6.94 0 0,1 40.96,37.74 Z M47.29,51.02 A2.24,2.24 0 0,1 49.53,53.26 L49.53,62.2 A2.24,2.24 0 0,1 47.29,64.44 A2.24,2.24 0 0,1 45.06,62.2 L45.06,53.26 A2.24,2.24 0 0,1 47.29,51.02 Z M60.71,51.02 A2.24,2.24 0 0,1 62.94,53.26 L62.94,62.2 A2.24,2.24 0 0,1 60.71,64.44 A2.24,2.24 0 0,1 58.47,62.2 L58.47,53.26 A2.24,2.24 0 0,1 60.71,51.02 Z" />
</vector>
```

### 10.3 `res/drawable/ic_launcher_monochrome.xml`

The monochrome layer for themed icons (Android 13+) uses the same geometry. The system
tints it, so the colour is irrelevant but must be opaque:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="M40.96,37.74 L46.69,37.74 L46.69,37.47 C46.69,33.43 50.01,30.29 54,30.29 C57.99,30.29 61.31,33.43 61.31,37.47 L61.31,37.74 L67.04,37.74 A6.94,6.94 0 0,1 73.99,44.68 L73.99,50.36 L79.29,56.33 C80,57.13 80,58.33 79.29,59.12 L73.99,65.09 L73.99,70.77 A6.94,6.94 0 0,1 67.04,77.71 L40.96,77.71 A6.94,6.94 0 0,1 34.01,70.77 L34.01,65.09 L28.71,59.12 C28,58.33 28,57.13 28.71,56.33 L34.01,50.36 L34.01,44.68 A6.94,6.94 0 0,1 40.96,37.74 Z M47.29,51.02 A2.24,2.24 0 0,1 49.53,53.26 L49.53,62.2 A2.24,2.24 0 0,1 47.29,64.44 A2.24,2.24 0 0,1 45.06,62.2 L45.06,53.26 A2.24,2.24 0 0,1 47.29,51.02 Z M60.71,51.02 A2.24,2.24 0 0,1 62.94,53.26 L62.94,62.2 A2.24,2.24 0 0,1 60.71,64.44 A2.24,2.24 0 0,1 58.47,62.2 L58.47,53.26 A2.24,2.24 0 0,1 60.71,51.02 Z" />
</vector>
```

### 10.4 `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` (identical)

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

`minSdk` is 26, so no PNG mipmaps are needed. The splash screen (Android 12+ and
`core-splashscreen` below that) uses the adaptive foreground. Set it in
`res/values/themes.xml` on a `Theme.SplashScreen` child:
`windowSplashScreenBackground = #F9F9FC` (`values-night`: `#111416`),
`windowSplashScreenAnimatedIcon = @drawable/ic_launcher_foreground`,
`windowSplashScreenIconBackgroundColor = @color/ic_launcher_background`,
`postSplashScreenTheme = @style/Theme.ClineTermux`. Call `installSplashScreen()` in
`MainActivity`. The splash stays up only until the first frame. The kernel probe
**must not** hold it, because the kernel states have their own UI.

### 10.5 Notification small icon `res/drawable/ic_stat_cline.xml`

The small icon is flat, white and filled, 24dp, with the same silhouette scaled to 20dp
wide inside 2dp padding and eyes cut out (1.7dp wide at 24dp, still legible):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Status-bar glyph: the Cline bot, flat and white; the system tints it. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="M6.98,5.75 L9.19,5.75 L9.19,5.64 C9.19,4.09 10.46,2.88 12,2.88 C13.54,2.88 14.81,4.09 14.81,5.64 L14.81,5.75 L17.02,5.75 A2.67,2.67 0 0,1 19.69,8.42 L19.69,10.6 L21.73,12.9 C22,13.2 22,13.66 21.73,13.97 L19.69,16.27 L19.69,18.45 A2.67,2.67 0 0,1 17.02,21.12 L6.98,21.12 A2.67,2.67 0 0,1 4.31,18.45 L4.31,16.27 L2.27,13.97 C2,13.66 2,13.2 2.27,12.9 L4.31,10.6 L4.31,8.42 A2.67,2.67 0 0,1 6.98,5.75 Z M9.42,10.85 A0.86,0.86 0 0,1 10.28,11.71 L10.28,15.15 A0.86,0.86 0 0,1 9.42,16.01 A0.86,0.86 0 0,1 8.56,15.15 L8.56,11.71 A0.86,0.86 0 0,1 9.42,10.85 Z M14.58,10.85 A0.86,0.86 0 0,1 15.44,11.71 L15.44,15.15 A0.86,0.86 0 0,1 14.58,16.01 A0.86,0.86 0 0,1 13.72,15.15 L13.72,11.71 A0.86,0.86 0 0,1 14.58,10.85 Z" />
</vector>
```

Use it for every notification channel: "Cline is working..." (foreground service),
task completed or failed, "Tool approval required", and question asked. The accent
colour follows section 1.7.

### 10.6 In-app logo `res/drawable/ic_cline_logo.xml` and the app-icon choice

`ic_cline_logo` is the desktop `ClineLogo` (outline mark, `public/icon.svg` path
verbatim in its native 113-unit viewport). Tint it with `LocalContentColor` via
`Icon(ImageVector.vectorResource(R.drawable.ic_cline_logo), …)`. It is used in the
drawer header, the rail, the KernelChip and the welcome hero.

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Desktop ClineLogo: apps/examples/desktop-app/webview/public/icon.svg, path verbatim (113-unit viewport);
     the two eye rects (x=37.3 / 66.1, y=49.8, 9.6x28.8, rx=4.8) written as paths. Tinted at use site. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="113"
    android:viewportHeight="113">
    <path
        android:fillColor="#FF1C1C24"
        android:pathData="M56.4998 8.99805L56.4999 5.29805H56.4998V8.99805ZM68.4998 20.708L72.1998 20.7081V20.708H68.4998ZM67.9929 25L64.3662 24.2671L63.4704 28.7H67.9929V25ZM84.4998 25L84.4998 21.3H84.4998V25ZM95.7 49.7998H92V51.2066L92.9346 52.258L95.7 49.7998ZM108.027 63.667L110.793 61.2088L110.792 61.2088L108.027 63.667ZM108.027 64.7305L110.792 67.1887L110.793 67.1883L108.027 64.7305ZM95.7 78.5977L92.9346 76.1394L92 77.1909V78.5977H95.7ZM95.7 92.2002L99.3999 92.2002V92.2002H95.7ZM84.4998 103.4V107.1H84.4998L84.4998 103.4ZM17.2996 92.2002H13.5996V92.2002L17.2996 92.2002ZM17.2996 78.5986H20.9996V77.1918L20.0649 76.1404L17.2996 78.5986ZM4.97144 64.7305L2.20602 67.1886L2.20611 67.1887L4.97144 64.7305ZM4.97144 63.667L7.73685 66.1251L7.73685 66.1251L4.97144 63.667ZM17.2996 49.7979L20.065 52.256L20.9996 51.2046V49.7979H17.2996ZM45.0066 25V28.7H49.5292L48.6333 24.267L45.0066 25ZM44.4998 20.708H40.7998V20.7081L44.4998 20.708ZM56.4998 8.99805L56.4996 12.698C61.1812 12.6982 64.7998 16.3247 64.7998 20.708H68.4998H72.1998C72.1998 12.0452 65.0728 5.29829 56.4999 5.29805L56.4998 8.99805ZM68.4998 20.708L64.7998 20.7079C64.7997 21.6154 64.6614 22.8066 64.3662 24.2671L67.9929 25L71.6196 25.7329C71.969 24.004 72.1997 22.2864 72.1998 20.7081L68.4998 20.708ZM67.9929 25V28.7H84.4998V25V21.3H67.9929V25ZM84.4998 25L84.4997 28.7C88.6419 28.7001 92 32.0582 92 36.2002H95.7H99.3999C99.3999 27.9712 92.7287 21.3001 84.4998 21.3L84.4998 25ZM95.7 36.2002H92V49.7998H95.7H99.3999V36.2002H95.7ZM95.7 49.7998L92.9346 52.258L105.262 66.1252L108.027 63.667L110.792 61.2088L98.4653 47.3416L95.7 49.7998ZM108.027 63.667L105.262 66.1251C104.285 65.026 104.285 63.371 105.261 62.2727L108.027 64.7305L110.793 67.1883C112.308 65.4837 112.308 62.9141 110.793 61.2088L108.027 63.667ZM108.027 64.7305L105.262 62.2722L92.9346 76.1394L95.7 78.5977L98.4653 81.0559L110.792 67.1887L108.027 64.7305ZM95.7 78.5977H92V92.2002H95.7H99.3999V78.5977H95.7ZM95.7 92.2002L92 92.2002C91.9999 96.3422 88.6418 99.7003 84.4997 99.7004L84.4998 103.4L84.4998 107.1C92.7287 107.1 99.3999 100.429 99.3999 92.2002L95.7 92.2002ZM84.4998 103.4V99.7004H28.4998V103.4V107.1H84.4998V103.4ZM28.4998 103.4V99.7004C24.3577 99.7004 20.9996 96.3423 20.9996 92.2002L17.2996 92.2002L13.5996 92.2002C13.5996 100.429 20.2707 107.1 28.4998 107.1V103.4ZM17.2996 92.2002H20.9996V78.5986H17.2996H13.5996V92.2002H17.2996ZM17.2996 78.5986L20.0649 76.1404L7.73676 62.2722L4.97144 64.7305L2.20611 67.1887L14.5342 81.0569L17.2996 78.5986ZM4.97144 64.7305L7.73685 62.2723C8.71353 63.3711 8.71353 65.0264 7.73685 66.1251L4.97144 63.667L2.20602 61.2088C0.690476 62.9138 0.690476 65.4836 2.20602 67.1886L4.97144 64.7305ZM4.97144 63.667L7.73685 66.1251L20.065 52.256L17.2996 49.7979L14.5341 47.3397L2.20602 61.2088L4.97144 63.667ZM17.2996 49.7979H20.9996V36.2002H17.2996H13.5996V49.7979H17.2996ZM17.2996 36.2002H20.9996C20.9996 32.0581 24.3576 28.7 28.4998 28.7V25V21.3C20.2707 21.3 13.5996 27.9712 13.5996 36.2002H17.2996ZM28.4998 25V28.7H45.0066V25V21.3H28.4998V25ZM45.0066 25L48.6333 24.267C48.3381 22.8066 48.1998 21.6154 48.1998 20.7079L44.4998 20.708L40.7998 20.7081C40.7998 22.2864 41.0305 24.0039 41.3799 25.733L45.0066 25ZM44.4998 20.708H48.1998C48.1998 16.3244 51.8182 12.698 56.4998 12.698V8.99805V5.29805C47.9264 5.29805 40.7998 12.0451 40.7998 20.708H44.4998Z" />
    <path
        android:fillColor="#FF1C1C24"
        android:pathData="M70.9,49.8008 A4.8,4.8 0 0,1 75.7,54.6008 L75.7,73.8008 A4.8,4.8 0 0,1 70.9,78.6008 A4.8,4.8 0 0,1 66.1,73.8008 L66.1,54.6008 A4.8,4.8 0 0,1 70.9,49.8008 Z M42.1,49.8008 A4.8,4.8 0 0,1 46.9,54.6008 L46.9,73.8008 A4.8,4.8 0 0,1 42.1,78.6008 A4.8,4.8 0 0,1 37.3,73.8008 L37.3,54.6008 A4.8,4.8 0 0,1 42.1,49.8008 Z" />
</vector>
```

**App icon setting** (feature matrix "Theme … app icon"; desktop Settings > General >
"App icon"). Settings > Appearance > "App icon" offers **"Midnight"** (default) and
**"Classic"**. Classic uses `@color/ic_launcher_background_classic` `#6E56CF` with the
same foreground recoloured to `#FFF4F0FE` (accent-3; contrast 4.8:1 on the violet,
following `public/app-icons/classic.png`). The desktop's "Hologram" and "Chip" are
photographic renders that do not translate into flat vector layers, so they are left
out. Implement this as two `<activity-alias>` entries (`.LauncherMidnight` enabled by
default, `.LauncherClassic` disabled) targeting `MainActivity`, switched with
`PackageManager.setComponentEnabledSetting(…, DONT_KILL_APP)`. The Classic layers live
in `mipmap-anydpi-v26/ic_launcher_classic.xml` + `drawable/ic_launcher_foreground_classic.xml`
(same path, fill `#FFF4F0FE`). Show the snackbar "The launcher may take a moment to
update the icon." *(Android)* after switching.

---

## 11. Implementer checklist (acceptance)

Theme and colour

- [ ] `ClineTheme` uses `paletteColorScheme` (copied from the voice app with the origin
      comment) on API 31+ when "Dynamic colour" is on, and `ClineLightScheme` /
      `ClineDarkScheme` otherwise. `dynamicLight/DarkColorScheme` is never called.
- [ ] The theme choice (System default / Light / Dark), "Dynamic colour" and "Font size"
      (12–20, default 15) persist in DataStore and apply without a restart.
- [ ] `ClineColors` light/dark values match section 1.5 exactly and do not change with
      the wallpaper.
- [ ] Every status dot, label and icon comes from the single mapping in section 1.6.
      A grep for `Color(0x` outside `ui/theme/` returns nothing.
- [ ] Section 1.8 pairs are the only text/background pairs used with the brand scheme.
      Timestamps use `onSurfaceVariant` without alpha.
- [ ] Edge-to-edge is on, the status-bar icon colour follows the theme, and gesture
      navigation has no scrim.

Typography

- [ ] Letter spacing is 0 on every style. title/label styles are Normal weight and
      emphasis is SemiBold. A Chinese session title renders in one weight next to Latin
      digits on the S25U.
- [ ] Geist Mono is bundled with its OFL license (or `FontFamily.Monospace` falls back
      cleanly). Code, commands, diffs, model ids, tokens, costs and `+N -N` use
      `ClineMono`.
- [ ] Layouts hold at system font scale 2.0 × in-app size 20.

Components

- [ ] Each section 4.1 component exists in `ui/components/` with a `@Preview` for every
      listed state, in light and dark.
- [ ] Tool row labels come from a Kotlin port of `buildToolSummary` with unit tests
      covering read/edit/command/search/web/spawn/team/skill/mcp/question/other,
      aggregates and fallbacks.
- [ ] Tool rows keep the label from moving when the spinner swaps with the icon, start
      folded whatever their body holds (A23), and stay open once the user opens them.
- [ ] Tool, reasoning and work rows are 40dp with 18dp icons and `bodySmall` labels, all
      from `DisclosureRowTokens` (A24).
- [ ] Reasoning shows "Thinking" (with shimmer) and then "Thought for Ns", and the body
      is capped at 192dp.
- [ ] Finished runs fold into "Worked for … and made N tool calls".
- [ ] Dismissing the approval sheet never rejects. The banner reopens it. "Stop agent"
      is always reachable while an approval is pending.
- [ ] Single-choice questions answer on tap. Multiple choice needs Submit. The custom
      answer field stays above the IME.
- [ ] The context ring is hidden when the context window is unknown and its colour
      thresholds are 50 % and 75 %.
- [ ] All copy is from `strings.xml`, and every string matches the desktop text quoted
      here character for character, including "..." versus "…".

Layout and navigation

- [ ] Compact uses a modal drawer, medium uses rail + modal drawer, expanded uses rail +
      list pane + detail, and ≥1200dp adds the Changes supporting pane. Height-compact
      windows drop the list pane and the hero.
- [ ] Verified on the S25U (portrait, landscape, split screen) and the Tab S7+ (portrait
      and landscape) with screenshots in `screenshots/`.
- [ ] The composer rides the IME and the nav bar with no double inset and no jump. The
      transcript stays anchored to the newest message when the IME opens.
- [ ] Predictive back follows the order in section 5.8 on every surface. No always-on
      `BackHandler` exists, and back-to-home animates from Home.
- [ ] FABs appear only where section 5.4 allows.

Motion

- [ ] Durations and easings follow section 6. With "Remove animations" on, the shimmer,
      pulse and skeletons are static.
- [ ] Streaming recomposes at most every 50ms, the stick-to-bottom threshold is 24dp,
      and the scroll button appears beyond 120dp.

Icons

- [ ] Only the Material Symbols Rounded drawables listed in section 7 are in
      `res/drawable/ms_*`. `material-icons-extended` is not a dependency.
- [ ] `TOOL_KIND_ICONS` and `TOOL_NAME_ICONS` match section 7.2, and error rows use
      `error`.
- [ ] The launcher foreground, monochrome, round and notification icons are the exact
      XML from section 10. The themed icon renders on One UI 8 / Android 16. The splash
      shows the bot on the `#1C1C24` tile.
- [ ] The Midnight/Classic app-icon switch works and survives a reboot.

Accessibility and i18n

- [ ] A TalkBack walk-through of Home → send → tool rows → approval → answer → session
      list announces every control with its desktop label and every status in words.
- [ ] Every row's long-press action is also a TalkBack custom action.
- [ ] Accessibility Scanner reports no touch-target or contrast issues on chat, drawer,
      settings, approval and model picker, in both themes.
- [ ] `HardcodedText` lint is an error, plurals are used for every count, and code,
      diffs and paths stay LTR under an RTL pseudo-locale (`ar-XB`).
- [ ] No secrets are present in resources or logs (token, keys). Nothing in this system
      stores provider keys.
