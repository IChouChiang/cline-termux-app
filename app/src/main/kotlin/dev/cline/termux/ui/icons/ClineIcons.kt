package dev.cline.termux.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.cline.termux.R

/**
 * Material Symbols Rounded (weight 400, grade 0, optical size 24) as [ImageVector]s.
 *
 * Every drawable lives in `res/drawable/ms_<symbol>.xml` (manifest: `res/README-material-symbols.md`)
 * and is exposed here once, by symbol name (`ClineIcons.ContentCopy`); `...Filled` members are the
 * fill-1 variants. The nested [Tools], [States] and [Chrome] objects name the same vectors by their
 * role, following the tables in design-system.md sections 7.2, 7.3 and 7.4.
 *
 * Draw them with `Icon(ClineIcons.Close, contentDescription = ...)`; the vectors carry no colour of
 * their own and take `LocalContentColor`. Sizes: 24dp in buttons and list items, 20dp in tool rows
 * and chips, 16dp in meta lines (section 7.1).
 */
object ClineIcons {

    @Composable
    private fun vector(@DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)

    // Symbols, alphabetically (fill-0 unless named Filled).
    val AccountCircle: ImageVector @Composable get() = vector(R.drawable.ms_account_circle)
    val AccountTree: ImageVector @Composable get() = vector(R.drawable.ms_account_tree)
    val Add: ImageVector @Composable get() = vector(R.drawable.ms_add)
    val AddCircle: ImageVector @Composable get() = vector(R.drawable.ms_add_circle)
    val AddComment: ImageVector @Composable get() = vector(R.drawable.ms_add_comment)
    val Analytics: ImageVector @Composable get() = vector(R.drawable.ms_analytics)
    val ArrowBack: ImageVector @Composable get() = vector(R.drawable.ms_arrow_back)
    val ArrowDownward: ImageVector @Composable get() = vector(R.drawable.ms_arrow_downward)
    val ArrowDropDown: ImageVector @Composable get() = vector(R.drawable.ms_arrow_drop_down)
    val ArrowForward: ImageVector @Composable get() = vector(R.drawable.ms_arrow_forward)
    val ArrowUpward: ImageVector @Composable get() = vector(R.drawable.ms_arrow_upward)
    val AttachFile: ImageVector @Composable get() = vector(R.drawable.ms_attach_file)
    val AudioFile: ImageVector @Composable get() = vector(R.drawable.ms_audio_file)
    val Autorenew: ImageVector @Composable get() = vector(R.drawable.ms_autorenew)
    val BatteryFull: ImageVector @Composable get() = vector(R.drawable.ms_battery_full)
    val Bedtime: ImageVector @Composable get() = vector(R.drawable.ms_bedtime)
    val Block: ImageVector @Composable get() = vector(R.drawable.ms_block)
    val Bolt: ImageVector @Composable get() = vector(R.drawable.ms_bolt)
    val BrokenImage: ImageVector @Composable get() = vector(R.drawable.ms_broken_image)
    val BugReport: ImageVector @Composable get() = vector(R.drawable.ms_bug_report)
    val Build: ImageVector @Composable get() = vector(R.drawable.ms_build)
    val Cable: ImageVector @Composable get() = vector(R.drawable.ms_cable)
    val CalendarMonth: ImageVector @Composable get() = vector(R.drawable.ms_calendar_month)
    val CallSplit: ImageVector @Composable get() = vector(R.drawable.ms_call_split)
    val Cancel: ImageVector @Composable get() = vector(R.drawable.ms_cancel)
    val CancelFilled: ImageVector @Composable get() = vector(R.drawable.ms_cancel_fill)
    val Chat: ImageVector @Composable get() = vector(R.drawable.ms_chat)
    val ChatBubble: ImageVector @Composable get() = vector(R.drawable.ms_chat_bubble)
    val ChatBubbleFilled: ImageVector @Composable get() = vector(R.drawable.ms_chat_bubble_fill)
    val Check: ImageVector @Composable get() = vector(R.drawable.ms_check)
    val CheckCircle: ImageVector @Composable get() = vector(R.drawable.ms_check_circle)
    val CheckCircleFilled: ImageVector @Composable get() = vector(R.drawable.ms_check_circle_fill)
    val ChevronRight: ImageVector @Composable get() = vector(R.drawable.ms_chevron_right)
    val Close: ImageVector @Composable get() = vector(R.drawable.ms_close)
    val CloudOff: ImageVector @Composable get() = vector(R.drawable.ms_cloud_off)
    val Code: ImageVector @Composable get() = vector(R.drawable.ms_code)
    val ContactSupport: ImageVector @Composable get() = vector(R.drawable.ms_contact_support)
    val ContactSupportFilled: ImageVector @Composable get() = vector(R.drawable.ms_contact_support_fill)
    val ContentCopy: ImageVector @Composable get() = vector(R.drawable.ms_content_copy)
    val Delete: ImageVector @Composable get() = vector(R.drawable.ms_delete)
    val DeployedCode: ImageVector @Composable get() = vector(R.drawable.ms_deployed_code)
    val Description: ImageVector @Composable get() = vector(R.drawable.ms_description)
    val Devices: ImageVector @Composable get() = vector(R.drawable.ms_devices)
    val Difference: ImageVector @Composable get() = vector(R.drawable.ms_difference)
    val Dns: ImageVector @Composable get() = vector(R.drawable.ms_dns)
    val Download: ImageVector @Composable get() = vector(R.drawable.ms_download)
    val Draft: ImageVector @Composable get() = vector(R.drawable.ms_draft)
    val Edit: ImageVector @Composable get() = vector(R.drawable.ms_edit)
    val EditSquare: ImageVector @Composable get() = vector(R.drawable.ms_edit_square)
    val Error: ImageVector @Composable get() = vector(R.drawable.ms_error)
    val ErrorFilled: ImageVector @Composable get() = vector(R.drawable.ms_error_fill)
    val ExitToApp: ImageVector @Composable get() = vector(R.drawable.ms_exit_to_app)
    val ExpandMore: ImageVector @Composable get() = vector(R.drawable.ms_expand_more)
    val Extension: ImageVector @Composable get() = vector(R.drawable.ms_extension)
    val ExtensionFilled: ImageVector @Composable get() = vector(R.drawable.ms_extension_fill)
    val FileCopy: ImageVector @Composable get() = vector(R.drawable.ms_file_copy)
    val FilterList: ImageVector @Composable get() = vector(R.drawable.ms_filter_list)
    val Folder: ImageVector @Composable get() = vector(R.drawable.ms_folder)
    val FolderOpen: ImageVector @Composable get() = vector(R.drawable.ms_folder_open)
    val ForkRight: ImageVector @Composable get() = vector(R.drawable.ms_fork_right)
    val GppGoodFilled: ImageVector @Composable get() = vector(R.drawable.ms_gpp_good_fill)
    val GppMaybe: ImageVector @Composable get() = vector(R.drawable.ms_gpp_maybe)
    val GppMaybeFilled: ImageVector @Composable get() = vector(R.drawable.ms_gpp_maybe_fill)
    val GraphicEq: ImageVector @Composable get() = vector(R.drawable.ms_graphic_eq)
    val Group: ImageVector @Composable get() = vector(R.drawable.ms_group)
    val History: ImageVector @Composable get() = vector(R.drawable.ms_history)
    val Home: ImageVector @Composable get() = vector(R.drawable.ms_home)
    val Hourglass: ImageVector @Composable get() = vector(R.drawable.ms_hourglass)
    val Image: ImageVector @Composable get() = vector(R.drawable.ms_image)
    val Info: ImageVector @Composable get() = vector(R.drawable.ms_info)
    val Key: ImageVector @Composable get() = vector(R.drawable.ms_key)
    val Language: ImageVector @Composable get() = vector(R.drawable.ms_language)
    val LeftPanelClose: ImageVector @Composable get() = vector(R.drawable.ms_left_panel_close)
    val Layers: ImageVector @Composable get() = vector(R.drawable.ms_layers)
    val LeftPanelOpen: ImageVector @Composable get() = vector(R.drawable.ms_left_panel_open)
    val LibraryBooks: ImageVector @Composable get() = vector(R.drawable.ms_library_books)
    val Link: ImageVector @Composable get() = vector(R.drawable.ms_link)
    val LinkOff: ImageVector @Composable get() = vector(R.drawable.ms_link_off)
    val Logout: ImageVector @Composable get() = vector(R.drawable.ms_logout)
    val ManageSearch: ImageVector @Composable get() = vector(R.drawable.ms_manage_search)
    val Memory: ImageVector @Composable get() = vector(R.drawable.ms_memory)
    val MenuBook: ImageVector @Composable get() = vector(R.drawable.ms_menu_book)
    val Menu: ImageVector @Composable get() = vector(R.drawable.ms_menu)
    val Mic: ImageVector @Composable get() = vector(R.drawable.ms_mic)
    val MoreVert: ImageVector @Composable get() = vector(R.drawable.ms_more_vert)
    val Movie: ImageVector @Composable get() = vector(R.drawable.ms_movie)
    val Neurology: ImageVector @Composable get() = vector(R.drawable.ms_neurology)
    val Newspaper: ImageVector @Composable get() = vector(R.drawable.ms_newspaper)
    val Notifications: ImageVector @Composable get() = vector(R.drawable.ms_notifications)
    val OpenInFull: ImageVector @Composable get() = vector(R.drawable.ms_open_in_full)
    val OpenInNew: ImageVector @Composable get() = vector(R.drawable.ms_open_in_new)
    val Palette: ImageVector @Composable get() = vector(R.drawable.ms_palette)
    val Pause: ImageVector @Composable get() = vector(R.drawable.ms_pause)
    val PauseCircle: ImageVector @Composable get() = vector(R.drawable.ms_pause_circle)
    val Pending: ImageVector @Composable get() = vector(R.drawable.ms_pending)
    val PendingFilled: ImageVector @Composable get() = vector(R.drawable.ms_pending_fill)
    val Person: ImageVector @Composable get() = vector(R.drawable.ms_person)
    val PhotoCamera: ImageVector @Composable get() = vector(R.drawable.ms_photo_camera)
    val PhotoLibrary: ImageVector @Composable get() = vector(R.drawable.ms_photo_library)
    val PlayArrow: ImageVector @Composable get() = vector(R.drawable.ms_play_arrow)
    val Power: ImageVector @Composable get() = vector(R.drawable.ms_power)
    val PushPin: ImageVector @Composable get() = vector(R.drawable.ms_push_pin)
    val PushPinFilled: ImageVector @Composable get() = vector(R.drawable.ms_push_pin_fill)
    val Refresh: ImageVector @Composable get() = vector(R.drawable.ms_refresh)
    val Remove: ImageVector @Composable get() = vector(R.drawable.ms_remove)
    val RestartAlt: ImageVector @Composable get() = vector(R.drawable.ms_restart_alt)
    val Schedule: ImageVector @Composable get() = vector(R.drawable.ms_schedule)
    val ScheduleFilled: ImageVector @Composable get() = vector(R.drawable.ms_schedule_fill)
    /** The specs say `sd_storage`; Material Symbols ships the glyph as `sd_card`. */
    val SdCard: ImageVector @Composable get() = vector(R.drawable.ms_sd_card)
    val Search: ImageVector @Composable get() = vector(R.drawable.ms_search)
    val SearchOff: ImageVector @Composable get() = vector(R.drawable.ms_search_off)
    val Settings: ImageVector @Composable get() = vector(R.drawable.ms_settings)
    val SettingsFilled: ImageVector @Composable get() = vector(R.drawable.ms_settings_fill)
    val Shield: ImageVector @Composable get() = vector(R.drawable.ms_shield)
    val Share: ImageVector @Composable get() = vector(R.drawable.ms_share)
    val SmartToy: ImageVector @Composable get() = vector(R.drawable.ms_smart_toy)
    val Sort: ImageVector @Composable get() = vector(R.drawable.ms_sort)
    val Star: ImageVector @Composable get() = vector(R.drawable.ms_star)
    val StarFilled: ImageVector @Composable get() = vector(R.drawable.ms_star_fill)
    val Stop: ImageVector @Composable get() = vector(R.drawable.ms_stop)
    val StopCircle: ImageVector @Composable get() = vector(R.drawable.ms_stop_circle)
    val Storefront: ImageVector @Composable get() = vector(R.drawable.ms_storefront)
    val SubdirectoryArrowLeft: ImageVector @Composable get() = vector(R.drawable.ms_subdirectory_arrow_left)
    val TaskAlt: ImageVector @Composable get() = vector(R.drawable.ms_task_alt)
    val Terminal: ImageVector @Composable get() = vector(R.drawable.ms_terminal)
    val Troubleshoot: ImageVector @Composable get() = vector(R.drawable.ms_troubleshoot)
    val Undo: ImageVector @Composable get() = vector(R.drawable.ms_undo)
    val UTurnLeft: ImageVector @Composable get() = vector(R.drawable.ms_u_turn_left)
    val UnfoldLess: ImageVector @Composable get() = vector(R.drawable.ms_unfold_less)
    val UnfoldMore: ImageVector @Composable get() = vector(R.drawable.ms_unfold_more)
    val VerifiedFilled: ImageVector @Composable get() = vector(R.drawable.ms_verified_fill)
    val Visibility: ImageVector @Composable get() = vector(R.drawable.ms_visibility)
    val VisibilityOff: ImageVector @Composable get() = vector(R.drawable.ms_visibility_off)
    val Warning: ImageVector @Composable get() = vector(R.drawable.ms_warning)
    val WarningFilled: ImageVector @Composable get() = vector(R.drawable.ms_warning_fill)
    val Web: ImageVector @Composable get() = vector(R.drawable.ms_web)
    val WifiOff: ImageVector @Composable get() = vector(R.drawable.ms_wifi_off)

    /**
     * The app's own mark, the Cline Termux C and cursor (section 10), tinted at the use site. It
     * stands where the desktop shows its `ClineLogo`; this app does not use the Cline logo.
     */
    val AppLogo: ImageVector @Composable get() = vector(R.drawable.ic_app_logo)

    /**
     * The auto-approve control (decided A15; docs/research/auto-approve-control.md): the ticked
     * shield is the guarded state, the bolt runs everything. Also the approval sheet's "Approve and
     * auto-approve from now on" and the Settings switch (both [Auto]).
     */
    object Approval {
        /** "Auto-approve all": `bolt`, fill 0. */
        val Auto: ImageVector @Composable get() = Bolt

        /** "Ask each time", and the pending state's target glyph: `gpp_good`, fill 1 (the ticked shield). */
        val Ask: ImageVector @Composable get() = GppGoodFilled
    }

    /** Section 7.2: tool-row icons by [ToolKind] and name override. Pick one with [toolIcon]. */
    object Tools {
        val Read: ImageVector @Composable get() = FileCopy
        val Edit: ImageVector @Composable get() = ClineIcons.Edit
        val Command: ImageVector @Composable get() = Terminal
        val Search: ImageVector @Composable get() = ManageSearch
        val Web: ImageVector @Composable get() = ClineIcons.Web
        val Spawn: ImageVector @Composable get() = Person
        val Team: ImageVector @Composable get() = Group
        val Skill: ImageVector @Composable get() = LibraryBooks
        val Mcp: ImageVector @Composable get() = DeployedCode
        val Question: ImageVector @Composable get() = ContactSupport
        val Other: ImageVector @Composable get() = Build
        val Plugins: ImageVector @Composable get() = Extension
        val SubmitAndExit: ImageVector @Composable get() = ExitToApp
        /** Any kind, error: fill 1 in the `error` colour. */
        val Error: ImageVector @Composable get() = ErrorFilled
        /** ReasoningBlock. */
        val Thinking: ImageVector @Composable get() = Neurology

        // The same vectors by symbol name, as the 7.2 table lists them.
        val FileCopy: ImageVector @Composable get() = ClineIcons.FileCopy
        val Terminal: ImageVector @Composable get() = ClineIcons.Terminal
        val ManageSearch: ImageVector @Composable get() = ClineIcons.ManageSearch
        val Person: ImageVector @Composable get() = ClineIcons.Person
        val Group: ImageVector @Composable get() = ClineIcons.Group
        val LibraryBooks: ImageVector @Composable get() = ClineIcons.LibraryBooks
        val DeployedCode: ImageVector @Composable get() = ClineIcons.DeployedCode
        val ContactSupport: ImageVector @Composable get() = ClineIcons.ContactSupport
        val Build: ImageVector @Composable get() = ClineIcons.Build
        val Extension: ImageVector @Composable get() = ClineIcons.Extension
        val ExitToApp: ImageVector @Composable get() = ClineIcons.ExitToApp
        val ErrorFilled: ImageVector @Composable get() = ClineIcons.ErrorFilled
        val Neurology: ImageVector @Composable get() = ClineIcons.Neurology
    }

    /** Section 7.3: session and kernel states (Running uses a CircularProgressIndicator). */
    object States {
        val ApprovalRequired: ImageVector @Composable get() = GppMaybeFilled
        val QuestionAsked: ImageVector @Composable get() = ContactSupport
        val Pending: ImageVector @Composable get() = ClineIcons.Pending
        val Completed: ImageVector @Composable get() = CheckCircleFilled
        /** Agents roster "completed". */
        val CompletedRoster: ImageVector @Composable get() = Check
        val Cancelled: ImageVector @Composable get() = Cancel
        /** Agents roster "cancelled/unresolved" (desktop `Clock3`). */
        val CancelledRoster: ImageVector @Composable get() = Schedule
        val Failed: ImageVector @Composable get() = ErrorFilled
        val Scheduled: ImageVector @Composable get() = Schedule
        val Pinned: ImageVector @Composable get() = PushPinFilled
        val Kernel: ImageVector @Composable get() = AppLogo
        val Termux: ImageVector @Composable get() = Terminal

        // The same vectors by symbol name, as the 7.3 table lists them.
        val GppMaybe: ImageVector @Composable get() = ClineIcons.GppMaybe
        val GppMaybeFilled: ImageVector @Composable get() = ClineIcons.GppMaybeFilled
        val ContactSupport: ImageVector @Composable get() = ClineIcons.ContactSupport
        val ContactSupportFilled: ImageVector @Composable get() = ClineIcons.ContactSupportFilled
        val PendingFilled: ImageVector @Composable get() = ClineIcons.PendingFilled
        val CheckCircle: ImageVector @Composable get() = ClineIcons.CheckCircle
        val CheckCircleFilled: ImageVector @Composable get() = ClineIcons.CheckCircleFilled
        val Check: ImageVector @Composable get() = ClineIcons.Check
        val Cancel: ImageVector @Composable get() = ClineIcons.Cancel
        val CancelFilled: ImageVector @Composable get() = ClineIcons.CancelFilled
        val Error: ImageVector @Composable get() = ClineIcons.Error
        val ErrorFilled: ImageVector @Composable get() = ClineIcons.ErrorFilled
        val Schedule: ImageVector @Composable get() = ClineIcons.Schedule
        val ScheduleFilled: ImageVector @Composable get() = ClineIcons.ScheduleFilled
        val PushPin: ImageVector @Composable get() = ClineIcons.PushPin
        val PushPinFilled: ImageVector @Composable get() = ClineIcons.PushPinFilled
        val Terminal: ImageVector @Composable get() = ClineIcons.Terminal
        val AppLogo: ImageVector @Composable get() = ClineIcons.AppLogo
    }

    /** Section 7.4 chrome and actions, plus the 5.2 navigation destinations. */
    object Chrome {
        val Send: ImageVector @Composable get() = ArrowUpward
        val Stop: ImageVector @Composable get() = ClineIcons.Stop
        val Attach: ImageVector @Composable get() = AttachFile
        val Model: ImageVector @Composable get() = Memory
        val Reasoning: ImageVector @Composable get() = Neurology
        val Copy: ImageVector @Composable get() = ContentCopy
        val Copied: ImageVector @Composable get() = Check
        val EditMessage: ImageVector @Composable get() = Edit
        val RestoreCheckpoint: ImageVector @Composable get() = Undo
        /** The whole-session fork of the chat's three-dot and the drawer's row menu (the only fork, A16). */
        val Fork: ImageVector @Composable get() = ForkRight
        val NewSession: ImageVector @Composable get() = Add
        val NewSessionRail: ImageVector @Composable get() = EditSquare
        val SessionActions: ImageVector @Composable get() = MoreVert
        val Delete: ImageVector @Composable get() = ClineIcons.Delete
        val Search: ImageVector @Composable get() = ClineIcons.Search
        val Filter: ImageVector @Composable get() = FilterList
        val Sort: ImageVector @Composable get() = ClineIcons.Sort
        val Agents: ImageVector @Composable get() = SmartToy
        val Customize: ImageVector @Composable get() = Extension
        val CustomizeSelected: ImageVector @Composable get() = ExtensionFilled
        val Settings: ImageVector @Composable get() = ClineIcons.Settings
        val SettingsSelected: ImageVector @Composable get() = SettingsFilled
        val Account: ImageVector @Composable get() = AccountCircle
        val Providers: ImageVector @Composable get() = Power
        val Mcp: ImageVector @Composable get() = DeployedCode
        val Marketplace: ImageVector @Composable get() = Storefront
        val Schedules: ImageVector @Composable get() = Schedule
        val SchedulesSelected: ImageVector @Composable get() = ScheduleFilled
        val Notifications: ImageVector @Composable get() = ClineIcons.Notifications
        val Appearance: ImageVector @Composable get() = Palette
        val About: ImageVector @Composable get() = Info
        val WorkspaceFolder: ImageVector @Composable get() = Folder
        val WorkspaceFolderOpen: ImageVector @Composable get() = FolderOpen
        val SetupNotice: ImageVector @Composable get() = Cable
        /** Rotated for expand / collapse. */
        val Expand: ImageVector @Composable get() = ExpandMore
        val ScrollToLatest: ImageVector @Composable get() = ArrowDownward
        val SteerQueuedPrompt: ImageVector @Composable get() = SubdirectoryArrowLeft
        val Close: ImageVector @Composable get() = ClineIcons.Close
        val Back: ImageVector @Composable get() = ArrowBack
        val ListPaneOpen: ImageVector @Composable get() = LeftPanelOpen
        val ListPaneClose: ImageVector @Composable get() = LeftPanelClose
        val Branch: ImageVector @Composable get() = CallSplit
        /** G3: Home's "Worktree" chip (`call_split` is already the branch chip's). */
        val Worktree: ImageVector @Composable get() = ClineIcons.AccountTree
        val Share: ImageVector @Composable get() = ClineIcons.Share
        val Menu: ImageVector @Composable get() = ClineIcons.Menu
        val Chats: ImageVector @Composable get() = ChatBubble
        val ChatsSelected: ImageVector @Composable get() = ChatBubbleFilled

        // The same vectors by symbol name, as the 7.4 and 5.2 tables list them.
        val ArrowUpward: ImageVector @Composable get() = ClineIcons.ArrowUpward
        val AttachFile: ImageVector @Composable get() = ClineIcons.AttachFile
        val Memory: ImageVector @Composable get() = ClineIcons.Memory
        val Neurology: ImageVector @Composable get() = ClineIcons.Neurology
        val ContentCopy: ImageVector @Composable get() = ClineIcons.ContentCopy
        val Check: ImageVector @Composable get() = ClineIcons.Check
        val Edit: ImageVector @Composable get() = ClineIcons.Edit
        val Undo: ImageVector @Composable get() = ClineIcons.Undo
        val ForkRight: ImageVector @Composable get() = ClineIcons.ForkRight
        val Add: ImageVector @Composable get() = ClineIcons.Add
        val EditSquare: ImageVector @Composable get() = ClineIcons.EditSquare
        val MoreVert: ImageVector @Composable get() = ClineIcons.MoreVert
        val FilterList: ImageVector @Composable get() = ClineIcons.FilterList
        val SmartToy: ImageVector @Composable get() = ClineIcons.SmartToy
        val Extension: ImageVector @Composable get() = ClineIcons.Extension
        val ExtensionFilled: ImageVector @Composable get() = ClineIcons.ExtensionFilled
        val SettingsFilled: ImageVector @Composable get() = ClineIcons.SettingsFilled
        val AccountCircle: ImageVector @Composable get() = ClineIcons.AccountCircle
        val Power: ImageVector @Composable get() = ClineIcons.Power
        val DeployedCode: ImageVector @Composable get() = ClineIcons.DeployedCode
        val Storefront: ImageVector @Composable get() = ClineIcons.Storefront
        val Schedule: ImageVector @Composable get() = ClineIcons.Schedule
        val ScheduleFilled: ImageVector @Composable get() = ClineIcons.ScheduleFilled
        val Palette: ImageVector @Composable get() = ClineIcons.Palette
        val Info: ImageVector @Composable get() = ClineIcons.Info
        val Folder: ImageVector @Composable get() = ClineIcons.Folder
        val FolderOpen: ImageVector @Composable get() = ClineIcons.FolderOpen
        val Cable: ImageVector @Composable get() = ClineIcons.Cable
        val ExpandMore: ImageVector @Composable get() = ClineIcons.ExpandMore
        val ArrowDownward: ImageVector @Composable get() = ClineIcons.ArrowDownward
        val SubdirectoryArrowLeft: ImageVector @Composable get() = ClineIcons.SubdirectoryArrowLeft
        val ArrowBack: ImageVector @Composable get() = ClineIcons.ArrowBack
        val LeftPanelOpen: ImageVector @Composable get() = ClineIcons.LeftPanelOpen
        val LeftPanelClose: ImageVector @Composable get() = ClineIcons.LeftPanelClose
        val CallSplit: ImageVector @Composable get() = ClineIcons.CallSplit
        val ChatBubble: ImageVector @Composable get() = ClineIcons.ChatBubble
        val ChatBubbleFilled: ImageVector @Composable get() = ClineIcons.ChatBubbleFilled
    }

    /** Drawable for a [ToolSymbol] chosen by [getToolNameIcon]. */
    @DrawableRes
    fun drawableOf(symbol: ToolSymbol): Int = when (symbol) {
        ToolSymbol.FileCopy -> R.drawable.ms_file_copy
        ToolSymbol.Edit -> R.drawable.ms_edit
        ToolSymbol.Terminal -> R.drawable.ms_terminal
        ToolSymbol.ManageSearch -> R.drawable.ms_manage_search
        ToolSymbol.Web -> R.drawable.ms_web
        ToolSymbol.Person -> R.drawable.ms_person
        ToolSymbol.Group -> R.drawable.ms_group
        ToolSymbol.LibraryBooks -> R.drawable.ms_library_books
        ToolSymbol.DeployedCode -> R.drawable.ms_deployed_code
        ToolSymbol.ContactSupport -> R.drawable.ms_contact_support
        ToolSymbol.Build -> R.drawable.ms_build
        ToolSymbol.Extension -> R.drawable.ms_extension
        ToolSymbol.ExitToApp -> R.drawable.ms_exit_to_app
    }

    /** The kind icon for a tool row (`getToolNameIcon(toolName)` in the desktop). */
    @Composable
    fun toolIcon(toolName: String): ImageVector = vector(drawableOf(getToolNameIcon(toolName)))
}
