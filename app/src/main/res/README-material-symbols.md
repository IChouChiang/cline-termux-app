# Material Symbols Rounded drawables (`drawable/ms_*.xml`)

This file lives in the `res` root because AAPT rejects anything but `.xml`/`.png` inside
`res/drawable`. Generated assets; do not hand-edit the vectors. Every file is a Material Symbols
**Rounded** glyph at weight 400, grade 0, optical size 24 (design-system.md section 7.1),
downloaded as an Android vector drawable from Google's repository
[google/material-design-icons](https://github.com/google/material-design-icons)
(`symbols/android/<name>/materialsymbolsrounded/<name>[_fill1]_24px.xml`).

License: Apache License 2.0 (https://github.com/google/material-design-icons/blob/master/LICENSE).

Changes applied to the downloaded files:

- `android:tint="?attr/colorControlNormal"` became `?android:attr/colorControlNormal`. The
  app has no AppCompat, so the app-namespace attribute would not resolve. Compose ignores
  theme-attribute tints and `Icon()` tints with `LocalContentColor`.
- `android:autoMirrored="true"` was added where section 7.1 asks for it and Google's file
  did not already set it (`subdirectory_arrow_left`, `left_panel_open`, `left_panel_close`).
- A provenance comment with the source URL was prepended.

Use them through `dev.cline.termux.ui.icons.ClineIcons`. `_fill` files are fill-1 variants
for selected navigation items and filled status icons.

Substitutions (design-system name -> repository name): `sd_storage` -> `sd_card`,
`gpp_good` -> `verified_user` (the same glyph, codepoint `f013`; the repository ships it only
under the second name).

Total files: 137.

## 7.2 Tool kinds

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_file_copy.xml` | `file_copy` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/file_copy/materialsymbolsrounded/file_copy_24px.xml) |
| `ms_edit.xml` | `edit` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/edit/materialsymbolsrounded/edit_24px.xml) |
| `ms_terminal.xml` | `terminal` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/terminal/materialsymbolsrounded/terminal_24px.xml) |
| `ms_manage_search.xml` | `manage_search` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/manage_search/materialsymbolsrounded/manage_search_24px.xml) |
| `ms_web.xml` | `web` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/web/materialsymbolsrounded/web_24px.xml) |
| `ms_person.xml` | `person` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/person/materialsymbolsrounded/person_24px.xml) |
| `ms_group.xml` | `group` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/group/materialsymbolsrounded/group_24px.xml) |
| `ms_library_books.xml` | `library_books` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/library_books/materialsymbolsrounded/library_books_24px.xml) |
| `ms_deployed_code.xml` | `deployed_code` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/deployed_code/materialsymbolsrounded/deployed_code_24px.xml) |
| `ms_contact_support.xml` | `contact_support` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/contact_support/materialsymbolsrounded/contact_support_24px.xml) |
| `ms_build.xml` | `build` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/build/materialsymbolsrounded/build_24px.xml) |
| `ms_extension.xml` | `extension` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/extension/materialsymbolsrounded/extension_24px.xml) |
| `ms_exit_to_app.xml` | `exit_to_app` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/exit_to_app/materialsymbolsrounded/exit_to_app_24px.xml) |
| `ms_error.xml` | `error` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/error/materialsymbolsrounded/error_24px.xml) |
| `ms_error_fill.xml` | `error` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/error/materialsymbolsrounded/error_fill1_24px.xml) |
| `ms_neurology.xml` | `neurology` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/neurology/materialsymbolsrounded/neurology_24px.xml) |

## 7.3 Session and kernel states

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_gpp_good_fill.xml` | `gpp_good` (as `verified_user`) | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/verified_user/materialsymbolsrounded/verified_user_fill1_24px.xml) (auto-approve control, Ask state, A15) |
| `ms_gpp_maybe.xml` | `gpp_maybe` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/gpp_maybe/materialsymbolsrounded/gpp_maybe_24px.xml) |
| `ms_gpp_maybe_fill.xml` | `gpp_maybe` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/gpp_maybe/materialsymbolsrounded/gpp_maybe_fill1_24px.xml) |
| `ms_contact_support_fill.xml` | `contact_support` | 1 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/contact_support/materialsymbolsrounded/contact_support_fill1_24px.xml) |
| `ms_pending.xml` | `pending` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/pending/materialsymbolsrounded/pending_24px.xml) |
| `ms_pending_fill.xml` | `pending` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/pending/materialsymbolsrounded/pending_fill1_24px.xml) |
| `ms_check_circle.xml` | `check_circle` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/check_circle/materialsymbolsrounded/check_circle_24px.xml) |
| `ms_check_circle_fill.xml` | `check_circle` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/check_circle/materialsymbolsrounded/check_circle_fill1_24px.xml) |
| `ms_check.xml` | `check` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/check/materialsymbolsrounded/check_24px.xml) |
| `ms_cancel.xml` | `cancel` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/cancel/materialsymbolsrounded/cancel_24px.xml) |
| `ms_cancel_fill.xml` | `cancel` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/cancel/materialsymbolsrounded/cancel_fill1_24px.xml) |
| `ms_schedule.xml` | `schedule` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/schedule/materialsymbolsrounded/schedule_24px.xml) |
| `ms_schedule_fill.xml` | `schedule` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/schedule/materialsymbolsrounded/schedule_fill1_24px.xml) |
| `ms_push_pin.xml` | `push_pin` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/push_pin/materialsymbolsrounded/push_pin_24px.xml) |
| `ms_push_pin_fill.xml` | `push_pin` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/push_pin/materialsymbolsrounded/push_pin_fill1_24px.xml) |

## 7.4 Chrome and actions, 5.2 navigation

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_arrow_upward.xml` | `arrow_upward` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/arrow_upward/materialsymbolsrounded/arrow_upward_24px.xml) |
| `ms_stop.xml` | `stop` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/stop/materialsymbolsrounded/stop_24px.xml) |
| `ms_attach_file.xml` | `attach_file` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/attach_file/materialsymbolsrounded/attach_file_24px.xml) |
| `ms_memory.xml` | `memory` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/memory/materialsymbolsrounded/memory_24px.xml) |
| `ms_content_copy.xml` | `content_copy` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/content_copy/materialsymbolsrounded/content_copy_24px.xml) |
| `ms_undo.xml` | `undo` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/undo/materialsymbolsrounded/undo_24px.xml) |
| `ms_fork_right.xml` | `fork_right` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/fork_right/materialsymbolsrounded/fork_right_24px.xml) |
| `ms_add.xml` | `add` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/add/materialsymbolsrounded/add_24px.xml) |
| `ms_edit_square.xml` | `edit_square` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/edit_square/materialsymbolsrounded/edit_square_24px.xml) |
| `ms_more_vert.xml` | `more_vert` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/more_vert/materialsymbolsrounded/more_vert_24px.xml) |
| `ms_delete.xml` | `delete` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/delete/materialsymbolsrounded/delete_24px.xml) |
| `ms_search.xml` | `search` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/search/materialsymbolsrounded/search_24px.xml) |
| `ms_filter_list.xml` | `filter_list` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/filter_list/materialsymbolsrounded/filter_list_24px.xml) |
| `ms_sort.xml` | `sort` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/sort/materialsymbolsrounded/sort_24px.xml) |
| `ms_smart_toy.xml` | `smart_toy` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/smart_toy/materialsymbolsrounded/smart_toy_24px.xml) |
| `ms_settings.xml` | `settings` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/settings/materialsymbolsrounded/settings_24px.xml) |
| `ms_settings_fill.xml` | `settings` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/settings/materialsymbolsrounded/settings_fill1_24px.xml) |
| `ms_account_circle.xml` | `account_circle` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/account_circle/materialsymbolsrounded/account_circle_24px.xml) |
| `ms_power.xml` | `power` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/power/materialsymbolsrounded/power_24px.xml) |
| `ms_storefront.xml` | `storefront` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/storefront/materialsymbolsrounded/storefront_24px.xml) |
| `ms_notifications.xml` | `notifications` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/notifications/materialsymbolsrounded/notifications_24px.xml) |
| `ms_palette.xml` | `palette` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/palette/materialsymbolsrounded/palette_24px.xml) |
| `ms_info.xml` | `info` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/info/materialsymbolsrounded/info_24px.xml) |
| `ms_folder.xml` | `folder` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/folder/materialsymbolsrounded/folder_24px.xml) |
| `ms_folder_open.xml` | `folder_open` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/folder_open/materialsymbolsrounded/folder_open_24px.xml) |
| `ms_cable.xml` | `cable` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/cable/materialsymbolsrounded/cable_24px.xml) |
| `ms_expand_more.xml` | `expand_more` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/expand_more/materialsymbolsrounded/expand_more_24px.xml) |
| `ms_arrow_downward.xml` | `arrow_downward` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/arrow_downward/materialsymbolsrounded/arrow_downward_24px.xml) |
| `ms_subdirectory_arrow_left.xml` | `subdirectory_arrow_left` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/subdirectory_arrow_left/materialsymbolsrounded/subdirectory_arrow_left_24px.xml) |
| `ms_close.xml` | `close` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/close/materialsymbolsrounded/close_24px.xml) |
| `ms_arrow_back.xml` | `arrow_back` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/arrow_back/materialsymbolsrounded/arrow_back_24px.xml) |
| `ms_left_panel_open.xml` | `left_panel_open` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/left_panel_open/materialsymbolsrounded/left_panel_open_24px.xml) |
| `ms_left_panel_close.xml` | `left_panel_close` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/left_panel_close/materialsymbolsrounded/left_panel_close_24px.xml) |
| `ms_call_split.xml` | `call_split` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/call_split/materialsymbolsrounded/call_split_24px.xml) |
| `ms_share.xml` | `share` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/share/materialsymbolsrounded/share_24px.xml) |
| `ms_menu.xml` | `menu` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/menu/materialsymbolsrounded/menu_24px.xml) |
| `ms_chat_bubble.xml` | `chat_bubble` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/chat_bubble/materialsymbolsrounded/chat_bubble_24px.xml) |
| `ms_chat_bubble_fill.xml` | `chat_bubble` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/chat_bubble/materialsymbolsrounded/chat_bubble_fill1_24px.xml) |
| `ms_extension_fill.xml` | `extension` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/extension/materialsymbolsrounded/extension_fill1_24px.xml) |

## 4.2 rows and design/screens/*.md

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_autorenew.xml` | `autorenew` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/autorenew/materialsymbolsrounded/autorenew_24px.xml) |
| `ms_pause_circle.xml` | `pause_circle` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/pause_circle/materialsymbolsrounded/pause_circle_24px.xml) |
| `ms_chevron_right.xml` | `chevron_right` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/chevron_right/materialsymbolsrounded/chevron_right_24px.xml) |
| `ms_audio_file.xml` | `audio_file` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/audio_file/materialsymbolsrounded/audio_file_24px.xml) |
| `ms_movie.xml` | `movie` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/movie/materialsymbolsrounded/movie_24px.xml) |
| `ms_description.xml` | `description` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/description/materialsymbolsrounded/description_24px.xml) |
| `ms_broken_image.xml` | `broken_image` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/broken_image/materialsymbolsrounded/broken_image_24px.xml) |
| `ms_open_in_full.xml` | `open_in_full` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/open_in_full/materialsymbolsrounded/open_in_full_24px.xml) |
| `ms_difference.xml` | `difference` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/difference/materialsymbolsrounded/difference_24px.xml) |
| `ms_cloud_off.xml` | `cloud_off` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/cloud_off/materialsymbolsrounded/cloud_off_24px.xml) |
| `ms_block.xml` | `block` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/block/materialsymbolsrounded/block_24px.xml) |
| `ms_warning.xml` | `warning` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/warning/materialsymbolsrounded/warning_24px.xml) |
| `ms_warning_fill.xml` | `warning` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/warning/materialsymbolsrounded/warning_fill1_24px.xml) |
| `ms_search_off.xml` | `search_off` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/search_off/materialsymbolsrounded/search_off_24px.xml) |
| `ms_history.xml` | `history` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/history/materialsymbolsrounded/history_24px.xml) |
| `ms_unfold_more.xml` | `unfold_more` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/unfold_more/materialsymbolsrounded/unfold_more_24px.xml) |
| `ms_unfold_less.xml` | `unfold_less` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/unfold_less/materialsymbolsrounded/unfold_less_24px.xml) |
| `ms_bedtime.xml` | `bedtime` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/bedtime/materialsymbolsrounded/bedtime_24px.xml) |
| `ms_stop_circle.xml` | `stop_circle` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/stop_circle/materialsymbolsrounded/stop_circle_24px.xml) |
| `ms_link_off.xml` | `link_off` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/link_off/materialsymbolsrounded/link_off_24px.xml) |
| `ms_hourglass.xml` | `hourglass` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/hourglass/materialsymbolsrounded/hourglass_24px.xml) |
| `ms_refresh.xml` | `refresh` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/refresh/materialsymbolsrounded/refresh_24px.xml) |
| `ms_visibility.xml` | `visibility` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/visibility/materialsymbolsrounded/visibility_24px.xml) |
| `ms_visibility_off.xml` | `visibility_off` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/visibility_off/materialsymbolsrounded/visibility_off_24px.xml) |
| `ms_star.xml` | `star` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/star/materialsymbolsrounded/star_24px.xml) |
| `ms_star_fill.xml` | `star` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/star/materialsymbolsrounded/star_fill1_24px.xml) |
| `ms_key.xml` | `key` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/key/materialsymbolsrounded/key_24px.xml) |
| `ms_language.xml` | `language` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/language/materialsymbolsrounded/language_24px.xml) |
| `ms_devices.xml` | `devices` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/devices/materialsymbolsrounded/devices_24px.xml) |
| `ms_open_in_new.xml` | `open_in_new` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/open_in_new/materialsymbolsrounded/open_in_new_24px.xml) |
| `ms_link.xml` | `link` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/link/materialsymbolsrounded/link_24px.xml) |
| `ms_add_comment.xml` | `add_comment` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/add_comment/materialsymbolsrounded/add_comment_24px.xml) |
| `ms_home.xml` | `home` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/home/materialsymbolsrounded/home_24px.xml) |
| `ms_add_circle.xml` | `add_circle` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/add_circle/materialsymbolsrounded/add_circle_24px.xml) |
| `ms_restart_alt.xml` | `restart_alt` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/restart_alt/materialsymbolsrounded/restart_alt_24px.xml) |
| `ms_download.xml` | `download` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/download/materialsymbolsrounded/download_24px.xml) |
| `ms_analytics.xml` | `analytics` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/analytics/materialsymbolsrounded/analytics_24px.xml) |
| `ms_remove.xml` | `remove` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/remove/materialsymbolsrounded/remove_24px.xml) |
| `ms_sd_card.xml` | `sd_card (for `sd_storage`)` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/sd_card/materialsymbolsrounded/sd_card_24px.xml) |
| `ms_mic.xml` | `mic` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/mic/materialsymbolsrounded/mic_24px.xml) |
| `ms_photo_library.xml` | `photo_library` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/photo_library/materialsymbolsrounded/photo_library_24px.xml) |
| `ms_photo_camera.xml` | `photo_camera` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/photo_camera/materialsymbolsrounded/photo_camera_24px.xml) |
| `ms_image.xml` | `image` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/image/materialsymbolsrounded/image_24px.xml) |
| `ms_draft.xml` | `draft` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/draft/materialsymbolsrounded/draft_24px.xml) |
| `ms_troubleshoot.xml` | `troubleshoot` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/troubleshoot/materialsymbolsrounded/troubleshoot_24px.xml) |
| `ms_battery_full.xml` | `battery_full` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/battery_full/materialsymbolsrounded/battery_full_24px.xml) |
| `ms_account_tree.xml` | `account_tree` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/account_tree/materialsymbolsrounded/account_tree_24px.xml) |
| `ms_layers.xml` | `layers` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/layers/materialsymbolsrounded/layers_24px.xml) |
| `ms_graphic_eq.xml` | `graphic_eq` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/graphic_eq/materialsymbolsrounded/graphic_eq_24px.xml) |
| `ms_wifi_off.xml` | `wifi_off` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/wifi_off/materialsymbolsrounded/wifi_off_24px.xml) |
| `ms_bolt.xml` | `bolt` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/bolt/materialsymbolsrounded/bolt_24px.xml) (also the auto-approve control's Auto state, A15) |
| `ms_play_arrow.xml` | `play_arrow` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/play_arrow/materialsymbolsrounded/play_arrow_24px.xml) |
| `ms_code.xml` | `code` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/code/materialsymbolsrounded/code_24px.xml) |
| `ms_arrow_drop_down.xml` | `arrow_drop_down` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/arrow_drop_down/materialsymbolsrounded/arrow_drop_down_24px.xml) |

## Marketplace (customize-and-rest.md §3.5, Appendix A)

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_dns.xml` | `dns` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/dns/materialsymbolsrounded/dns_24px.xml) |
| `ms_verified_fill.xml` | `verified` | 1 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/verified/materialsymbolsrounded/verified_fill1_24px.xml) |

## Schedules (customize-and-rest.md Appendix A)

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_bug_report.xml` | `bug_report` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/bug_report/materialsymbolsrounded/bug_report_24px.xml) |
| `ms_shield.xml` | `shield` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/shield/materialsymbolsrounded/shield_24px.xml) |
| `ms_newspaper.xml` | `newspaper` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/newspaper/materialsymbolsrounded/newspaper_24px.xml) |
| `ms_menu_book.xml` | `menu_book` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/menu_book/materialsymbolsrounded/menu_book_24px.xml) |
| `ms_pause.xml` | `pause` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/pause/materialsymbolsrounded/pause_24px.xml) |
| `ms_calendar_month.xml` | `calendar_month` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/calendar_month/materialsymbolsrounded/calendar_month_24px.xml) |

## customize-and-rest.md C8 and C11 (account-agents-about lane)

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_logout.xml` | `logout` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/logout/materialsymbolsrounded/logout_24px.xml) |
| `ms_chat.xml` | `chat` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/chat/materialsymbolsrounded/chat_24px.xml) |
| `ms_task_alt.xml` | `task_alt` | 0 |  | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/task_alt/materialsymbolsrounded/task_alt_24px.xml) |
| `ms_arrow_forward.xml` | `arrow_forward` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/arrow_forward/materialsymbolsrounded/arrow_forward_24px.xml) |

## home-chat.md §6.4 (chat-screen lane)

| File | Symbol | Fill | Mirrored | Source |
| --- | --- | --- | --- | --- |
| `ms_u_turn_left.xml` | `u_turn_left` | 0 | yes | [xml](https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/u_turn_left/materialsymbolsrounded/u_turn_left_24px.xml) |
