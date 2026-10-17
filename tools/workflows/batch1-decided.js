export const meta = {
  name: 'batch1-decided',
  description: 'Build, integrate, verify on the S25U and close out every item decided in docs/decided.md on 2026-10-07 (Opus workers; Fable only reads results)',
  phases: [
    { title: 'Design', detail: 'one brief per work package, verification plan' },
    { title: 'Implement', detail: 'implementer -> reviewer -> fixer per package, in worktrees' },
    { title: 'Integrate', detail: 'merge all packages, full gate, release build' },
    { title: 'Verify', detail: 'device rounds on the S25U, one agent at a time, fixers between rounds' },
    { title: 'Closeout', detail: 'docs, report, installs, test-session cleanup' },
  ],
}

const APP = '~/workspace/cline-termux-app'
const FORK = '~/workspace/cline-termux-v3-phone'
const OPUS = { model: 'opus' }

const RULES = `
STANDING RULES (every agent):
- App repo: ${APP} (Kotlin, Jetpack Compose, Material 3). Kernel/desktop source for reference: ${FORK} (read-only; never modify it).
- Read ${APP}/CLAUDE.md first; then docs/decided.md (the decisions you implement), docs/todo.md (trial log), docs/research/auto-approve-control.md and docs/research/terminal-only-functions.md.
- Build ONLY through "bash scripts/build.sh <gradle tasks>". The gate for a package is: assembleDebug testDebugUnitTest lintDebug, all green, lint 0 errors (HardcodedText is an error). Never weaken lint or tests to pass.
- Commit on your branch with a clear message; end every commit message with the line "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>". Never push. Never touch main directly.
- Parity rule P1 (docs/decided.md): desktop functions exact where the kernel or other clients can see it; Material 3 phone presentation; additions only as decided. When the decision text and your reading of the desktop source disagree, follow the decision and say so in your report.
- No secrets in code, logs or reports; never print Hub tokens or credentials.
- Deliver complete work, not WIP. Report honestly: what you verified, what you could not.
- Device: S25U, adb serial $SERIAL, adb at ~/Android/Sdk/platform-tools/adb; ssh alias <phone> (Termux login shell is zsh; use "bash -s" for scripts). The debug build now uses the application id dev.cline.termux.debug (package 8 adds the suffix); the owner's own app stays installed as dev.cline.termux and must never be reinstalled, launched or cleared by test agents. Termux's shared-storage permission is deliberately revoked for the test window: /sdcard and ~/storage are inaccessible from Termux; do not grant it back and do not "fix" the app's storage checks because of it.
- Dev bench: "bash scripts/dev-hub.sh start|stop|status|reverse|helper" (mock provider :25499, Hub :25498 under /tmp/cline-dev-hub; markers hello-from-mock and mock-long-output; run_commands fails with ENOENT in sessions the app creates, so bench sessions use the bench workspace). The phone reaches the bench Hub through "adb reverse tcp:25498 tcp:25498".
- Real kernel on the phone: Hub on ws://127.0.0.1:25463, the owner's real ~/.cline. Allowed only for checks that need a real model: prefer the DeepSeek provider (fast, cheap, limited quota: few, short prompts), use the owner's Astra (openai-codex) only for Astra-specific checks (the thinking chip). Never use Minimax (dead). Every real session title starts with "[test] "; create them in the scratch workspace /data/data/com.termux/files/home/tmp/app-test-ws; prompts are benign and read-only; never ask a model to modify files outside that workspace; delete the [test] sessions when your round ends. Never run the owner's flows that write to the real Termux config (Connect a model, provider keys, sign-in, schedules, MCP edits) against the real kernel.
- Phone hygiene: one agent uses the phone at a time; leave it unlocked and plugged in; do not reboot it; do not change system settings beyond what a test needs and restore what you changed.
`

const DESIGN_SCHEMA = {
  type: 'object',
  properties: {
    packages: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          decisions: { type: 'array', items: { type: 'string' } },
          brief: { type: 'string' },
          files: { type: 'array', items: { type: 'string' } },
          tests: { type: 'array', items: { type: 'string' } },
          benchAdditions: { type: 'array', items: { type: 'string' } },
          dependsOn: { type: 'array', items: { type: 'string' } },
        },
        required: ['id', 'title', 'decisions', 'brief', 'files', 'tests', 'dependsOn'],
      },
    },
    verification: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          packageId: { type: 'string' },
          steps: { type: 'string' },
          needsRealModel: { type: 'boolean' },
          expected: { type: 'string' },
        },
        required: ['id', 'packageId', 'steps', 'needsRealModel', 'expected'],
      },
    },
    notes: { type: 'string' },
  },
  required: ['packages', 'verification'],
}

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    commit: { type: 'string' },
    summary: { type: 'string' },
    filesTouched: { type: 'array', items: { type: 'string' } },
    tests: { type: 'array', items: { type: 'string' } },
    gatePassed: { type: 'boolean' },
    limitations: { type: 'array', items: { type: 'string' } },
    decisionDeviations: { type: 'array', items: { type: 'string' } },
  },
  required: ['branch', 'commit', 'summary', 'filesTouched', 'tests', 'gatePassed', 'limitations'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    approved: { type: 'boolean' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          file: { type: 'string' },
          summary: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'file', 'summary', 'fix'],
      },
    },
  },
  required: ['approved', 'findings'],
}

const INTEGRATE_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    commit: { type: 'string' },
    mergedPackages: { type: 'array', items: { type: 'string' } },
    droppedPackages: { type: 'array', items: { type: 'string' } },
    gate: { type: 'string' },
    releaseGate: { type: 'string' },
    debugApk: { type: 'string' },
    releaseApk: { type: 'string' },
    notes: { type: 'string' },
  },
  required: ['branch', 'commit', 'mergedPackages', 'droppedPackages', 'gate', 'releaseGate', 'debugApk', 'releaseApk'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    results: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          status: { type: 'string', enum: ['pass', 'fail', 'untestable'] },
          evidence: { type: 'string' },
          finding: { type: 'string' },
          fixHint: { type: 'string' },
        },
        required: ['id', 'status', 'evidence'],
      },
    },
    phoneLeftClean: { type: 'boolean' },
    notes: { type: 'string' },
  },
  required: ['results', 'phoneLeftClean'],
}

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    commit: { type: 'string' },
    fixed: { type: 'array', items: { type: 'string' } },
    notFixed: { type: 'array', items: { type: 'string' } },
    gatePassed: { type: 'boolean' },
    debugApk: { type: 'string' },
  },
  required: ['commit', 'fixed', 'notFixed', 'gatePassed'],
}

const CLOSEOUT_SCHEMA = {
  type: 'object',
  properties: {
    commit: { type: 'string' },
    reportPath: { type: 'string' },
    docsUpdated: { type: 'array', items: { type: 'string' } },
    installed: { type: 'string' },
    testSessionsDeleted: { type: 'boolean' },
    openItems: { type: 'array', items: { type: 'string' } },
  },
  required: ['commit', 'reportPath', 'docsUpdated', 'installed', 'testSessionsDeleted', 'openItems'],
}

const PACKAGE_LIST = `
The eight work packages, fixed by the lead (keep these ids and this scope; refine the briefs):
P1 approvals: A1 (A′ shield before the title on Home and in every chat; Ask/Auto/pending states; two-tap menu; the kernel's global toolAutoApprove read through the existing cat of global-settings.json and written through one new Termux command; missing key = on; Ask sends the terminal's seven read-only auto-approved tool policies (apps/cli/src/runtime/tool-policies.ts); replace the private tools.autoApprove preference everywhere; approval-sheet "Approve" + "▾" with "Approve and auto-approve from now on"; Settings "Auto-approve all" in the Kernel group; the ring/disc motion when the pending state applies at the end of a run or on Stop; re-read the file on resume, run end and session open). Full design: docs/research/auto-approve-control.md.
P2 thinking: A2 option 2 (export provider capabilities from the kernel registry into res/raw/fallback_providers.json via tools/catalog/export-fallback-providers.ts; a reasoning-capable provider's chat models are reasoning-capable; unknown stays disabled; the live catalog overrides; custom models from the kernel's models file honoured through the live catalog).
P3 resilience: A4 option 1 (notice on the first run.heartbeat without any session event: "The kernel has been running for 30 s without responding" with Restart kernel and Keep waiting; Stop failure after the protocol's 30 s instead of ToolCallStore.STOP_TIMEOUT_MS 5 s, offering Restart kernel), G5 (Draining must not survive a connection-generation change; onDraining must not drop a cleared signal during a sequence; re-derive from the new Hub's status), B5 (Reliability step: ask again on the next tap, open system settings only after a permanent denial, like Settings). Bench additions as needed (a stall marker for the mock provider so the heartbeat path is testable; hub.drain on the bench Hub for G5).
P4 session config: G1 (compaction {enabled:true} in every session.create like the desktop sidecar), A11 row 2 (compaction strategy LLM/Truncation/Off in Settings, sessionConfig.compaction + the global compactionStrategy/compactionEnabled read like the terminal), A11.8 ("Move to workspace…" in the session menu: session.create again with the messages in the chosen workspace), A11 row 5 steer (long-press Send while a run is active sends session.send_input {delivery:"steer"}).
P5 contributions: G2 (the mistake-limit prompt: register the Hub's mistakeLimit contribution like the desktop sidecar, answer with "Try a different approach" / "Stop this run" through a sheet), A5 hooks (register hook contributions on session.create; answer capability.requested by running the hook scripts through Termux RUN_COMMAND with the JSON payload on stdin and results from stdout; honour block/allow/modify; docs/02 gains a section), A11 row 4 switch_to_act_mode (client tool contribution over capability.request; after approval the session mode switches to act and the run continues). Bench additions: mock markers that trigger the mistake limit and the act-mode tool; a hook script in the bench workspace.
P6 composer: A11.6 prompt history (a quick horizontal fling on the EMPTY field steps through the last 20 prompts of ~/.cline/data/cache/user_input_history.jsonl (one {prompt} per line, most recent first), slow drags still move the cursor; browsing state while the recalled text is unedited with a chip "Recent prompt n of 20" and an × above the field; typing or clearing ends it; a "Recent prompts" row in the attach sheet opens the same list; the app appends its own sent prompts to that file through Termux so the terminal sees them), B9 full-screen editor (an expand icon at the end of the field once the text passes about three lines opens a Material full-screen dialog with close, the attachments and Send in its top bar; closing returns the text).
P7 transcript: A11.9 (Settings "Message details: Brief / Verbose"; Verbose adds tokens, cost and duration to the revealed row of agent messages and shows recoverable mid-run errors inline), A13 (the message-row fork is labelled "Fork before this turn" with its own icon distinct from the whole-session fork_right glyph: the owner's sketch is the branch arm without the shared stem; choose between the existing ms_call_split and a custom glyph drawn from the sketch, and show both on the S25U in verification).
P8 housekeeping: G3 (worktree toggle for a new task like the desktop's welcome-workspace-controls: creates a git worktree through Termux and starts the session there), G4 (hardware-keyboard Ctrl+, opens Settings and Ctrl+B toggles the sidebar/drawer, matching the desktop page.tsx bindings; fix the Shortcuts.kt comment about Ctrl+N), the debug build gets applicationIdSuffix ".debug" (update every script and doc that names the package, including the acceptance scripts under tools/ and scripts/), and the release build type is signed with the debug keystore for now (a clearly commented temporary signing config; no new keystore), so assembleRelease produces an installable APK.
`

phase('Design')
const design = await agent(`${RULES}
You are the DESIGN lead for this batch. Produce one implementation brief per work package and a verification plan. Read the decisions, the two research notes, the app's architecture docs (docs/07) and kernel contract (docs/02), and the relevant app source, before writing. For every package: the exact files to touch or add, the Hub/Termux interactions, the unit tests to write, bench additions (mock markers, bench hook scripts) the verification needs, and dependencies between packages (which must merge first). Keep each brief concrete enough that an implementer can start without re-reading the research. Keep the package ids and scope exactly as listed.
${PACKAGE_LIST}
Also write the verification plan: one entry per decided item (and per bug: G5, the stuck Draining), with step-by-step device or bench steps an agent can execute with adb and the bench, whether it needs a real model, and the expected observation. Include: the jank measurement (B8.3: release build, real kernel streaming with DeepSeek, dumpsys gfxinfo before/after, report the janky-frame percentage against the 30 % debug baseline), fixture 07 (record the two-step checkpoint restore traffic against the bench with the existing fixture tooling under tools/, see docs/08), and a side-by-side check of the two fork icons. Write the briefs to ${APP}/docs/research/batch1-briefs.md (commit on main is NOT allowed: write the file but do not commit; the integrate agent commits it) and return them structured.`, { ...OPUS, label: 'design', schema: DESIGN_SCHEMA, effort: 'high' })

if (!design) throw new Error('design agent returned nothing')
log(`design: ${design.packages.length} packages, ${design.verification.length} verification items`)

const ORDER = ['P8', 'P2', 'P3', 'P4', 'P1', 'P5', 'P6', 'P7']
const packages = ORDER.map(id => design.packages.find(p => p.id === id)).filter(Boolean)

phase('Implement')
const implemented = await pipeline(
  packages,
  pkg => agent(`${RULES}
You are the IMPLEMENTER of work package ${pkg.id} "${pkg.title}" in your own git worktree of the app repo (already checked out for you; confirm with git status and git branch). Create and switch to a branch named pkg/${pkg.id.toLowerCase()} from the current HEAD.
Brief from the design lead:
${pkg.brief}
Decisions covered: ${pkg.decisions.join('; ')}
Files expected: ${pkg.files.join(', ')}
Tests expected: ${pkg.tests.join('; ')}
Bench additions: ${(pkg.benchAdditions || []).join('; ') || 'none'}
Implement the whole package with unit tests (and Compose UI tests where the repo already has that pattern), then run the gate "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug" until it is green, commit, and return the structured report. If a decision cannot be implemented as written, implement the closest faithful form, and list it under decisionDeviations with the reason. Do not use the phone in this phase (bench-only or unit tests). Strings go to res/values with proper names; no hardcoded text.`, { ...OPUS, label: `impl:${pkg.id}`, phase: 'Implement', schema: IMPL_SCHEMA, isolation: 'worktree' }),
  async (impl, pkg) => {
    if (!impl) return null
    let current = impl
    for (let round = 0; round < 2; round++) {
      const review = await agent(`${RULES}
You are the adversarial REVIEWER of work package ${pkg.id} "${pkg.title}", branch ${current.branch} (commit ${current.commit}) in the app repo. Read the diff against main ("git -C ${APP} diff main...${current.branch}") and the touched files. Check: fidelity to the decisions in docs/decided.md and the brief; Material 3 and the app's existing patterns; Hub protocol correctness against the kernel source in ${FORK}; tests that actually exercise the behaviour; strings and accessibility; nothing that touches the owner's real data unexpectedly; the gate claim. Implementer's report: ${JSON.stringify(current)}. Approve only if there are no blocker or major findings. Be concrete: file, what is wrong, how to fix.`, { ...OPUS, label: `review:${pkg.id}:${round + 1}`, phase: 'Implement', schema: REVIEW_SCHEMA })
      if (!review || review.approved) return { ...current, review }
      const fix = await agent(`${RULES}
You are the FIXER for work package ${pkg.id} on branch ${current.branch} in the app repo. Work in your worktree: run "git -C ${APP} worktree list" and check out the branch ${current.branch} in a new worktree under ${APP}/.claude/worktrees/fix-${pkg.id.toLowerCase()}-${round + 1} (git worktree add), fix every finding below, re-run the gate "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug" until green, commit on the same branch, and return the structured report (branch unchanged, new commit).
Findings: ${JSON.stringify(review.findings)}`, { ...OPUS, label: `fix:${pkg.id}:${round + 1}`, phase: 'Implement', schema: IMPL_SCHEMA })
      if (!fix) return { ...current, review }
      current = { ...fix, branch: current.branch }
    }
    return current
  },
)
const built = implemented.filter(Boolean)
log(`implemented: ${built.map(b => b.branch).join(', ')}`)

phase('Integrate')
const integ = await agent(`${RULES}
You are the INTEGRATOR. In the app repo (${APP}, on main, no worktree), create the branch batch/decided-1 from main and merge these package branches in this order, resolving conflicts faithfully to both packages: ${built.map(b => `${b.branch} (${b.commit})`).join(', ')}. Also add docs/research/batch1-briefs.md (written by the design lead, uncommitted on main) in a docs commit. After the merges run the FULL gate: "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug", then "bash scripts/build.sh assembleRelease lintRelease" and "bash scripts/build.sh testReleaseUnitTest -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false". Fix whatever the merge broke (do not drop a package unless it cannot be made to build; list drops). Report the APK paths (debug: application id dev.cline.termux.debug; release: signed with the debug keystore as package P8 configured). Do not use the phone.
Package reports: ${JSON.stringify(built.map(b => ({ branch: b.branch, commit: b.commit, summary: b.summary, limitations: b.limitations, decisionDeviations: b.decisionDeviations })))}`, { ...OPUS, label: 'integrate', schema: INTEGRATE_SCHEMA, effort: 'high' })
if (!integ) throw new Error('integrate agent returned nothing')
log(`integrated ${integ.mergedPackages.length} packages on ${integ.branch} @ ${integ.commit}; dropped: ${integ.droppedPackages.join(', ') || 'none'}`)

phase('Verify')
const chunk = (arr, n) => arr.reduce((acc, x, i) => (i % n ? acc[acc.length - 1].push(x) : acc.push([x]), acc), [])
let pending = design.verification
let debugApk = integ.debugApk
let headCommit = integ.commit
const allResults = []
for (let round = 0; round < 3 && pending.length; round++) {
  const groups = chunk(pending, 3)
  const roundResults = []
  for (const [gi, group] of groups.entries()) {
    const res = await agent(`${RULES}
You are DEVICE VERIFIER ${gi + 1} of round ${round + 1}. Branch batch/decided-1 at ${headCommit}; debug APK ${debugApk} (application id dev.cline.termux.debug). ${gi === 0 && round === 0 ? 'Install the debug APK on the S25U first ("adb -s $SERIAL install -r <apk>"), start the bench if down ("bash scripts/dev-hub.sh status|start|reverse"), and point the debug app at the bench as docs/09 and CLAUDE.md describe for the developer loop.' : 'The debug APK is already installed and the bench is up; re-check both before starting.'}
Verify exactly these items, in order, one at a time, with adb (screenshots, input, logcat) and the bench or the real kernel as each item says:
${JSON.stringify(group, null, 1)}
Record evidence (what you saw, screenshot paths under ~/.claude/jobs/02338d87/tmp/verify/, log lines). A fail needs a concrete finding and a fix hint. Mark untestable only when the environment truly prevents the check (say why). For real-model items use DeepSeek, [test]-titled sessions in the scratch workspace, and delete them at the end. For the jank item use the release APK ${integ.releaseApk} installed as a SEPARATE step only if P8 gave it an installable signature, and uninstall nothing of the owner's. For fixture 07 use the fixture tooling under tools/ and commit nothing (leave the recorded file in the worktree-free app repo under docs/ or tools/fixtures as docs/08 prescribes; the close-out agent commits it). Leave the phone as you found it (app in a sane state, no stray dialogs), report phoneLeftClean honestly.`, { ...OPUS, label: `verify:r${round + 1}:g${gi + 1}`, phase: 'Verify', schema: VERIFY_SCHEMA })
    if (res) roundResults.push(...res.results.map(r => ({ ...r, round: round + 1 })))
  }
  allResults.push(...roundResults)
  const failed = roundResults.filter(r => r.status === 'fail')
  log(`verify round ${round + 1}: ${roundResults.filter(r => r.status === 'pass').length} pass, ${failed.length} fail, ${roundResults.filter(r => r.status === 'untestable').length} untestable`)
  if (!failed.length) { pending = []; break }
  const fix = await agent(`${RULES}
You are the ROUND-${round + 1} FIXER on branch batch/decided-1 (commit ${headCommit}) in the app repo (work on main's checkout of that branch, no worktree; nobody else edits it now). Fix every failed verification item below at the root cause, with a unit test where one applies, re-run the gate "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug" until green, commit, and return the new commit and the debug APK path. Do not use the phone.
Failures: ${JSON.stringify(failed, null, 1)}`, { ...OPUS, label: `fix:round${round + 1}`, phase: 'Verify', schema: FIX_SCHEMA, effort: 'high' })
  if (!fix) break
  headCommit = fix.commit
  if (fix.debugApk) debugApk = fix.debugApk
  const fixedIds = new Set(fix.fixed)
  pending = design.verification.filter(v => failed.some(f => f.id === v.id && fixedIds.has(f.id)))
  log(`round ${round + 1} fixer: fixed ${fix.fixed.length}, not fixed ${fix.notFixed.length}; re-verifying ${pending.length}`)
}

phase('Closeout')
const closeout = await agent(`${RULES}
You are the CLOSE-OUT agent on branch batch/decided-1 (commit ${headCommit}) in the app repo. Tasks, in order:
1. Update the docs for what was built: docs/02-kernel-contract.md (hooks and contributions, heartbeat notice, compaction field, steer, move-to-workspace), docs/07-app-architecture.md (new decisions D21+ for anything the build had to settle; the approvals model; the .debug suffix and the temporary release signing), docs/08-hub-fixtures.md (fixture 07 if recorded), docs/09-implementation-report.md (a new section "Batch 1 (2026-10-07)" pointing at the report), CLAUDE.md of the app (package id change, bench markers added, release signing note). Commit the recorded fixture if the verifiers left one.
2. Write docs/10-batch1-report.md: what was built per package (with commits), the verification results of every round (pass/fail/untestable with evidence), the jank figures, deviations from decisions, known limitations, and what the owner should try first. Base it on these results: ${JSON.stringify({ built: built.map(b => ({ branch: b.branch, commit: b.commit, summary: b.summary, limitations: b.limitations, decisionDeviations: b.decisionDeviations })), integ, results: allResults })}
3. Update docs/decided.md Build column for every item that is now built and verified ("done"), partially ("in progress" with a note), or not ("pending" with the reason), and add trial-log rows to docs/todo.md only for new defects found and not fixed.
4. Run the full gate once more on the final head (debug: assembleDebug testDebugUnitTest lintDebug; release: assembleRelease lintRelease, testReleaseUnitTest with -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false) and record the result in the report. Commit everything on batch/decided-1.
5. Phone: make sure every [test] session in the owner's real Hub is deleted (use the app's session list on the debug build or the kernel's cline history commands over ssh; never touch other sessions), the bench app points back at the real kernel, and install the debug build (dev.cline.termux.debug) at the final commit. Do NOT install over dev.cline.termux (the owner's app); the lead does that after its own gate.
Return the structured report.`, { ...OPUS, label: 'closeout', schema: CLOSEOUT_SCHEMA, effort: 'high' })

return {
  branch: integ.branch,
  finalCommit: closeout?.commit || headCommit,
  packages: built.map(b => b.branch),
  dropped: integ.droppedPackages,
  verification: allResults,
  closeout,
}
