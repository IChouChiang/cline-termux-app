// Kept here as reference material for upstream items U9/U10 (docs/decided.md, "Compaction check 2").
// It imports the kernel's TypeScript sources by relative path, so copy it to
// <port repo>/release/.work/compaction-replay.ts and run it from the port repo root after `bun run build:sdk`.
// Offline replay of the kernel's auto-compaction step over a saved session transcript.
// No real provider is contacted: a stub fetch answers the summariser request.
//   bun run release/.work/compaction-replay.ts <reject|empty> [messages.json]   (default: ./messages.json)
// Numbers that come from the Hub event log of the real run (MyTest, 2026-10-09):
//   maxInputTokens 258400, triggerTokens 232560, targetTokens 129200, messageTargetTokens 118358
import { readFileSync } from "node:fs";
import { MessageBuilder } from "../../sdk/packages/core/src/session/services/message-builder";
import { createContextCompactionPrepareTurn } from "../../sdk/packages/core/src/extensions/context/compaction";

const MODE = process.argv[2] ?? "reject";
const PATH = process.argv[3] ?? "messages.json";
const OVERHEAD_TOKENS = 129_200 - 118_358; // request overhead the real run reported
const CHARS_PER_TOKEN = 3;

const raw = JSON.parse(readFileSync(PATH, "utf8"));
const messages = Array.isArray(raw) ? raw : raw.messages;

function show(label: string, meta?: Record<string, unknown>) {
	const s = meta ? JSON.stringify(meta) : "";
	console.log(`${label} ${s.length > 900 ? s.slice(0, 900) + "…" : s}`);
}
const logger = {
	log: (m: string, meta?: Record<string, unknown>) => show(`[log] ${m}`, meta),
	debug: (m: string, meta?: Record<string, unknown>) => show(`[debug] ${m}`, meta),
	error: (m: string, meta?: Record<string, unknown>) => show(`[error] ${m}`, meta),
};

const builder = new MessageBuilder();
const apiMessages = builder.buildForApi(messages as never[]);
const jsonChars = (v: unknown) => JSON.stringify(v).length;
console.log(`messages=${messages.length} rawChars=${jsonChars(messages)} apiChars=${jsonChars(apiMessages)} (÷${CHARS_PER_TOKEN})`);
let imageChars = 0;
for (const m of apiMessages as { content: unknown }[]) {
	if (Array.isArray(m.content)) for (const p of m.content as { type: string }[]) if (p.type === "image") imageChars += jsonChars(p);
}
console.log(`image part chars in api messages: ${imageChars} (~${Math.ceil(imageChars / CHARS_PER_TOKEN)} est tokens)`);

let summaryRequestChars = 0;
let summaryCjk = 0;
let summaryCalls = 0;
const stubFetch: typeof fetch = async (_input, init) => {
	summaryCalls += 1;
	const body = typeof init?.body === "string" ? init.body : "";
	try {
		const parsed = JSON.parse(body);
		const user = (parsed.messages ?? []).find((m: { role: string }) => m.role === "user");
		const text = typeof user?.content === "string" ? user.content : JSON.stringify(user?.content ?? "");
		summaryRequestChars = text.length;
		for (const ch of text) if (ch.charCodeAt(0) >= 0x2e80) summaryCjk += 1;
		console.log(`[stub] summariser request: bodyChars=${body.length} userTextChars=${summaryRequestChars} cjkChars=${summaryCjk} model=${parsed.model} stream=${parsed.stream}`);
	} catch {
		console.log(`[stub] summariser request: bodyChars=${body.length} (not JSON)`);
	}
	if (MODE === "reject") {
		return new Response(
			JSON.stringify({ error: { message: "This model's maximum context length is 131072 tokens. However, you requested more.", type: "invalid_request_error", code: "invalid_request_error" } }),
			{ status: 400, headers: { "content-type": "application/json" } },
		);
	}
	// "empty": a well-formed stream that carries no text
	const sse = [
		`data: ${JSON.stringify({ id: "x", object: "chat.completion.chunk", created: 1, model: "deepseek-chat", choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] })}`,
		`data: ${JSON.stringify({ id: "x", object: "chat.completion.chunk", created: 1, model: "deepseek-chat", choices: [{ index: 0, delta: {}, finish_reason: "stop" }], usage: { prompt_tokens: 1, completion_tokens: 0, total_tokens: 1 } })}`,
		"data: [DONE]",
		"",
	].join("\n\n");
	return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } });
};

const limits = { contextWindow: 400_000, maxInputTokens: 258_400, maxTokens: 128_000 };
const providerConfig = {
	providerId: "deepseek",
	modelId: "deepseek-chat",
	apiKey: "sk-replay-dummy",
	knownModels: { "deepseek-chat": { id: "deepseek-chat", name: "deepseek-chat", ...limits } },
	fetch: stubFetch,
} as never;

const prepareTurn = createContextCompactionPrepareTurn(
	{
		providerConfig,
		providerId: "deepseek",
		modelId: "deepseek-chat",
		apiKey: "sk-replay-dummy",
		compaction: { enabled: true },
		logger,
		sessionId: "replay",
	} as never,
	{},
);
if (!prepareTurn) throw new Error("compaction disabled?");

const started = Date.now();
let result: unknown;
try {
	result = await prepareTurn({
		agentId: "lead",
		conversationId: "replay",
		parentAgentId: null,
		iteration: 1,
		messages: messages as never,
		apiMessages: apiMessages as never,
		abortSignal: new AbortController().signal,
		systemPrompt: "x".repeat(OVERHEAD_TOKENS * CHARS_PER_TOKEN),
		tools: [],
		model: { id: "gpt-6-astra", provider: "openai-codex", info: { id: "gpt-6-astra", name: "gpt-6-astra", ...limits } } as never,
		previousRequestInputTokens: 104_119,
		emitStatusNotice: (m, meta) => show(`[NOTICE] ${m}`, meta),
	});
} catch (error) {
	console.log(`[thrown] ${(error as Error).message}`);
}
const r = result as { messages?: unknown[] } | undefined;
console.log(`mode=${MODE} summariserCalls=${summaryCalls} durationMs=${Date.now() - started} result=${r?.messages ? `${r.messages.length} messages` : "undefined (skipped)"}`);
