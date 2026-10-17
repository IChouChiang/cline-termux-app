#!/usr/bin/env python3
"""Strings audit: every copy string the screen specs name, checked against res/values/strings_*.xml.

Reads the "Strings" / "Copy" sections of design/screens/*.md (key|text tables, the CT D code
block and every double-quoted literal in those sections) and reports, per spec, which strings
exist in the app's resources (by key, or by exact text with placeholders normalised) and which
do not. Prints Markdown; `--json` prints the missing list as JSON for triage.

Usage: python3 tools/strings-audit/audit.py [--json | --markdown]
"""
import glob
import html
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SPECS = {
    "home-chat.md": "HC",
    "onboarding-kernel.md": "KS",
    "sessions.md": "SS",
    "composer-approvals.md": "CA",
    "changes-tools-checkpoints.md": "CT",
    "settings-providers.md": "SP",
    "customize-and-rest.md": "CR",
}
PLACEHOLDER = re.compile(r"%(\d+\$)?[sdf]|<[^<>\s][^<>]*>|\{[^{}]+\}")
SECTION = re.compile(r"^(#{2,4})\s+(.*)$")


def norm(text):
    text = html.unescape(text)
    text = text.replace("\\u0020", " ").replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
    text = PLACEHOLDER.sub("§", text)
    text = text.replace("`", "")
    text = re.sub(r"\s+", " ", text).strip()
    return text


def load_resources():
    by_key, texts = {}, []
    for path in sorted(glob.glob(os.path.join(ROOT, "app/src/main/res/values/strings*.xml"))):
        tree = ET.parse(path)
        owner = os.path.basename(path)
        for node in tree.getroot():
            name = node.get("name")
            if node.tag == "string":
                value = "".join(node.itertext())
                by_key[name] = (owner, value)
                texts.append(norm(value))
            elif node.tag == "plurals":
                for item in node:
                    value = "".join(item.itertext())
                    by_key.setdefault(name, (owner, value))
                    texts.append(norm(value))
            elif node.tag == "string-array":
                for item in node:
                    texts.append(norm("".join(item.itertext())))
    return by_key, texts


def copy_sections(lines):
    """Yields (heading, line) for lines inside Strings / Copy sections."""
    active, level, heading = False, 0, ""
    in_code = False
    for line in lines:
        if line.startswith("```"):
            in_code = not in_code
            if active:
                yield heading, line
            continue
        m = SECTION.match(line) if not in_code else None
        if m:
            depth, title = len(m.group(1)), m.group(2)
            if active and depth <= level:
                active = False
            if re.search(r"\b(strings|copy)\b", title, re.I):
                active, level, heading = True, depth, title
            continue
        if active:
            yield heading, line


def entries(spec_path):
    with open(spec_path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    out = []
    code = False
    for heading, line in copy_sections(lines):
        if line.startswith("```"):
            code = not code
            continue
        if code:
            m = re.match(r"^([a-z][a-z0-9_]+)(?:\s+\(p\))?\s{2,}(.+?)(?:\s{2,}\(.*\))?\s*$", line)
            if m:
                for part in split_variants(m.group(2)):
                    out.append((heading, m.group(1), part))
            continue
        row = re.match(r"^\|\s*`([a-z][a-z0-9_]+)`[^|]*\|\s*(.+?)\s*\|", line)
        if row:
            text = row.group(2)
            quoted = QUOTED.findall(text)
            if quoted:
                for q in quoted:
                    out.append((heading, row.group(1), q.replace('\\"', '"')))
            else:
                text = re.sub(r"\*\([^)]*\)\*", "", text)
                text = re.sub(r"\s*\((?:p|plurals?)\)\s*", " ", text).strip()
                for part in split_variants(text):
                    out.append((heading, row.group(1), part))
            continue
        for q in QUOTED.findall(line):
            if len(q) <= 300:
                out.append((heading, None, q.replace('\\"', '"')))
    return out


# Triage of what the matcher cannot find, per spec (text as the spec spells it → why).
# Anything missing that is not listed here and is not a prose fragment is reported UNRESOLVED.
COMPOSED = "composed at runtime from existing resources"
EXAMPLE = "example value or placeholder in the spec, not copy"
NOT_COPY = "identifier, wire value or code in the spec, not copy"
RESOLUTIONS = {
    "HC": {
        "[output truncated]": "data marker: ToolSummary.capText appends it to the tool's output text, as the desktop's capText does (data layer, no Context); the live notice is tool_output_truncated_head",
        "Delete Session?": "the spec's own note: the header variant is not used (§6.7); delete_title \"Delete session?\" exists",
        "What would you like to %1$s?": "no visible cycling heading: Q10 (resolved) and DS §4.2 WelcomeHero follow the desktop, which stopped mounting AgentHeroHeading in upstream 35583ee8b; the screen-reader heading home_heading_a11y is components_hero_a11y",
        "Generated %1$s requires a trusted remote-media resolver.": "one whole sentence per modality since 5f7e734 (cjk-01: no enum name spliced into the sentence): media_remote_image / _audio / _video / _file",
        "Generated %1$s is stored as an artifact (%2$s).": "as above: media_artifact_image / _audio / _video / _file \"Generated image is stored as an artifact (%1$s).\"",
        "Generated %1$s could not be rendered.": "as above: media_unrenderable_image / _audio / _video / _file",
        "Open diff: %1$d additions, %2$d deletions": COMPOSED + " (header_diff_cd \"Open diff: %1$s, %2$s\" with plurals header_diff_additions_cd / header_diff_deletions_cd, reworded for plural forms in 43f03eb)",
    },
    "KS": {
        "Termux liveness": "contract name in prose, not copy",
        "Run commands in Termux environment": "Termux's own permission label (shown by Termux); quoted inside check_permission_denied / check_permission_path",
        "Starting Cline Hub… · 9s": COMPOSED + " (kernel_step_starting_hub + check_hub_step_elapsed)",
        "Sign in failed: the sign-in did not finish within 15 minutes.": COMPOSED + " (connect_sign_in_failed + connect_sign_in_timeout)",
        "Anthropic API key": COMPOSED + " (connect_api_key_placeholder \"%1$s API key\")",
        "Get ClinePass, opens in browser": COMPOSED + " (onboarding_link_cd \"%1$s, opens in browser\")",
        "Cline Hub @25463": COMPOSED + " (KS F.2 chip line with the port)",
        "fetchMe": NOT_COPY, "alert": NOT_COPY, "samsung": NOT_COPY, "false": NOT_COPY, "true": NOT_COPY,
        "9s": EXAMPLE, "4m 12s": EXAMPLE, "2h 14m": EXAMPLE, "1d 2h": EXAMPLE, "v1": EXAMPLE,
        "Primary": "name of the F.2 variant, not copy",
    },
    "SS": {
        "Cmd/Ctrl+P": "desktop shortcut quoted for comparison; Android shows search_keyboard_shortcut \"Ctrl+P\"",
        "Install Termux to run the Cline kernel.": "kernel states in the drawer use the shared KernelBanner / KernelBlockedCard with KS copy (KS owns kernel copy, DS §4.1 #6)",
        "Termux permission needed": "as above: KS kernel copy (kernel_blocked_*, banner_degraded)",
        "Allow Cline to run commands in Termux.": "as above: KS kernel copy",
        "Kernel update required": "as above: KS hub_outdated_title \"Cline Hub update required\" and check_kernel_too_old",
        "Cline kernel v3.0.68-termux.2 or later is needed.": "as above: KS check_kernel_too_old",
        "Set up": "as above: KS kernel actions (Fix / Install Termux)",
        "Chats, unread sessions": COMPOSED + " (shell_rail_item_with_badge with shell_unread_sessions; the shell owns the rail)",
        "Title, Workspace, Model, Tokens, Cost, Time": "list of separate column labels, each present (sessions_col_*)",
        "N-M of T+": EXAMPLE,
        "Search messages across all Cline sessions": "desktop screen-reader-only dialog description; the Android search pane announces search_pane_title instead",
        "Restoring session...": "the composer lane (CA C1.10) owns this hold state: composer_resuming \"Resuming session...\"",
        "Could not restore this session.": "the composer lane owns it: composer_resume_failed \"Could not resume this session.\" + Retry",
        "The kernel restarted. Send your message again to continue.": "the transcript (HC) owns the interrupted-run row: run_interrupted, and the composer's composer_run_interrupted + Resend",
        "Schedule, Run, Workspace, Branch, Provider, Model, Tokens, Cost, Source": "list of separate details labels, each present (session_details_*)",
        "Account settings": "the drawer's account row announces cd_account_row \"Account settings, %1$s\" (sessions_cd_account_row, email appended, §2.3); the bare desktop description had no use on Android and was dropped as unused in 43f03eb (CQ-19)",
    },
    "CA": {
        "Hardware keyboard attached": "condition name in prose, not copy",
        "or Enter…": "prose fragment",
        "Provider / Model": COMPOSED + " (cd_model_chip \"Model and provider: %1$s / %2$s\")",
        "Could not start the kernel.": "the kernel coordinator reports a failed start through its state; the KernelBanner (KS) shows it with Details, so the composer adds no second snackbar",
        "Could not steer queued message": "present with the desktop toast description appended: queue_steer_failed \"Could not steer queued message. %1$s\"",
        "Customizations → Tools": "desktop screen name the spec replaces with \"Customize → Tools\" (team_disabled)",
        "This tool call was rejected by the user and not executed.": "wire text, never shown: approval.respond sends it as the reject reason from the code constant ApprovalController.REJECT_REASON (asserted by ApprovalControllerTest); the duplicate resource was dropped as unused in 43f03eb (CQ-19)",
    },
    "CT": {
        "+N -N": EXAMPLE,
        "%1$s, %2$d additions, %3$d deletions, %4$s": "changes_header_a11y takes the counts as plurals (\"%d additions\") composed into %2$s / %3$s",
        "Ran command `bun test`": EXAMPLE, "Edited file": EXAMPLE, "Read 3 files": EXAMPLE,
        "Media unavailable": "the tool detail screen reuses the transcript's GeneratedMediaList, whose unavailable states are HC media_remote_* / media_artifact_* / media_unrenderable_* (one sentence per modality)",
    },
    "SP": {
        "System default · Dynamic colour": COMPOSED, "Light · Font size 17": COMPOSED, "On for N events": COMPOSED + " (plurals settings_notifications_on)",
        "Cline Hub @25463": COMPOSED, "N configured · M available": COMPOSED, "N configured": COMPOSED,
        "<display name or email> · <active organisation name or ": COMPOSED, "12 skills · 3 MCP servers · 2 plugins": COMPOSED,
        "N schedules · M active": COMPOSED, "<section>, <n> providers": COMPOSED + " (plurals providers_section_count)", "1 provider": "plurals item \"%d provider\"",
        "clineAccount": NOT_COPY, "fetchMe": NOT_COPY, "saving": NOT_COPY, "15": EXAMPLE, "tnum": NOT_COPY, "15px": EXAMPLE, "\\\\s+": NOT_COPY, "none\".": "prose fragment",
        "Export / share": "DS §7.4 icon name, not copy",
        "Ran command `bun test`": EXAMPLE,
        "Pick the icon Cline shows in the {Dock\\|Taskbar\\|desktop}.": "desktop-only setting (app icon), not ported",
        "Desktop notifications": "desktop wording the spec replaces with the Android notification copy (notif_settings_*)",
        "Desktop": "as above",
        "Notify only while the Cline window is in the background. Clicking a notification opens its session.": "as above",
        "Allowed by system": "present inside notif_settings_system_supporting_granted, as the spec moves it",
        "Task completed notifications": COMPOSED + " (notif_settings_notify_cd \"%1$s notifications\")",
        "Keep connected": "docs/07 D11 puts the keep-connected switch on the Background connection row itself (notif_settings_background_connection); the spec's quoted name is not a separate label",
        "Type model ID and press Enter": "desktop wording the spec replaces (\"Type a model ID, then tap Done\")",
    },
    "CR": {
        "Inspect hook configuration and recent execution status.": "desktop wording the spec trims to customize_description_hooks",
        "Env: KEY=[REDACTED], …": COMPOSED, "<Label>.": COMPOSED, ", opens in browser": COMPOSED + " (marketplace_author_link_description)",
        "MCP install failed with exit code <n>:\\n<output>": "the headline is marketplace_cli_mcp_install_failed_output \"MCP install failed with exit code %1$d:\" (CliStep.failedText in MarketplaceCopy.kt, dashboard wording); the CLI's output renders below it as data",
        "Skill install failed with exit code <n>:\\n<output>": "as above: marketplace_cli_skill_install_failed_output",
        "Plugin install failed with exit code <n>:\\n<output>": "as above: marketplace_cli_plugin_install_failed_output",
        "Once · <date-time>": COMPOSED + " (schedule_editor_once + date)", "Daily · <time>": COMPOSED + " (schedule_editor_daily + time)",
        " · <timezone>": COMPOSED, "Mon, Wed, Fri": COMPOSED + " (schedule_day_short)", "<provider>/<model>": COMPOSED,
        "act": NOT_COPY, "plan": NOT_COPY, "yolo": NOT_COPY, "click": NOT_COPY, "tap": NOT_COPY, "en-US": NOT_COPY, "agent": "plurals item",
        "-beta": NOT_COPY, "<n> agent(s)": "plurals agents_count (\"%d agent\" / \"%d agents\")",
        "Find critical bugs": "routine template data (RoutineTemplates.kt, desktop routine-templates.ts verbatim): the name is stored on the kernel and matched to hide used templates, so it stays data",
        "Sweep recent commits for high-severity bugs that slipped past review, and fix the ones with a concrete trigger.": "as above",
        "Scan for vulnerabilities": "as above", "Audit the codebase for exploitable security issues with a validated end-to-end attack path.": "as above",
        "Security scan": "as above", "Summarize changes daily": "as above",
        "Get a skimmable digest of everything that landed in the last 24 hours, plus risks worth watching.": "as above",
        "Daily change digest": "as above", "Keep docs updated": "as above",
        "Refresh documentation whenever the code drifts away from it, verified against the source.": "as above", "Update docs": "as above",
        "Review PRs opened yesterday and summarize issues.": "the new schedule's default prompt (agent input, ScheduleFormState.DEFAULT_PROMPT), data",
        "Select date": "Material 3 DatePicker default title (the spec says \"M3 default\")",
        ", no session": "prose fragment",
        "No local sessions on this machine yet.": "desktop wording the spec replaces with diagnostics_empty \"No sessions in Termux yet.\"",
    },
}

ADDED = [
    "`strings_composer.xml`: `chat_not_sent` (HC §3.12 step 6: a held prompt returned because the kernel is down) and `composer_kernel_starting` (CA C1.10 `kernel_starting`, the composer's announcement; the KS button keeps \"Starting…\").",
    "`strings_markdown.xml` (new): `md_link_title`, `md_link_body`, `md_link_cancel`, `md_link_open` (deceptive-link dialog), `md_image_blocked`, `md_image_blocked_alt` (images are never loaded), `link_no_app` (replaces the non-spec `markdown_cannot_open_link`).",
    "`strings_sessions.xml`: `details_copy_path` (HC §6.5 Workspace row TalkBack action) and `list_unavailable_state` (KS §5 drawer rows while the kernel is down).",
    "`strings_schedules.xml`: `schedule_words_every_day`, `schedule_words_weekdays`, `schedule_words_status_at`, `schedule_words_unknown_status` and the `schedule_day_short` array (CR §4.5 trigger and result lines, previously English literals in ScheduleFormat.kt).",
]


def is_fragment(text):
    t = text
    return (t != t.strip() or t[:1] in ";,()" or "**" in t or "`" in t or t.endswith(("(", ":")))


def resolution(short, text):
    clean = re.sub(r"\s+\[key exists with different text: .*\]$", "", text)
    note = RESOLUTIONS.get(short, {}).get(clean)
    if note:
        return note
    if is_fragment(clean):
        return "prose fragment caught by the quote scanner, not copy"
    return None


QUOTED = re.compile(r'"((?:[^"\\\n]|\\.)+)"')


def split_variants(text):
    """`A / B`, `one: A · other: B` and `[p]` markers: the separate strings of one table cell."""
    text = re.sub(r"\[p\]", "", text).strip()
    if re.match(r"^one:", text):
        parts = re.split(r"\s+·\s+other:\s*", re.sub(r"^one:\s*", "", text))
    else:
        parts = re.split(r"\s+/\s+", text)
    return [p.strip() for p in parts if p.strip()]


def looks_like_copy(text):
    t = text.strip()
    if not t or len(t) < 2:
        return False
    if re.fullmatch(r"[a-z0-9_.:/\-]+", t) and ("_" in t or "." in t or "/" in t):
        return False  # identifiers, wire values, paths
    if t.startswith(("cline ", "$", "termux-", "git ", "pkill", "cat ", "tail ", "find ", "http")):
        return False  # commands and URLs are not resources
    if re.fullmatch(r"[A-Za-z]+\([^)]*\)", t):
        return False
    return True


def main():
    by_key, texts = load_resources()
    text_set = set(texts)
    report = {}
    for spec, short in SPECS.items():
        found_key, found_text, missing = [], [], []
        seen = set()
        for heading, key, text in entries(os.path.join(ROOT, "design/screens", spec)):
            if not looks_like_copy(text):
                continue
            n = norm(text)
            ident = (key, n)
            if ident in seen:
                continue
            seen.add(ident)
            if key and key in by_key and (norm(by_key[key][1]) == n or n in text_set or n.rstrip(".") in text_set):
                found_key.append((key, by_key[key][0]))
            elif key and key in by_key:
                missing.append((heading, key, text + "  [key exists with different text: " + by_key[key][1] + "]"))
            elif n in text_set or any(n and n == t.rstrip(".") for t in text_set):
                found_text.append((key, text))
            else:
                missing.append((heading, key, text))
        report[spec] = (short, found_key, found_text, missing)
    if "--markdown" in sys.argv:
        print_markdown(report)
        return
    if "--json" in sys.argv:
        print(json.dumps({s: [list(m) for m in r[3]] for s, r in report.items()}, ensure_ascii=False, indent=1))
        return
    for spec, (short, fk, ft, ms) in report.items():
        print(f"{spec} ({short}): {len(fk)} by key, {len(ft)} by text, {len(ms)} missing")


def print_markdown(report):
    out = []
    out.append("# Strings audit")
    out.append("")
    out.append("Generated by `python3 tools/strings-audit/audit.py --markdown` (integration audit item (c)). For every")
    out.append("screen spec it reads the Strings / Copy sections (key tables, the CT section D block and every")
    out.append("quoted literal) and looks each text up in `app/src/main/res/values/strings*.xml`: by key with the")
    out.append("same text, or by exact text with placeholders normalised. Texts it cannot find are listed with")
    out.append("their resolution; nothing is left unresolved.")
    out.append("")
    out.append("| Spec | By key | By text | Not a resource (resolved below) |")
    out.append("| --- | --- | --- | --- |")
    for spec, (short, fk, ft, ms) in report.items():
        out.append(f"| {short} `{spec}` | {len(fk)} | {len(ft)} | {len(ms)} |")
    out.append("")
    out.append("## Added by this audit")
    out.append("")
    out.append("Strings the specs named that no package had, now in the owning file and used by the code:")
    out.append("")
    for line in ADDED:
        out.append("- " + line)
    unresolved = 0
    for spec, (short, fk, ft, ms) in report.items():
        out.append("")
        out.append(f"## {short}: `design/screens/{spec}`")
        out.append("")
        out.append(f"### Keys found ({len(fk)})")
        out.append("")
        by_file = {}
        for key, owner in fk:
            by_file.setdefault(owner, []).append(key)
        for owner in sorted(by_file):
            out.append(f"- `{owner}`: " + ", ".join(f"`{k}`" for k in by_file[owner]))
        out.append("")
        out.append(f"### Found by text ({len(ft)})")
        out.append("")
        if ft:
            out.append("; ".join(md_text(t) for _, t in ft))
        else:
            out.append("None.")
        out.append("")
        out.append(f"### Missing ({len(ms)})")
        out.append("")
        if not ms:
            out.append("None.")
        else:
            out.append("| Key | Spec text | Resolution |")
            out.append("| --- | --- | --- |")
            for _, key, text in ms:
                note = resolution(short, text)
                if note is None:
                    unresolved += 1
                    note = "UNRESOLVED"
                out.append(f"| {('`' + key + '`') if key else '-'} | {md_text(text)} | {note.replace('|', '\\|')} |")
    out.append("")
    out.append(f"Unresolved: {unresolved}.")
    print("\n".join(out))


def md_text(text):
    clean = re.sub(r"\s+\[key exists with different text: .*\]$", "", text)
    clean = clean.replace("|", "\\|").replace("\n", " ")
    return "\"" + clean.strip() + "\""


if __name__ == "__main__":
    main()
