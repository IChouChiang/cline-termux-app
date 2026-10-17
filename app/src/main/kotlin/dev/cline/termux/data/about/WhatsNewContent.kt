package dev.cline.termux.data.about

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.cline.termux.R

/*
 * The What's new catch-up entries (customize-and-rest.md §8.1, §8.3; desktop
 * `lib/whats-new-content.ts`). Newest first; `latest()` is the first. Each entry is written
 * for this app (the desktop's "Work anywhere, in parallel" is not copied). Copy lives in
 * `strings_tier4_data.xml`; icons are named by [WhatsNewIcon] and resolved to Material
 * Symbols by the dialog (data packages hold no ImageVector).
 */

/** The DS §7 symbol above each highlight title (decorative; the title carries the meaning). */
enum class WhatsNewIcon { Chat, TaskAlt, Schedule, Extension }

@Immutable
data class WhatsNewHighlight(@get:StringRes val title: Int, @get:StringRes val description: Int, val icon: WhatsNewIcon)

@Immutable
data class WhatsNewRelease(val id: String, @get:StringRes val title: Int, val highlights: List<WhatsNewHighlight>)

/** `WHATS_NEW_RELEASES`: newest first; ids must stay unique so seen-tracking cannot collide. */
val WHATS_NEW_RELEASES: List<WhatsNewRelease> = listOf(
    WhatsNewRelease(
        id = "android-0.1",
        title = R.string.whats_new_android_0_1_title,
        highlights = listOf(
            WhatsNewHighlight(R.string.whats_new_android_0_1_chat_title, R.string.whats_new_android_0_1_chat_description, WhatsNewIcon.Chat),
            WhatsNewHighlight(R.string.whats_new_android_0_1_approvals_title, R.string.whats_new_android_0_1_approvals_description, WhatsNewIcon.TaskAlt),
            WhatsNewHighlight(R.string.whats_new_android_0_1_schedules_title, R.string.whats_new_android_0_1_schedules_description, WhatsNewIcon.Schedule),
            WhatsNewHighlight(R.string.whats_new_android_0_1_customize_title, R.string.whats_new_android_0_1_customize_description, WhatsNewIcon.Extension),
        ),
    ),
)
