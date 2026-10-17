/**
 * Deterministic OpenAI-compatible mock provider for recording Cline Hub fixtures.
 *
 * Implements `POST /v1/chat/completions` (streaming SSE only, the way the Cline
 * runtime calls it) and `GET /v1/models`. Behaviour is scripted from the shape of
 * the conversation the runtime sends, so the same prompts always produce the same
 * frames:
 *
 *   1. First user prompt mentioning `hello-from-mock` and no tool result yet
 *      -> short text, then a `run_commands` tool call (`echo hello-from-mock`),
 *         finish_reason "tool_calls".
 *   2. Last message is a tool result
 *      -> if it says the call was rejected: a one-line acknowledgement;
 *         otherwise the final markdown answer (code block, table, list),
 *         finish_reason "stop".
 *   3. A user prompt containing `slow`
 *      -> 40 small text chunks 300 ms apart (used to record run.abort mid-run).
 *   4. Device-acceptance markers in the newest user prompt (app testing, not recorded by
 *      record.ts), each answered once per prompt, then case 2 applies to the tool result:
 *        mock-sleep  -> `run_commands` ["sleep 8; echo hello-from-mock"] (a running row)
 *        mock-edit   -> `editor` on <workspace>/hello.js (a diff row)
 *        mock-read   -> `read_files` <workspace>/README.md
 *        mock-two-reads    -> two parallel `read_files` calls (README.md, hello.js): two approvals
 *        mock-long-command -> `run_commands` with a ~230-character `curl` line
 *        mock-long-output  -> `run_commands` ["seq 1 80"] (80 lines of output, CT B7)
 *        mock-fetch  -> `fetch_web_content` https://example.com
 *        mock-ask    -> `ask_question` with three options
 *        mock-agent  -> `spawn_agent` (the subagent's own prompt gets a plain answer)
 *        mock-think  -> streamed `reasoning_content`, then a short answer
 *        mock-links  -> markdown with a normal link, a deceptive link and an external image
 *        mock-stall  -> the SSE response headers, then no bytes at all for MOCK_STALL_MS
 *                       (env, default 75000), then a short answer: a silent model for the
 *                       app's stall notice (A4) and the 30 s Stop timeout
 *      The workspace is `--workspace` (default /tmp/cline-dev-hub/workspace).
 *      Client-contribution, hook and error markers (mock-mistakes, mock-act-switch, mock-hook-block,
 *      mock-hook-context, mock-api-error; see `benchScript`) steer their whole run and are checked first.
 *   5. Anything else -> a plain streamed text answer.
 *
 * Every request body is appended to the request log (JSONL) so the exact tool
 * schema and message shapes the runtime sends can be inspected afterwards.
 *
 * Usage: bun tools/hub-fixtures/mock-openai.ts [--port 25499] [--log path]
 */

const args = process.argv.slice(2);
function argValue(flag: string, fallback: string): string {
	const index = args.indexOf(flag);
	return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
}

const PORT = Number.parseInt(argValue("--port", "25499"), 10);
const WORKSPACE = argValue("--workspace", "/tmp/cline-dev-hub/workspace");
const LOG_PATH = argValue("--log", `${import.meta.dir}/mock-requests.log.jsonl`);
const MODEL_ID = "mock-1";
/** "mock-stall": how long the stalled response sends nothing after its headers. */
const STALL_MS = Number.parseInt(process.env.MOCK_STALL_MS ?? "75000", 10);

let requestCounter = 0;

type ChatMessage = {
	role: string;
	content?: unknown;
	tool_calls?: unknown[];
	tool_call_id?: string;
};

function textOf(content: unknown): string {
	if (typeof content === "string") return content;
	if (Array.isArray(content)) {
		return content
			.map((part) =>
				part && typeof part === "object" && typeof part.text === "string"
					? part.text
					: "",
			)
			.join("");
	}
	return "";
}

function sanitizeHeaders(headers: Headers): Record<string, string> {
	const out: Record<string, string> = {};
	headers.forEach((value, key) => {
		out[key] = key.toLowerCase() === "authorization" ? "Bearer <redacted>" : value;
	});
	return out;
}

async function appendLog(entry: Record<string, unknown>): Promise<void> {
	const line = `${JSON.stringify(entry)}\n`;
	const file = Bun.file(LOG_PATH);
	const existing = (await file.exists()) ? await file.text() : "";
	await Bun.write(LOG_PATH, existing + line);
}

type ToolScript = {
	name: string;
	args: Record<string, unknown>;
	narration: string;
	/** Further calls in the same assistant message (parallel tool calls). */
	more?: Array<{ name: string; args: Record<string, unknown> }>;
	/** Tool-call id prefix instead of `call_mock_<name>` (a hook reads it from `tool_call.id`). */
	callId?: string;
};

const MARKER_TOOLS: Record<string, () => ToolScript> = {
	"mock-sleep": () => ({
		name: "run_commands",
		args: { commands: ["sleep 8; echo hello-from-mock"] },
		narration: "I'll run the slow command now.",
	}),
	"mock-edit": () => ({
		name: "editor",
		args: {
			path: `${WORKSPACE}/hello.js`,
			old_text: 'console.log("hello from the dev workspace");',
			new_text: 'console.log("hello from the dev workspace");\nconsole.log("edited by the mock");',
		},
		narration: "I'll edit hello.js.",
	}),
	// Before "mock-read": the marker lookup is a substring match in insertion order.
	"mock-two-reads": () => ({
		name: "read_files",
		args: { files: [{ path: `${WORKSPACE}/README.md` }] },
		narration: "I'll read two files.",
		more: [{ name: "read_files", args: { files: [{ path: `${WORKSPACE}/hello.js` }] } }],
	}),
	"mock-long-command": () => ({
		name: "run_commands",
		args: {
			commands: [
				"curl --silent --show-error --fail --location --retry 3 --retry-delay 2 --max-time 30 --header 'Accept: application/json' --header 'User-Agent: cline-mock' https://example.com/api/v1/very/long/path/to/resource?query=hello-from-mock",
			],
		},
		narration: "I'll run a long command.",
	}),
	"mock-long-output": () => ({
		name: "run_commands",
		args: { commands: ["seq 1 80"] },
		narration: "I'll print 80 lines.",
	}),
	"mock-read": () => ({
		name: "read_files",
		args: { files: [{ path: `${WORKSPACE}/README.md` }] },
		narration: "I'll read the README.",
	}),
	"mock-fetch": () => ({
		name: "fetch_web_content",
		args: { requests: [{ url: "https://example.com", prompt: "Summarize the page" }] },
		narration: "I'll fetch the page.",
	}),
	"mock-ask": () => ({
		name: "ask_question",
		args: { question: "Which color should the button be?", options: ["Red", "Green", "Blue"] },
		narration: "I need one decision first.",
	}),
	"mock-agent": () => ({
		name: "spawn_agent",
		args: { systemPrompt: "You are a helper that lists files.", task: "List the files in the workspace." },
		narration: "I'll ask a subagent to list the files.",
	}),
	// Changes / Tool detail / checkpoint acceptance (design/screens/changes-tools-checkpoints.md).
	"mock-file-create": () => ({
		name: "editor",
		args: { path: `${WORKSPACE}/hello.py`, new_text: 'print("hello")\n' },
		narration: "I'll create hello.py.",
	}),
	"mock-file-change": () => ({
		name: "editor",
		args: { path: `${WORKSPACE}/hello.py`, old_text: 'print("hello")', new_text: 'print("hi")' },
		narration: "I'll change hello.py to print hi.",
	}),
	"mock-file-notes": () => ({
		name: "editor",
		args: { path: `${WORKSPACE}/notes.txt`, old_text: "a\nb\nc", new_text: "a\nc" },
		narration: "I'll delete line b from notes.txt.",
	}),
	"mock-file-big": () => ({
		name: "editor",
		args: {
			path: `${WORKSPACE}/big.txt`,
			// The editor refuses new_text over 6,000 characters: one-letter lines keep 2,000 lines under it.
			new_text: Array.from({ length: 2000 }, (_, i) => String.fromCharCode(97 + (i % 26))).join("\n") + "\n",
		},
		narration: "I'll create a 2,000-line file.",
	}),
	"mock-cmd-ansi": () => ({
		name: "run_commands",
		args: { commands: ["printf '\\e[31mred\\e[0m \\e[1;32mbold green\\e[0m \\e[38;5;208morange\\e[0m\\n'"] },
		narration: "I'll print some colours.",
	}),
	"mock-cmd-progress": () => ({
		name: "run_commands",
		args: { commands: ["for i in $(seq 1 20); do printf '\\r%d%%' $((i*5)); sleep 0.3; done; echo"] },
		narration: "I'll run a progress bar.",
	}),
	"mock-cmd-loop": () => ({
		name: "run_commands",
		args: { commands: ["for i in $(seq 1 90); do echo line $i; sleep 1; done"] },
		narration: "I'll run the 90-second loop.",
	}),
	"mock-cmd-huge": () => ({
		name: "run_commands",
		args: { commands: ["seq 1 2000000"] },
		narration: "I'll print two million lines.",
	}),
	"mock-cmd-bounded": () => ({
		name: "run_commands",
		args: { commands: ["for k in $(seq 0 19); do seq $((k*10000+1)) $((k*10000+10000)); sleep 1; done"] },
		narration: "I'll print 200,000 lines in batches.",
	}),
	"mock-cmd-stderr": () => ({
		name: "run_commands",
		args: { commands: ["ls /nonexistent; echo ok"] },
		narration: "I'll list a missing folder.",
	}),
	"mock-cmd-two": () => ({
		name: "run_commands",
		args: { commands: ["echo a", "echo b"] },
		narration: "I'll run these two commands separately.",
	}),
	"mock-cmd-bg": () => ({
		name: "run_commands",
		args: { commands: ["(sleep 30 &) ; echo done"] },
		narration: "I'll start a background process.",
	}),
	"mock-cmd-fail": () => ({
		name: "run_commands",
		args: { commands: ["seq 1 50; exit 3"] },
		narration: "I'll run a failing command.",
	}),
	"mock-cmd-wide": () => ({
		name: "run_commands",
		args: { commands: ["printf 'W%.0s' $(seq 1 300); echo; echo short line"] },
		narration: "I'll print a wide line.",
	}),
};

const THINK_REASONING =
	"The user wants a short answer. I should check what they asked, consider the options, and keep the reply brief. " +
	"There is nothing to run, so a direct answer is enough. Let me also make sure the answer mentions the mock.";

const LINKS_MARKDOWN = [
	"Here are the links you asked for.",
	"",
	"- A normal link: [Cline documentation](https://docs.cline.bot)",
	"- A deceptive link: [https://github.com](https://evil.example/login)",
	"",
	"![Remote diagram](https://example.com/diagram.png)",
	"",
].join("\n");

type Script =
	| { kind: "tool_call" }
	| { kind: "marker_tool"; tool: ToolScript }
	| { kind: "think" }
	| { kind: "links" }
	| { kind: "final_markdown" }
	| { kind: "rejected_ack" }
	| { kind: "slow" }
	| { kind: "plain"; text: string }
	| { kind: "stall" }
	| { kind: "api_error"; status: number };

// ---- client-contribution and hook markers (batch-1 brief §8.5) --------------------------------

/** Core's soft loop notice, the app's mistake-limit guidance and hook context are user-role notes, not prompts. */
const MISTAKE_GUIDANCE = "Do not repeat the same call";
const ACT_CONTINUATION = "The user approved switching to act mode. Continue with the approved plan now.";

function isRuntimeNote(text: string): boolean {
	const t = text.trim();
	return (
		t.startsWith("<hook_context") ||
		t.startsWith("[SYSTEM]") ||
		t.startsWith("The run reached the limit for repeated mistakes or tool calls.") ||
		/^Detected \d+ consecutive identical calls to /.test(t)
	);
}

/** Index of the newest user message that is a prompt (a person's or the act-mode continuation), or -1. */
function promptIndex(messages: ChatMessage[]): number {
	for (let i = messages.length - 1; i >= 0; i -= 1) {
		const m = messages[i];
		if (m.role === "user" && !isRuntimeNote(textOf(m.content))) return i;
	}
	return -1;
}

function quote(text: string): string {
	const t = text.trim().replace(/\s+/g, " ");
	return t.length > 300 ? `${t.slice(0, 300)}…` : t;
}

/**
 * The same `echo hook-test` call for both hook markers; the tool-call id names the marker
 * (`call_mock_hook_block_<n>`, `call_mock_hook_context_<n>`), which the bench hook reads from
 * the PreToolUse payload's `tool_call.id` to choose its answer.
 */
function hookTestCall(marker: "block" | "context"): ToolScript {
	return {
		name: "run_commands",
		args: { commands: ["echo hook-test"] },
		narration: "I'll run the hook test command.",
		callId: `call_mock_hook_${marker}`,
	};
}

/**
 * The markers below steer every request of their prompt's run, including the ones after a tool
 * result or a runtime note (hook context, mistake guidance), so they are checked first:
 *   mock-mistakes     -> `no_such_tool` until a message after the prompt carries the mistake-limit
 *                        guidance ("Do not repeat the same call"), then "Recovered."
 *   mock-act-switch   -> `switch_to_act_mode`; after its tool result (an error in Act mode) a line
 *                        quoting it; the act-mode continuation prompt gets "Plan executed."
 *   mock-hook-block   -> one `run_commands` ["echo hook-test"], then an answer quoting the tool result
 *   mock-hook-context -> the same call, then an answer quoting the `<hook_context>` the hook added
 *   mock-api-error    -> every request of the run fails with HTTP 400 and an OpenAI-style error body,
 *                        so the run ends with a provider error (A11.9: the error row in Brief and
 *                        Verbose); `mock-api-error-<status>` picks the status, for example
 *                        `mock-api-error-503` for a retryable server error
 * scripts/bench/hooks.sh installs the PreToolUse hook the hook markers expect.
 */
function benchScript(messages: ChatMessage[]): Script | undefined {
	const p = promptIndex(messages);
	if (p < 0) return undefined;
	const prompt = textOf(messages[p].content);
	const after = messages.slice(p + 1);
	const toolResult = [...after].reverse().find((m) => m.role === "tool");
	const apiError = /mock-api-error(?:-([1-5]\d\d))?/.exec(prompt);
	if (apiError) return { kind: "api_error", status: apiError[1] ? Number(apiError[1]) : 400 };
	if (prompt.includes("mock-mistakes")) {
		if (after.some((m) => textOf(m.content).includes(MISTAKE_GUIDANCE))) {
			return { kind: "plain", text: "Recovered." };
		}
		return {
			kind: "marker_tool",
			tool: { name: "no_such_tool", args: {}, narration: "I'll call the tool." },
		};
	}
	if (prompt.includes(ACT_CONTINUATION)) {
		const switched = messages.slice(0, p).some((m) => m.role === "user" && textOf(m.content).includes("mock-act-switch"));
		if (switched) return { kind: "plain", text: "Plan executed." };
	}
	if (prompt.includes("mock-act-switch")) {
		if (toolResult) return { kind: "plain", text: `switch_to_act_mode said: ${quote(textOf(toolResult.content))}` };
		return {
			kind: "marker_tool",
			tool: { name: "switch_to_act_mode", args: {}, narration: "The plan is approved, switching to act mode." },
		};
	}
	if (prompt.includes("mock-hook-block")) {
		if (toolResult) return { kind: "plain", text: `The tool result was: ${quote(textOf(toolResult.content))}` };
		return { kind: "marker_tool", tool: hookTestCall("block") };
	}
	if (prompt.includes("mock-hook-context")) {
		if (toolResult) {
			const context = after.map((m) => textOf(m.content)).find((t) => t.trim().startsWith("<hook_context"));
			const body = context?.replace(/<\/?hook_context[^>]*>/g, "");
			return { kind: "plain", text: body ? `The hook context was: ${quote(body)}` : "No hook context arrived." };
		}
		return { kind: "marker_tool", tool: hookTestCall("context") };
	}
	return undefined;
}

function chooseScript(messages: ChatMessage[]): Script {
	const bench = benchScript(messages);
	if (bench) return bench;
	const last = messages[messages.length - 1];
	const lastUser = [...messages].reverse().find((m) => m.role === "user");
	const lastUserText = textOf(lastUser?.content);
	if (last?.role === "tool") {
		const result = textOf(last.content);
		if (/rejected by the user/i.test(result)) {
			return { kind: "rejected_ack" };
		}
		// "mock-chain-edit": after the first tool result of this prompt, make the mock-edit
		// call too (a live edit that lands while the Changes screen is already open).
		const lastUserIndex = messages.lastIndexOf(lastUser as ChatMessage);
		const toolResultsSinceUser = messages.slice(lastUserIndex + 1).filter((m) => m.role === "tool").length;
		if (lastUserText.includes("mock-chain-edit") && toolResultsSinceUser === 1) {
			return { kind: "marker_tool", tool: MARKER_TOOLS["mock-edit"]() };
		}
		return { kind: "final_markdown" };
	}
	if (last?.role === "user") {
		for (const [marker, tool] of Object.entries(MARKER_TOOLS)) {
			if (lastUserText.includes(marker)) return { kind: "marker_tool", tool: tool() };
		}
		if (lastUserText.includes("mock-think")) return { kind: "think" };
		if (lastUserText.includes("mock-links")) return { kind: "links" };
		if (lastUserText.includes("mock-stall")) return { kind: "stall" };
	}
	if (/slow/i.test(lastUserText)) {
		return { kind: "slow" };
	}
	const hasToolResult = messages.some((m) => m.role === "tool");
	if (/hello-from-mock/i.test(lastUserText) && !hasToolResult) {
		return { kind: "tool_call" };
	}
	if (/what did the command print/i.test(lastUserText)) {
		return {
			kind: "plain",
			text: "The command printed `hello-from-mock` on a single line of stdout.",
		};
	}
	return {
		kind: "plain",
		text: "This is the mock provider's plain streamed answer for a second turn.",
	};
}

const FINAL_MARKDOWN = [
	"The command ran successfully. Here is the summary.",
	"",
	"```bash",
	"$ echo hello-from-mock",
	"hello-from-mock",
	"```",
	"",
	"| Field | Value |",
	"| --- | --- |",
	"| Command | `echo hello-from-mock` |",
	"| Exit code | 0 |",
	"| Output lines | 1 |",
	"",
	"- stdout contained exactly `hello-from-mock`",
	"- stderr was empty",
	"- nothing else was executed",
	"",
].join("\n");

function splitChunks(text: string, size: number): string[] {
	const chunks: string[] = [];
	for (let i = 0; i < text.length; i += size) {
		chunks.push(text.slice(i, i + size));
	}
	return chunks;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function sseStream(
	id: string,
	producer: (
		emit: (chunk: Record<string, unknown>) => void,
		aborted: () => boolean,
	) => Promise<void>,
	signal: AbortSignal,
): ReadableStream<Uint8Array> {
	const encoder = new TextEncoder();
	return new ReadableStream<Uint8Array>({
		async start(controller) {
			let closed = false;
			const aborted = () => closed || signal.aborted;
			const emit = (chunk: Record<string, unknown>) => {
				if (aborted()) return;
				try {
					controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`));
				} catch {
					closed = true;
				}
			};
			signal.addEventListener("abort", () => {
				closed = true;
				try {
					controller.close();
				} catch {
					// already closed
				}
			});
			try {
				await producer(emit, aborted);
				if (!aborted()) {
					controller.enqueue(encoder.encode("data: [DONE]\n\n"));
					controller.close();
				}
			} catch {
				if (!aborted()) controller.close();
			}
		},
	});
}

function baseChunk(id: string, delta: Record<string, unknown>, finish: string | null) {
	return {
		id,
		object: "chat.completion.chunk",
		created: 1700000000,
		model: MODEL_ID,
		system_fingerprint: "fp_mock",
		choices: [{ index: 0, delta, logprobs: null, finish_reason: finish }],
	};
}

function usageChunk(id: string, prompt: number, completion: number) {
	return {
		id,
		object: "chat.completion.chunk",
		created: 1700000000,
		model: MODEL_ID,
		system_fingerprint: "fp_mock",
		choices: [],
		usage: {
			prompt_tokens: prompt,
			completion_tokens: completion,
			total_tokens: prompt + completion,
		},
	};
}

async function handleChat(request: Request): Promise<Response> {
	const requestIndex = ++requestCounter;
	const bodyText = await request.text();
	let body: Record<string, unknown> = {};
	try {
		body = JSON.parse(bodyText);
	} catch {
		body = { unparsable: bodyText.slice(0, 2000) };
	}
	const messages = Array.isArray(body.messages) ? (body.messages as ChatMessage[]) : [];
	const script = chooseScript(messages);
	await appendLog({
		index: requestIndex,
		at: new Date().toISOString(),
		method: request.method,
		path: new URL(request.url).pathname,
		headers: sanitizeHeaders(request.headers),
		script: script.kind,
		body,
	});
	console.log(
		`[mock] #${requestIndex} ${request.method} ${new URL(request.url).pathname} messages=${messages.length} stream=${body.stream === true} script=${script.kind}`,
	);
	if (body.stream !== true) {
		return Response.json(
			{ error: { message: "mock provider only supports stream: true" } },
			{ status: 400 },
		);
	}
	const id = `chatcmpl-mock-${requestIndex}`;
	if (script.kind === "stall") return stallResponse(id, request.signal);
	if (script.kind === "api_error") {
		// "mock-api-error": the provider refuses the request outright, before any stream starts.
		return Response.json(
			{
				error: {
					message: `mock-api-error: the mock provider answered HTTP ${script.status}`,
					type: script.status >= 500 ? "server_error" : "invalid_request_error",
					code: "mock_api_error",
				},
			},
			{ status: script.status },
		);
	}
	const stream = sseStream(
		id,
		async (emit, aborted) => {
			emit(baseChunk(id, { role: "assistant", content: "" }, null));
			switch (script.kind) {
				case "tool_call": {
					for (const piece of splitChunks("I'll run the command now.", 6)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(20);
					}
					emit(
						baseChunk(
							id,
							{
								tool_calls: [
									{
										index: 0,
										id: "call_mock_run_1",
										type: "function",
										function: { name: "run_commands", arguments: "" },
									},
								],
							},
							null,
						),
					);
					const argsJson = JSON.stringify({ commands: ["echo hello-from-mock"] });
					for (const piece of splitChunks(argsJson, 10)) {
						emit(
							baseChunk(
								id,
								{ tool_calls: [{ index: 0, function: { arguments: piece } }] },
								null,
							),
						);
						await sleep(10);
					}
					emit(baseChunk(id, {}, "tool_calls"));
					emit(usageChunk(id, 1200, 24));
					return;
				}
				case "marker_tool": {
					for (const piece of splitChunks(script.tool.narration, 6)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(20);
					}
					const calls = [{ name: script.tool.name, args: script.tool.args }, ...(script.tool.more ?? [])];
					for (const [index, call] of calls.entries()) {
						emit(
							baseChunk(
								id,
								{
									tool_calls: [
										{
											index,
											id: `${script.tool.callId ?? `call_mock_${call.name}`}_${requestIndex}${index > 0 ? `_${index}` : ""}`,
											type: "function",
											function: { name: call.name, arguments: "" },
										},
									],
								},
								null,
							),
						);
						const argsText = JSON.stringify(call.args);
						for (const piece of splitChunks(argsText, Math.max(10, Math.ceil(argsText.length / 40)))) {
							emit(baseChunk(id, { tool_calls: [{ index, function: { arguments: piece } }] }, null));
							await sleep(10);
						}
					}
					emit(baseChunk(id, {}, "tool_calls"));
					emit(usageChunk(id, 1200, 30));
					return;
				}
				case "think": {
					for (const piece of splitChunks(THINK_REASONING, 8)) {
						if (aborted()) return;
						emit(baseChunk(id, { reasoning_content: piece }, null));
						await sleep(120);
					}
					for (const piece of splitChunks("Thought it through: the mock answers after thinking.", 10)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(20);
					}
					emit(baseChunk(id, {}, "stop"));
					emit(usageChunk(id, 1500, 220));
					return;
				}
				case "links": {
					for (const piece of splitChunks(LINKS_MARKDOWN, 24)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(15);
					}
					emit(baseChunk(id, {}, "stop"));
					emit(usageChunk(id, 1300, 60));
					return;
				}
				case "final_markdown": {
					for (const piece of splitChunks(FINAL_MARKDOWN, 24)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(15);
					}
					emit(baseChunk(id, {}, "stop"));
					emit(usageChunk(id, 1300, 96));
					return;
				}
				case "rejected_ack": {
					for (const piece of splitChunks(
						"Understood - the command was rejected, so nothing was executed.",
						12,
					)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(15);
					}
					emit(baseChunk(id, {}, "stop"));
					emit(usageChunk(id, 1250, 14));
					return;
				}
				case "slow": {
					for (let i = 1; i <= 40; i += 1) {
						if (aborted()) return;
						emit(baseChunk(id, { content: `slow chunk ${i} ` }, null));
						await sleep(300);
					}
					emit(baseChunk(id, {}, "stop"));
					emit(usageChunk(id, 1100, 120));
					return;
				}
				case "plain": {
					for (const piece of splitChunks(script.text, 10)) {
						emit(baseChunk(id, { content: piece }, null));
						await sleep(15);
					}
					emit(baseChunk(id, {}, "stop"));
					emit(usageChunk(id, 1400, 18));
					return;
				}
			}
		},
		request.signal,
	);
	return new Response(stream, {
		status: 200,
		headers: {
			"content-type": "text/event-stream; charset=utf-8",
			"cache-control": "no-cache",
			connection: "keep-alive",
			"x-request-id": id,
		},
	});
}

/**
 * "mock-stall": the response starts (status and SSE headers are flushed at once), then the body
 * stays silent for STALL_MS, then a short normal answer streams. A client abort (Stop) ends the
 * wait and the response without an answer.
 */
function stallResponse(id: string, signal: AbortSignal): Response {
	const encoder = new TextEncoder();
	const body = new ReadableStream<Uint8Array>({
		async start(controller) {
			const stalled = await new Promise<boolean>((resolve) => {
				if (signal.aborted) return resolve(false);
				const timer = setTimeout(() => resolve(true), STALL_MS);
				signal.addEventListener("abort", () => {
					clearTimeout(timer);
					resolve(false);
				});
			});
			const send = (chunk: Record<string, unknown>) =>
				controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`));
			try {
				if (!stalled || signal.aborted) {
					controller.close();
					return;
				}
				send(baseChunk(id, { role: "assistant", content: "" }, null));
				for (const piece of splitChunks("The mock answered after a long silence.", 10)) {
					if (signal.aborted) break;
					send(baseChunk(id, { content: piece }, null));
					await sleep(15);
				}
				if (!signal.aborted) {
					send(baseChunk(id, {}, "stop"));
					send(usageChunk(id, 1100, 9));
					controller.enqueue(encoder.encode("data: [DONE]\n\n"));
				}
				controller.close();
			} catch {
				// The client went away mid-answer.
			}
		},
	});
	console.log(`[mock] ${id} stalling for ${STALL_MS} ms`);
	return new Response(body, {
		status: 200,
		headers: {
			"content-type": "text/event-stream; charset=utf-8",
			"cache-control": "no-cache",
			connection: "keep-alive",
			"x-request-id": id,
		},
	});
}

const server = Bun.serve({
	hostname: "127.0.0.1",
	port: PORT,
	// A silent "mock-stall" response must outlive its STALL_MS (Bun's limit is 255 s).
	idleTimeout: Math.min(255, Math.max(120, Math.ceil(STALL_MS / 1000) + 30)),
	async fetch(request) {
		const url = new URL(request.url);
		if (request.method === "GET" && url.pathname === "/v1/models") {
			await appendLog({
				index: ++requestCounter,
				at: new Date().toISOString(),
				method: "GET",
				path: url.pathname,
				headers: sanitizeHeaders(request.headers),
			});
			return Response.json({
				object: "list",
				data: [{ id: MODEL_ID, object: "model", created: 1700000000, owned_by: "mock" }],
			});
		}
		if (request.method === "POST" && url.pathname === "/v1/chat/completions") {
			return handleChat(request);
		}
		if (url.pathname === "/health") {
			return Response.json({ ok: true, requests: requestCounter });
		}
		await appendLog({
			index: ++requestCounter,
			at: new Date().toISOString(),
			method: request.method,
			path: url.pathname,
			headers: sanitizeHeaders(request.headers),
			note: "unhandled route",
		});
		return new Response("not found", { status: 404 });
	},
});

console.log(`[mock] OpenAI-compatible mock listening on http://127.0.0.1:${server.port}/v1 (log: ${LOG_PATH})`);
