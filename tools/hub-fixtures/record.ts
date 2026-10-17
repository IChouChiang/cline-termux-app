/**
 * Records real Cline Hub protocol traffic into JSONL fixtures for the Android
 * client's unit tests. Runs on the workstation only, against an isolated kernel
 * home (CLINE_DIR) and the mock provider from ./mock-openai.ts, so no real
 * credentials, no real ~/.cline and no LLM credits are involved.
 *
 * Prerequisites (see docs/08-hub-fixtures.md for the full recipe):
 *   export CLINE_DIR=<isolated home>; export CLINE_HUB_PORT=25498
 *   bun tools/hub-fixtures/mock-openai.ts --port 25499 &
 *   tools/hub-fixtures/cline-isolated.sh auth --provider openai-compatible \
 *       --apikey mock --modelid mock-1 --baseurl http://127.0.0.1:25499/v1
 *   tools/hub-fixtures/cline-isolated.sh hub start
 *   bun tools/hub-fixtures/record.ts --workspace <scratch dir with a few files>
 *
 * Scenario 07 (checkpoint restore) is opt-in: `--only 07 --workspace /tmp/cline-dev-hub/workspace`
 * against dev-hub.sh's bench (a clean git repository the mock also edits); it resets that
 * workspace with `git checkout -- . && git clean -fd` when it ends.
 *
 * Output: one JSONL file per scenario under app/src/test/resources/hub-fixtures/.
 * Each line is {"t": <ms since the scenario started>, "dir": "send"|"recv"|"note", ...}
 * where "send"/"recv" lines carry the raw frame under "frame" and "note" lines
 * carry structured observations. The Hub auth token is replaced by the literal
 * string "<hub-auth-token>" wherever it would appear.
 */

import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { connect as netConnect } from "node:net";
import { resolve } from "node:path";

// ── configuration ─────────────────────────────────────────────────────────────

const argv = process.argv.slice(2);
function argValue(flag: string, fallback: string): string {
	const index = argv.indexOf(flag);
	return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback;
}

const CLINE_DIR = process.env.CLINE_DIR?.trim();
if (!CLINE_DIR) {
	console.error("CLINE_DIR must point at the isolated fixture home");
	process.exit(2);
}
const HUB_PORT = Number.parseInt(process.env.CLINE_HUB_PORT ?? "25498", 10);
const HUB_HOST = "127.0.0.1";
const HUB_URL = `ws://${HUB_HOST}:${HUB_PORT}/hub`;
const DISCOVERY_PATH = resolve(CLINE_DIR, "data", "locks", "hub", "production.json");
const APP_ROOT = resolve(import.meta.dir, "..", "..");
const OUT_DIR = resolve(argValue("--out", resolve(APP_ROOT, "app", "src", "test", "resources", "hub-fixtures")));
const WORKSPACE = resolve(argValue("--workspace", resolve(CLINE_DIR, "..", "hubfix-ws")));
const CLI = resolve(import.meta.dir, "cline-isolated.sh");
const PROVIDER_ID = "openai-compatible";
const MODEL_ID = "mock-1";
const CLIENT_ID = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
const REJECTION_REASON = "This tool call was rejected by the user and not executed.";
const PROTOCOL_PREFIX = "cline-hub-auth.";

mkdirSync(OUT_DIR, { recursive: true });

// ── helpers ───────────────────────────────────────────────────────────────────

const sleep = (ms: number) => new Promise<void>((done) => setTimeout(done, ms));

function readDiscovery(): { authToken: string; pid?: number; startedAt?: string; hubId?: string; url?: string } {
	return JSON.parse(readFileSync(DISCOVERY_PATH, "utf8"));
}

let redactTokens: string[] = [];
function redact(text: string): string {
	let out = text;
	for (const token of redactTokens) {
		if (token) out = out.split(token).join("<hub-auth-token>");
	}
	return out;
}

class Recorder {
	private readonly path: string;
	private readonly startedAt = performance.now();
	private requestCounter = 0;

	constructor(name: string) {
		this.path = resolve(OUT_DIR, name);
		writeFileSync(this.path, "");
	}

	private stamp(): number {
		return Math.round((performance.now() - this.startedAt) * 10) / 10;
	}

	private write(entry: Record<string, unknown>): void {
		appendFileSync(this.path, `${redact(JSON.stringify(entry))}\n`);
	}

	send(frame: unknown, connection: string): void {
		this.write({ t: this.stamp(), dir: "send", conn: connection, frame });
	}

	recv(frame: unknown, connection: string): void {
		this.write({ t: this.stamp(), dir: "recv", conn: connection, frame });
	}

	note(note: string, extra: Record<string, unknown> = {}): void {
		console.log(`  [note] ${note}`);
		this.write({ t: this.stamp(), dir: "note", note, ...extra });
	}

	nextRequestId(prefix: string): string {
		this.requestCounter += 1;
		return `${prefix}-${String(this.requestCounter).padStart(3, "0")}`;
	}
}

type Frame = Record<string, any>;

class HubConnection {
	private socket!: WebSocket;
	private readonly pending = new Map<string, { resolve: (reply: Frame) => void; reject: (error: Error) => void; timer?: ReturnType<typeof setTimeout> }>();
	readonly events: Frame[] = [];
	private waiters: Array<() => void> = [];
	lastSequence = 0;
	closed: Promise<{ code: number; reason: string }>;
	private resolveClosed!: (info: { code: number; reason: string }) => void;
	private closedInfo?: { code: number; reason: string };

	constructor(
		readonly recorder: Recorder,
		readonly name: string,
		readonly clientId: string,
	) {
		this.closed = new Promise((done) => {
			this.resolveClosed = done;
		});
	}

	setRecorder(recorder: Recorder): HubConnection {
		(this as { recorder: Recorder }).recorder = recorder;
		return this;
	}

	async open(options: { token?: string; headers?: Record<string, string> }): Promise<void> {
		const protocols = options.token ? [`${PROTOCOL_PREFIX}${options.token}`] : undefined;
		const socket = new WebSocket(HUB_URL, {
			...(protocols ? { protocols } : {}),
			...(options.headers ? { headers: options.headers } : {}),
		} as any);
		this.socket = socket;
		await new Promise<void>((done, fail) => {
			socket.addEventListener("open", () => {
				this.recorder.note(`${this.name}: websocket open`, {
					url: HUB_URL,
					sentSubprotocol: protocols ? `${PROTOCOL_PREFIX}<hub-auth-token>` : null,
					sentHeaders: options.headers ?? null,
					negotiatedProtocol: socket.protocol ? redact(socket.protocol) : "",
				});
				done();
			});
			socket.addEventListener("error", (event: any) => {
				const message = event?.message ?? String(event);
				this.recorder.note(`${this.name}: websocket error before open`, { message });
				fail(new Error(message));
			});
		});
		socket.addEventListener("message", (event) => {
			const text = typeof event.data === "string" ? event.data : String(event.data);
			let frame: Frame;
			try {
				frame = JSON.parse(text);
			} catch {
				this.recorder.note(`${this.name}: non-JSON frame`, { text: text.slice(0, 200) });
				return;
			}
			this.recorder.recv(frame, this.name);
			if (frame.kind === "reply") {
				const requestId = frame.envelope?.requestId;
				const pending = requestId ? this.pending.get(requestId) : undefined;
				if (pending) {
					this.pending.delete(requestId);
					if (pending.timer) clearTimeout(pending.timer);
					pending.resolve(frame.envelope);
				}
			} else if (frame.kind === "event") {
				const sequence = frame.envelope?.sequence;
				if (typeof sequence === "number" && sequence > this.lastSequence) {
					this.lastSequence = sequence;
				}
				this.events.push(frame.envelope);
				const waiters = this.waiters;
				this.waiters = [];
				for (const wake of waiters) wake();
			}
		});
		socket.addEventListener("close", (event) => {
			this.closedInfo = { code: event.code, reason: event.reason };
			this.recorder.note(`${this.name}: websocket closed`, { code: event.code, reason: event.reason });
			for (const [, pending] of this.pending) {
				pending.reject(new Error(`connection closed (code ${event.code})`));
			}
			this.pending.clear();
			this.resolveClosed(this.closedInfo);
			const waiters = this.waiters;
			this.waiters = [];
			for (const wake of waiters) wake();
		});
	}

	sendFrame(frame: Frame): void {
		this.recorder.send(frame, this.name);
		this.socket.send(JSON.stringify(frame));
	}

	command(
		command: string,
		payload?: Record<string, unknown>,
		options: { sessionId?: string; timeoutMs?: number | null; localTimeoutMs?: number; clientId?: string | null } = {},
	): Promise<Frame> {
		const requestId = this.recorder.nextRequestId(command.replace(/\./g, "-"));
		const envelope: Frame = {
			version: "v1",
			command,
			requestId,
			...(options.clientId === null ? {} : { clientId: options.clientId ?? this.clientId }),
			...(options.sessionId ? { sessionId: options.sessionId } : {}),
			...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
			...(payload !== undefined ? { payload } : {}),
		};
		const localTimeoutMs = options.localTimeoutMs ?? 60_000;
		return new Promise<Frame>((resolveReply, rejectReply) => {
			const timer = setTimeout(() => {
				this.pending.delete(requestId);
				rejectReply(new Error(`${command} (${requestId}) got no reply within ${localTimeoutMs}ms`));
			}, localTimeoutMs);
			this.pending.set(requestId, { resolve: resolveReply, reject: rejectReply, timer });
			this.sendFrame({ kind: "command", envelope });
		});
	}

	subscribe(options: { sessionId?: string; sinceSequence?: number } = {}): void {
		this.sendFrame({
			kind: "stream.subscribe",
			clientId: this.clientId,
			...(options.sessionId ? { sessionId: options.sessionId } : {}),
			...(options.sinceSequence !== undefined ? { sinceSequence: options.sinceSequence } : {}),
		});
	}

	mark(): number {
		return this.events.length;
	}

	async waitForEvent(
		since: number,
		predicate: (event: Frame) => boolean,
		label: string,
		timeoutMs = 60_000,
	): Promise<Frame> {
		const deadline = Date.now() + timeoutMs;
		let cursor = since;
		for (;;) {
			for (; cursor < this.events.length; cursor += 1) {
				const event = this.events[cursor];
				if (predicate(event)) return event;
			}
			if (this.closedInfo) throw new Error(`connection closed while waiting for ${label}`);
			const remaining = deadline - Date.now();
			if (remaining <= 0) throw new Error(`timed out waiting for ${label}`);
			await new Promise<void>((wake) => {
				this.waiters.push(wake);
				setTimeout(wake, Math.min(remaining, 1_000));
			});
		}
	}

	async close(code = 1000, reason = "fixture done"): Promise<{ code: number; reason: string }> {
		if (this.closedInfo) return this.closedInfo;
		this.socket.close(code, reason);
		return await this.closed;
	}
}

async function runCli(recorder: Recorder, args: string[], label: string): Promise<{ code: number; stdout: string; stderr: string }> {
	const proc = Bun.spawn([CLI, ...args], {
		env: { ...process.env, CLINE_DIR, CLINE_HUB_PORT: String(HUB_PORT) },
		stdout: "pipe",
		stderr: "pipe",
	});
	const [stdout, stderr] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text()]);
	const code = await proc.exited;
	recorder.note(label, {
		command: `cline-isolated.sh ${args.join(" ")}`,
		exitCode: code,
		stdout: redact(stdout.trim()).slice(-2000),
		stderr: redact(stderr.trim()).slice(-2000),
	});
	return { code, stdout, stderr };
}

/**
 * Plain HTTP GET over a raw socket. `fetch` is deliberately avoided: Bun's fetch
 * honours HTTP_PROXY and does not reliably honour NO_PROXY, so on a workstation
 * behind a proxy the Hub's loopback endpoints would be answered by the proxy
 * (a bare 503) instead of the Hub.
 */
async function fetchJson(path: string, headers: Record<string, string> = {}): Promise<{ status: number; body: unknown }> {
	return await new Promise((done, fail) => {
		const chunks: Buffer[] = [];
		const socket = netConnect({ host: HUB_HOST, port: HUB_PORT });
		let settled = false;
		const finish = (error?: Error) => {
			if (settled) return;
			settled = true;
			socket.destroy();
			if (error) {
				fail(error);
				return;
			}
			const text = Buffer.concat(chunks).toString("utf8");
			const separator = text.indexOf("\r\n\r\n");
			const head = separator >= 0 ? text.slice(0, separator) : text;
			const rawBody = separator >= 0 ? text.slice(separator + 4) : "";
			const status = Number.parseInt(head.split(" ")[1] ?? "0", 10);
			let body: unknown = rawBody;
			try {
				body = JSON.parse(rawBody);
			} catch {
				// keep text
			}
			done({ status, body });
		};
		socket.on("connect", () => {
			socket.write(
				[
					`GET ${path} HTTP/1.1`,
					`Host: ${HUB_HOST}:${HUB_PORT}`,
					"Connection: close",
					...Object.entries(headers).map(([key, value]) => `${key}: ${value}`),
					"",
					"",
				].join("\r\n"),
			);
		});
		socket.on("data", (chunk) => chunks.push(chunk));
		socket.on("end", () => finish());
		socket.on("close", () => finish());
		socket.on("error", (error) => finish(error));
		setTimeout(() => finish(new Error("timeout")), 5_000);
	});
}

/** Raw HTTP/1.1 upgrade attempt so the exact status line of a refusal is visible. */
async function rawUpgrade(
	path: string,
	extraHeaders: string[],
): Promise<{ statusLine: string | null; headers: string[]; closedWithoutResponse: boolean }> {
	return await new Promise((done) => {
		const chunks: Buffer[] = [];
		const socket = netConnect({ host: HUB_HOST, port: HUB_PORT });
		let settled = false;
		const finish = () => {
			if (settled) return;
			settled = true;
			socket.destroy();
			const text = Buffer.concat(chunks).toString("utf8");
			const headerBlock = text.split("\r\n\r\n")[0] ?? "";
			const lines = headerBlock.split("\r\n").filter(Boolean);
			done({
				statusLine: lines[0] ?? null,
				headers: lines.slice(1).map((line) => redact(line)),
				closedWithoutResponse: text.length === 0,
			});
		};
		socket.on("connect", () => {
			socket.write(
				[
					`GET ${path} HTTP/1.1`,
					`Host: ${HUB_HOST}:${HUB_PORT}`,
					"Upgrade: websocket",
					"Connection: Upgrade",
					"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
					"Sec-WebSocket-Version: 13",
					...extraHeaders,
					"",
					"",
				].join("\r\n"),
			);
		});
		socket.on("data", (chunk) => {
			chunks.push(chunk);
			if (Buffer.concat(chunks).toString("utf8").includes("\r\n\r\n")) finish();
		});
		socket.on("close", finish);
		socket.on("error", finish);
		setTimeout(finish, 3_000);
	});
}

function sessionCreatePayload(extra: Record<string, unknown> = {}, sessionConfigExtra: Record<string, unknown> = {}) {
	return {
		workspaceRoot: WORKSPACE,
		cwd: WORKSPACE,
		sessionConfig: {
			providerId: PROVIDER_ID,
			modelId: MODEL_ID,
			mode: "act",
			enableTools: true,
			checkpoint: { enabled: true },
			...sessionConfigExtra,
		},
		metadata: {
			source: "android",
			interactive: true,
			provider: PROVIDER_ID,
			model: MODEL_ID,
		},
		toolPolicies: { "*": { autoApprove: false } },
		...extra,
	};
}

function registerPayload(clientId: string, withWorkspace = true) {
	return {
		clientId,
		clientType: "android",
		displayName: "Cline for Android (fixture recorder)",
		transport: "native",
		actorKind: "client",
		capabilities: [{ name: "approval.respond" }, { name: "tool_executor.askQuestion" }],
		metadata: { version: "0.1.0-fixture", platform: "android", pid: 0 },
		...(withWorkspace ? { workspaceContext: { workspaceRoot: WORKSPACE, cwd: WORKSPACE } } : {}),
		protocolVersion: "v1",
	};
}

const isEvent = (name: string, sessionId?: string) => (event: Frame) =>
	event.event === name && (!sessionId || event.sessionId === sessionId);

// ── scenarios ─────────────────────────────────────────────────────────────────

const state: {
	token: string;
	conn?: HubConnection;
	session1?: string;
	session2?: string;
	lastSequenceBeforeRestart?: number;
} = { token: readDiscovery().authToken };
redactTokens = [state.token];

async function scenario01(): Promise<void> {
	console.log("== 01-register-and-status");
	const rec = new Recorder("01-register-and-status.jsonl");
	const discovery = readDiscovery();
	rec.note("discovery record read from CLINE_DIR/data/locks/hub/production.json", {
		fields: Object.keys(discovery),
		hubId: discovery.hubId,
		pid: discovery.pid,
		startedAt: discovery.startedAt,
		url: discovery.url,
		authTokenLength: state.token.length,
	});
	rec.note("GET /health (no auth)", await fetchJson("/health"));
	rec.note("GET /version (no auth)", await fetchJson("/version"));
	const conn = new HubConnection(rec, "c1", CLIENT_ID);
	await conn.open({ token: state.token });
	state.conn = conn;
	await conn.command("client.register", registerPayload(CLIENT_ID));
	await conn.command("hub.status", {});
	await conn.command("settings.list", { workspaceRoot: WORKSPACE, cwd: WORKSPACE, includePluginTools: true });
	await conn.command("session.list", { limit: 50, rootOnly: true });
	conn.subscribe();
	await sleep(500);
	rec.note("global stream.subscribe sent without sinceSequence; socket stays open for the next scenarios", {
		lastSequenceSeen: conn.lastSequence,
	});
}

async function scenario02(): Promise<void> {
	console.log("== 02-run-with-tool-approval");
	const rec = new Recorder("02-run-with-tool-approval.jsonl");
	const conn = state.conn!.setRecorder(rec);
	const created = await conn.command("session.create", sessionCreatePayload(), { timeoutMs: 120_000, localTimeoutMs: 150_000 });
	const sessionId = created.payload?.session?.sessionId ?? created.payload?.snapshot?.sessionId;
	if (!sessionId) throw new Error(`session.create failed: ${JSON.stringify(created.error)}`);
	state.session1 = sessionId;
	rec.note("session created", { sessionId });
	conn.subscribe({ sessionId });
	await sleep(200);
	const mark = conn.mark();
	const runReply = conn.command(
		"session.send_input",
		{ prompt: "Run echo hello-from-mock and summarize" },
		{ sessionId, timeoutMs: null, localTimeoutMs: 240_000 },
	);
	runReply.catch(() => undefined);
	const approval = await conn.waitForEvent(mark, isEvent("approval.requested", sessionId), "approval.requested", 180_000);
	rec.note("approval.requested received; approving", {
		approvalId: approval.payload?.approvalId,
		toolName: approval.payload?.toolName,
		inputJson: approval.payload?.inputJson,
	});
	await conn.command("approval.respond", { approvalId: approval.payload.approvalId, approved: true });
	const completed = await conn.waitForEvent(mark, (event) => /^run\.(completed|failed|aborted)$/.test(event.event) && event.sessionId === sessionId, "run terminal event", 180_000);
	rec.note("terminal run event observed", { event: completed.event, sequence: completed.sequence });
	const reply = await runReply;
	rec.note("session.send_input reply arrived after the terminal event", { ok: reply.ok, finishReason: reply.payload?.result?.finishReason });
	await sleep(1_500);
	const toolEvents = conn.events.filter((event) => event.sessionId === sessionId && /^tool\./.test(event.event));
	rec.note("tool.* events seen for this session (both subscriptions)", {
		count: toolEvents.length,
		kinds: toolEvents.map((event) => `${event.event}${event.payload?.toolCallId ? "" : "(no toolCallId)"}`),
	});
}

async function scenario03(): Promise<void> {
	console.log("== 03-reject-and-abort");
	const rec = new Recorder("03-reject-and-abort.jsonl");
	const conn = state.conn!.setRecorder(rec);
	const created = await conn.command("session.create", sessionCreatePayload(), { timeoutMs: 120_000, localTimeoutMs: 150_000 });
	const sessionId = created.payload?.session?.sessionId ?? created.payload?.snapshot?.sessionId;
	if (!sessionId) throw new Error(`session.create failed: ${JSON.stringify(created.error)}`);
	state.session2 = sessionId;
	conn.subscribe({ sessionId });
	await sleep(200);

	let mark = conn.mark();
	let runReply = conn.command(
		"session.send_input",
		{ prompt: "Run echo hello-from-mock and summarize" },
		{ sessionId, timeoutMs: null, localTimeoutMs: 240_000 },
	);
	runReply.catch(() => undefined);
	const approval = await conn.waitForEvent(mark, isEvent("approval.requested", sessionId), "approval.requested", 180_000);
	rec.note("rejecting the approval with the documented reason string", { approvalId: approval.payload?.approvalId });
	await conn.command("approval.respond", { approvalId: approval.payload.approvalId, approved: false, reason: REJECTION_REASON });
	let terminal = await conn.waitForEvent(mark, (event) => /^run\.(completed|failed|aborted)$/.test(event.event) && event.sessionId === sessionId, "run terminal event", 180_000);
	rec.note("terminal event after rejection", { event: terminal.event });
	let reply = await runReply;
	rec.note("send_input reply after rejection", { ok: reply.ok, finishReason: reply.payload?.result?.finishReason });
	await sleep(1_000);

	mark = conn.mark();
	runReply = conn.command(
		"session.send_input",
		{ prompt: "Please stream a slow answer so I can interrupt it" },
		{ sessionId, timeoutMs: null, localTimeoutMs: 240_000 },
	);
	runReply.catch(() => undefined);
	await conn.waitForEvent(mark, isEvent("assistant.delta", sessionId), "first assistant.delta", 180_000);
	await sleep(1_500);
	rec.note("calling run.abort mid-stream");
	await conn.command("run.abort", { sessionId, reason: "User pressed Stop" }, { sessionId });
	terminal = await conn.waitForEvent(mark, (event) => /^run\.(completed|failed|aborted)$/.test(event.event) && event.sessionId === sessionId, "run terminal event", 180_000);
	rec.note("terminal event after abort", { event: terminal.event, reason: terminal.payload?.reason });
	reply = await runReply;
	rec.note("send_input reply after abort", { ok: reply.ok, finishReason: reply.payload?.result?.finishReason, error: reply.error });
	await sleep(1_500);
}

async function scenario04(): Promise<void> {
	console.log("== 04-history-and-replay");
	const rec = new Recorder("04-history-and-replay.jsonl");
	const conn = state.conn!.setRecorder(rec);
	const sessionId = state.session1!;
	const messagesReply = await conn.command("session.messages", { sessionId }, { sessionId });
	rec.note("session.messages content block types", {
		messageCount: messagesReply.payload?.messages?.length,
		blocks: (messagesReply.payload?.messages ?? []).map((message: Frame) => ({
			role: message.role,
			types: (message.content ?? []).map((block: Frame) => block.type),
			metadataKeys: Object.keys(message.metadata ?? {}),
		})),
	});
	await conn.command("session.get", { sessionId, includeSnapshot: true }, { sessionId });
	const firstSearch = await conn.command("session.search", { query: "hello", limit: 20 });
	if ((firstSearch.payload?.hits ?? []).length === 0) {
		rec.note("session.search returned no hits right after the run; retrying after 3 s (index refresh is asynchronous)");
		await sleep(3_000);
		await conn.command("session.search", { query: "hello", limit: 20 });
	}
	await conn.command("session.update", { sessionId, title: "Fixture: hello-from-mock", metadata: { pinned: true } }, { sessionId });
	await conn.command("session.get", { sessionId }, { sessionId });
	await sleep(300);

	const lastSequence = conn.lastSequence;
	const sinceSequence = Math.max(0, lastSequence - 3);
	rec.note("disconnecting c1 to test replay", { lastSequenceSeen: lastSequence, sinceSequence });
	await conn.close();
	await sleep(300);

	const conn2 = new HubConnection(rec, "c2", CLIENT_ID);
	await conn2.open({ token: state.token });
	await conn2.command("client.register", registerPayload(CLIENT_ID));
	rec.note("re-registered with the SAME clientId on a new connection after the old one closed");
	const replayMark = conn2.mark();
	conn2.subscribe({ sinceSequence });
	await sleep(1_500);
	const replayed = conn2.events.slice(replayMark);
	rec.note("global replay result", {
		sinceSequence,
		replayedSequences: replayed.map((event) => event.sequence),
		replayedEvents: replayed.map((event) => event.event),
	});
	const scopedMark = conn2.mark();
	const scopedSince = Math.max(0, lastSequence - 12);
	conn2.subscribe({ sessionId, sinceSequence: scopedSince });
	await sleep(1_500);
	const scoped = conn2.events.slice(scopedMark);
	rec.note("session-scoped replay result", {
		sessionId,
		sinceSequence: scopedSince,
		replayedSequences: scoped.map((event) => event.sequence),
		replayedEvents: scoped.map((event) => event.event),
		allForThisSession: scoped.every((event) => event.sessionId === sessionId),
	});

	// Same clientId on a second live connection (half-open socket simulation).
	const conn3 = new HubConnection(rec, "c3", CLIENT_ID);
	await conn3.open({ token: state.token });
	const dupRegister = await conn3.command("client.register", registerPayload(CLIENT_ID));
	rec.note("client.register with the same clientId while c2 is still open", { ok: dupRegister.ok, error: dupRegister.error });
	const listBefore = await conn3.command("client.list", {});
	rec.note("client.list while both connections are open", {
		clientIds: (listBefore.payload?.clients ?? []).map((client: Frame) => client.clientId),
	});
	await conn2.close();
	await sleep(500);
	const listAfter = await conn3.command("client.list", {});
	rec.note("client.list after the older connection (c2) closed", {
		clientIds: (listAfter.payload?.clients ?? []).map((client: Frame) => client.clientId),
	});
	const update = await conn3.command("client.update", { metadata: { version: "0.1.0-fixture", platform: "android", pid: 0, note: "after c2 closed" } });
	rec.note("client.update on c3 after c2 closed", { ok: update.ok, error: update.error });
	const reRegister = await conn3.command("client.register", registerPayload(CLIENT_ID));
	rec.note("client.register again on the same connection", { ok: reRegister.ok, error: reRegister.error });
	await conn3.close();
	state.conn = undefined;
}

async function scenario05(): Promise<void> {
	console.log("== 05-resume-after-restart");
	const rec = new Recorder("05-resume-after-restart.jsonl");
	const sessionId = state.session1!;
	const before = readDiscovery();
	const probe = new HubConnection(rec, "c4", CLIENT_ID);
	await probe.open({ token: state.token });
	await probe.command("client.register", registerPayload(CLIENT_ID));
	const status = await probe.command("hub.status", {});
	state.lastSequenceBeforeRestart = status.payload?.eventLog?.lastSequence ?? probe.lastSequence;
	rec.note("state before restart", { pid: before.pid, startedAt: before.startedAt, hubId: before.hubId, eventLogLastSequence: state.lastSequenceBeforeRestart });
	await probe.close();

	const stopStartedAt = performance.now();
	await runCli(rec, ["hub", "stop"], "cline hub stop");
	let healthAfterStop: { status: number; body: unknown } | string = "connection refused";
	for (let i = 0; i < 50; i += 1) {
		try {
			healthAfterStop = await fetchJson("/health");
			await sleep(200);
		} catch {
			healthAfterStop = "connection refused";
			break;
		}
	}
	rec.note("hub endpoint after stop", {
		msSinceStopIssued: Math.round(performance.now() - stopStartedAt),
		health: healthAfterStop,
		discoveryFileExists: existsSync(DISCOVERY_PATH),
	});
	const started = await runCli(rec, ["hub", "start"], "cline hub start");
	const after = readDiscovery();
	const oldToken = state.token;
	state.token = after.authToken;
	redactTokens = [oldToken, state.token];
	rec.note("new discovery record after restart", {
		pid: after.pid,
		startedAt: after.startedAt,
		hubId: after.hubId,
		tokenChanged: oldToken !== after.authToken,
		hubStartLastStdoutLine: started.stdout.trim().split("\n").pop(),
	});
	rec.note("GET /version after restart", await fetchJson("/version"));

	const conn = new HubConnection(rec, "c5", CLIENT_ID);
	await conn.open({ token: state.token });
	await conn.command("client.register", registerPayload(CLIENT_ID));
	const replayMark = conn.mark();
	conn.subscribe({ sinceSequence: state.lastSequenceBeforeRestart });
	await sleep(1_500);
	const replayed = conn.events.slice(replayMark);
	rec.note("global replay across the restart (cursor from before the stop)", {
		sinceSequence: state.lastSequenceBeforeRestart,
		replayedSequences: replayed.map((event) => event.sequence),
		replayedEvents: replayed.map((event) => event.event),
	});
	const statusAfter = await conn.command("hub.status", {});
	rec.note("hub.status after restart", { eventLog: statusAfter.payload?.eventLog, hubId: statusAfter.payload?.hubId });
	const list = await conn.command("session.list", { limit: 50, rootOnly: true });
	rec.note("session.list after restart", {
		sessions: (list.payload?.sessions ?? []).map((session: Frame) => ({ sessionId: session.sessionId, status: session.status, title: session.metadata?.title, pinned: session.metadata?.pinned })),
	});
	const staleSend = await conn.command("session.send_input", { prompt: "Are you there?" }, { sessionId, timeoutMs: null, localTimeoutMs: 60_000 });
	rec.note("session.send_input to the not-yet-resumed session", { ok: staleSend.ok, error: staleSend.error });
	const attach = await conn.command("session.attach", { sessionId }, { sessionId });
	rec.note("session.attach to the persisted (not live) session", { ok: attach.ok, status: attach.payload?.session?.status, error: attach.error });
	const messagesReply = await conn.command("session.messages", { sessionId }, { sessionId });
	const messages = messagesReply.payload?.messages ?? [];
	rec.note("session.messages after restart (used as initialMessages)", { count: messages.length });
	const resumed = await conn.command(
		"session.create",
		sessionCreatePayload({ initialMessages: messages }, { sessionId }),
		{ timeoutMs: 120_000, localTimeoutMs: 150_000 },
	);
	rec.note("session.create resume reply", {
		ok: resumed.ok,
		error: resumed.error,
		sessionId: resumed.payload?.session?.sessionId,
		status: resumed.payload?.session?.status,
		usage: resumed.payload?.session?.usage,
		aggregateUsage: resumed.payload?.session?.aggregateUsage,
		snapshotRunCount: resumed.payload?.snapshot?.runCount,
		snapshotMessageCount: resumed.payload?.snapshot?.messages?.length,
		snapshotMetadataKeys: Object.keys(resumed.payload?.snapshot?.metadata ?? {}),
	});
	if (!resumed.ok) throw new Error("resume failed");
	conn.subscribe({ sessionId });
	await sleep(200);
	const mark = conn.mark();
	const runReply = conn.command(
		"session.send_input",
		{ prompt: "What did the command print? Answer in one line." },
		{ sessionId, timeoutMs: null, localTimeoutMs: 240_000 },
	);
	runReply.catch(() => undefined);
	const terminal = await conn.waitForEvent(mark, (event) => /^run\.(completed|failed|aborted)$/.test(event.event) && event.sessionId === sessionId, "run terminal event", 180_000);
	rec.note("terminal event for the resumed turn", { event: terminal.event, reason: terminal.payload?.reason, text: terminal.payload?.result?.text });
	const reply = await runReply;
	rec.note("send_input reply for the resumed turn", { ok: reply.ok, finishReason: reply.payload?.result?.finishReason, usage: reply.payload?.result?.usage });
	await sleep(1_000);
	await conn.command("session.get", { sessionId, includeSnapshot: true }, { sessionId });
	const searchAfterRestart = await conn.command("session.search", { query: "hello", limit: 20 });
	rec.note("session.search after restart and a second turn", { hits: (searchAfterRestart.payload?.hits ?? []).length });
	const finalMessages = await conn.command("session.messages", { sessionId }, { sessionId });
	rec.note("session.messages after the resumed turn", {
		count: finalMessages.payload?.messages?.length,
		roles: (finalMessages.payload?.messages ?? []).map((message: Frame) => message.role),
	});
	await conn.close();
}

async function scenario06(): Promise<void> {
	console.log("== 06-auth-negative");
	const rec = new Recorder("06-auth-negative.jsonl");
	rec.note("Hub was started with --cwd = the fork checkout (cline-isolated.sh cd's there); the fixture workspace is a different directory", {
		fixtureWorkspace: WORKSPACE,
	});
	rec.note("A: upgrade with no Sec-WebSocket-Protocol and no Origin", await rawUpgrade("/hub", []));
	rec.note("B: upgrade with a wrong token", await rawUpgrade("/hub", [`Sec-WebSocket-Protocol: ${PROTOCOL_PREFIX}${"0".repeat(64)}`]));
	rec.note("C: upgrade with a correct token on the wrong path (/nothub)", await rawUpgrade("/nothub", [`Sec-WebSocket-Protocol: ${PROTOCOL_PREFIX}${state.token}`]));
	rec.note("D: upgrade with Origin: http://localhost and no token", await rawUpgrade("/hub", ["Origin: http://localhost"]));
	rec.note("E: upgrade with Origin: http://evil.example and no token", await rawUpgrade("/hub", ["Origin: http://evil.example"]));
	rec.note("F: upgrade with the correct token (control)", await rawUpgrade("/hub", [`Sec-WebSocket-Protocol: ${PROTOCOL_PREFIX}${state.token}`]));
	rec.note("G: GET /status without Authorization", await fetchJson("/status"));
	const statusWithBearer = await fetchJson("/status", { authorization: `Bearer ${state.token}` });
	rec.note("H: GET /status with Authorization: Bearer <token>", {
		status: statusWithBearer.status,
		bodyKeys: statusWithBearer.body && typeof statusWithBearer.body === "object" ? Object.keys(statusWithBearer.body as object) : statusWithBearer.body,
	});

	// Origin-only connection: reduced authority.
	const originConn = new HubConnection(rec, "origin-only", `${CLIENT_ID}-origin`);
	await originConn.open({ headers: { Origin: "http://localhost" } });
	const withWorkspace = await originConn.command("client.register", registerPayload(`${CLIENT_ID}-origin`, true));
	rec.note("I: origin-only register WITH workspaceContext pointing at the fixture workspace", { ok: withWorkspace.ok, error: withWorkspace.error });
	const withoutWorkspace = await originConn.command("client.register", registerPayload(`${CLIENT_ID}-origin`, false));
	rec.note("J: origin-only register WITHOUT workspaceContext", { ok: withoutWorkspace.ok, error: withoutWorkspace.error });
	if (withoutWorkspace.ok) {
		const list = await originConn.command("session.list", { limit: 5, rootOnly: true });
		rec.note("K: origin-only session.list", { ok: list.ok, count: list.payload?.sessions?.length });
		const create = await originConn.command("session.create", sessionCreatePayload(), { timeoutMs: 120_000, localTimeoutMs: 150_000 });
		rec.note("L: origin-only session.create in the fixture workspace", { ok: create.ok, error: create.error, sessionId: create.payload?.session?.sessionId });
		if (create.ok) {
			const createdId = create.payload?.session?.sessionId;
			const deleted = await originConn.command("session.delete", { sessionId: createdId }, { sessionId: createdId });
			rec.note("M: session.delete of that session", { ok: deleted.ok, payload: deleted.payload });
			const afterDelete = await originConn.command("session.list", { limit: 20, rootOnly: true });
			const getDeleted = await originConn.command("session.get", { sessionId: createdId }, { sessionId: createdId });
			rec.note("M2: session.list / session.get after the delete", {
				stillListed: (afterDelete.payload?.sessions ?? []).some((session: Frame) => session.sessionId === createdId),
				getOk: getDeleted.ok,
				getError: getDeleted.error,
			});
		}
		const schedules = await originConn.command("schedule.list", {});
		rec.note("N: origin-only schedule.list (needs registered workspace authority)", { ok: schedules.ok, error: schedules.error, count: schedules.payload?.schedules?.length });
	}
	await originConn.close();

	const tokenConn = new HubConnection(rec, "token", `${CLIENT_ID}-token`);
	await tokenConn.open({ token: state.token });
	const schedulesToken = await tokenConn.command("client.register", registerPayload(`${CLIENT_ID}-token`, true));
	rec.note("O: token-auth register with the fixture workspace (control)", { ok: schedulesToken.ok });
	const scheduleList = await tokenConn.command("schedule.list", {});
	rec.note("P: token-auth schedule.list", { ok: scheduleList.ok, error: scheduleList.error, count: scheduleList.payload?.schedules?.length });
	const mismatch = await tokenConn.command("hub.status", {}, { clientId: "someone-else" });
	rec.note("Q: command with a clientId that does not belong to this connection", { ok: mismatch.ok, error: mismatch.error });
	const unknown = await tokenConn.command("session.frobnicate", {});
	rec.note("R: unknown command name", { ok: unknown.ok, error: unknown.error });
	await tokenConn.close();
}

// ── 07: the app's split checkpoint restore (CheckpointActions.kt, CheckpointIndex.kt) ──────────

/** `SYNTHETIC_USER_MESSAGE_KINDS` of checkpoint-restore.ts / CheckpointIndex.kt. */
const SYNTHETIC_USER_MESSAGE_KINDS = new Set([
	"auto_compaction",
	"compaction_budget_emergency",
	"completion_reminder",
	"loop_detection_notice",
	"manual_compaction",
	"mistake_stop_notice",
	"recovery_notice",
]);

/**
 * `getUserRunSpan`, simplified for the mock's plain prompts: a user message with visible text
 * (tool results are user-role messages without it) is one run; stored spans and the synthetic
 * kinds are honoured. The `<user_input>` unwrapping of `formatDisplayUserInput` is skipped.
 */
function userRunSpan(message: Frame): number {
	if (message?.role !== "user") return 0;
	const metadata = message.metadata && typeof message.metadata === "object" ? message.metadata : undefined;
	const kind = typeof metadata?.kind === "string" ? metadata.kind : undefined;
	const stored = Number.isInteger(metadata?.userRunSpan) && metadata.userRunSpan >= 0 ? (metadata.userRunSpan as number) : undefined;
	if (kind === "compaction" || kind === "compaction_summary") return stored ?? 1;
	if (stored !== undefined) return stored;
	if (kind && SYNTHETIC_USER_MESSAGE_KINDS.has(kind)) return 0;
	let text = "";
	let attachment = false;
	let toolResult = false;
	if (typeof message.content === "string") {
		text = message.content;
	} else if (Array.isArray(message.content)) {
		for (const block of message.content) {
			if (block?.type === "text" && typeof block.text === "string") text += (text ? "\n" : "") + block.text;
			else if (block?.type === "tool_result" || block?.type === "tool-result") toolResult = true;
			else if (block?.type === "file" || block?.type === "image") attachment = true;
		}
	} else {
		text = "unknown";
	}
	const visible = attachment && !toolResult;
	if (!visible && text.trim().length === 0) return 0;
	if (!visible && text.trim().startsWith("[TASK RESUMPTION]")) return 0;
	return 1;
}

/** `trimMessagesToCheckpoint` (C1 revert): everything up to and including run [runCount]'s user message. */
function trimMessagesToCheckpoint(messages: Frame[], runCount: number): Frame[] {
	let count = 0;
	for (let index = 0; index < messages.length; index += 1) {
		const span = userRunSpan(messages[index]);
		if (span < 1) continue;
		count += span;
		if (count === runCount) return messages.slice(0, index + 1);
		if (count > runCount) throw new Error(`Run ${runCount} is folded into a compacted message`);
	}
	throw new Error(`Could not find user message for run ${runCount}`);
}

/** `HubPayloads.filteredCheckpoints`: `{latest, history}` with only the entries up to [maxRunCount]. */
function filteredCheckpoints(checkpoint: Frame | undefined, maxRunCount: number): Frame | undefined {
	if (!checkpoint || typeof checkpoint !== "object") return undefined;
	const normalize = (entry: any): Frame | undefined => {
		if (!entry || typeof entry !== "object") return undefined;
		const ref = typeof entry.ref === "string" ? entry.ref.trim() : "";
		const createdAt =
			entry.createdAt === undefined || entry.createdAt === null || String(entry.createdAt).trim() === ""
				? Number.NaN
				: Number(entry.createdAt);
		const runCount = Number.isInteger(entry.runCount) && entry.runCount >= 1 ? (entry.runCount as number) : undefined;
		if (!ref || !Number.isFinite(createdAt) || runCount === undefined) return undefined;
		const kind = entry.kind === "stash" || entry.kind === "commit" ? entry.kind : undefined;
		return { ref, createdAt, runCount, ...(kind ? { kind } : {}) };
	};
	const history = (Array.isArray(checkpoint.history) ? checkpoint.history : [])
		.map(normalize)
		.filter((entry): entry is Frame => entry !== undefined && entry.runCount <= maxRunCount);
	const latestEntry = normalize(checkpoint.latest);
	const latest =
		latestEntry && latestEntry.runCount <= maxRunCount
			? latestEntry
			: history.reduce<Frame | undefined>((best, entry) => (!best || entry.runCount > best.runCount ? entry : best), undefined);
	if (!latest && history.length === 0) return undefined;
	return { ...(latest ? { latest } : {}), history };
}

function gitInWorkspace(args: string[]): { code: number; stdout: string; stderr: string } {
	const proc = Bun.spawnSync(["git", "-C", WORKSPACE, ...args], { stdout: "pipe", stderr: "pipe" });
	return { code: proc.exitCode ?? -1, stdout: proc.stdout.toString(), stderr: proc.stderr.toString() };
}

function mockEditLines(): number {
	const path = resolve(WORKSPACE, "hello.js");
	if (!existsSync(path)) return -1;
	return readFileSync(path, "utf8").split("\n").filter((line) => line.includes("edited by the mock")).length;
}

/** One run with auto-approve; an approval that still shows up (a policy mismatch) is approved. */
async function runTurn(conn: HubConnection, rec: Recorder, sessionId: string, prompt: string, label: string): Promise<void> {
	const mark = conn.mark();
	const runReply = conn.command("session.send_input", { prompt }, { sessionId, timeoutMs: null, localTimeoutMs: 240_000 });
	runReply.catch(() => undefined);
	let cursor = mark;
	for (;;) {
		const event = await conn.waitForEvent(
			cursor,
			(e) => e.sessionId === sessionId && (e.event === "approval.requested" || /^run\.(completed|failed|aborted)$/.test(e.event)),
			`${label}: approval or terminal event`,
			180_000,
		);
		cursor = conn.events.indexOf(event) + 1;
		if (event.event === "approval.requested") {
			rec.note(`${label}: unexpected approval.requested (auto-approve was set); approving`, { toolName: event.payload?.toolName });
			await conn.command("approval.respond", { approvalId: event.payload.approvalId, approved: true });
			continue;
		}
		rec.note(`${label}: terminal run event`, { event: event.event, sequence: event.sequence, mockEditLines: mockEditLines() });
		break;
	}
	const reply = await runReply;
	rec.note(`${label}: send_input reply`, { ok: reply.ok, finishReason: reply.payload?.result?.finishReason });
}

/**
 * Opt-in (`--only 07`): needs `--workspace` to be a clean git repository under /tmp with the
 * mock's `hello.js`, the same directory the mock's `--workspace` names (dev-hub.sh's default
 * `/tmp/cline-dev-hub/workspace`), because `mock-edit` edits `<mock workspace>/hello.js`.
 * Two `mock-edit` runs with auto-approve, the checkpoint history, then the app's split restore
 * (CheckpointActions `RestorePath.Split`, C1 revert at run 1 with "restore files"): a
 * workspace-only `session.restore`, `session.messages` trimmed to run 1, the restored
 * `session.create`, `session.detach` of the source. The workspace is reset at the end.
 */
async function scenario07(): Promise<void> {
	console.log("== 07-checkpoint-restore");
	if (!WORKSPACE.startsWith("/tmp/")) throw new Error(`scenario 07 resets --workspace; it must live under /tmp (got ${WORKSPACE})`);
	const top = gitInWorkspace(["rev-parse", "--show-toplevel"]);
	if (top.code !== 0 || resolve(top.stdout.trim()) !== resolve(WORKSPACE)) throw new Error(`scenario 07 needs --workspace to be the root of a git repository (${WORKSPACE})`);
	if (gitInWorkspace(["status", "--porcelain"]).stdout.trim() !== "") throw new Error(`scenario 07 needs a clean workspace; commit or reset ${WORKSPACE} first`);
	if (mockEditLines() < 0) throw new Error(`scenario 07 needs ${WORKSPACE}/hello.js (the mock-edit target)`);

	const rec = new Recorder("07-checkpoint-restore.jsonl");
	const conn = new HubConnection(rec, "c7", CLIENT_ID);
	try {
		await conn.open({ token: state.token });
		await conn.command("client.register", registerPayload(CLIENT_ID));
		const created = await conn.command(
			"session.create",
			sessionCreatePayload({ toolPolicies: { "*": { autoApprove: true } } }),
			{ timeoutMs: 120_000, localTimeoutMs: 150_000 },
		);
		const sessionId = created.payload?.session?.sessionId ?? created.payload?.snapshot?.sessionId;
		if (!sessionId) throw new Error(`session.create failed: ${JSON.stringify(created.error)}`);
		rec.note("source session created with toolPolicies {*: autoApprove true}", { sessionId, mockEditLines: mockEditLines() });
		conn.subscribe({ sessionId });
		await sleep(200);
		await runTurn(conn, rec, sessionId, "Please mock-edit hello.js (run 1)", "run 1");
		await runTurn(conn, rec, sessionId, "Please mock-edit hello.js once more (run 2)", "run 2");
		await sleep(1_000);

		const record = await conn.command("session.get", { sessionId, includeSnapshot: true }, { sessionId });
		const session = record.payload?.session;
		const checkpoint = session?.metadata?.checkpoint ?? record.payload?.snapshot?.checkpoint;
		const history: Frame[] = Array.isArray(checkpoint?.history) ? checkpoint.history : [];
		rec.note("checkpoint history after two runs (CheckpointActions reads metadata.checkpoint, else snapshot.checkpoint)", {
			from: session?.metadata?.checkpoint ? "session.metadata.checkpoint" : "snapshot.checkpoint",
			latestRunCount: checkpoint?.latest?.runCount,
			history: history.map((entry) => ({ runCount: entry.runCount, kind: entry.kind, refLength: typeof entry.ref === "string" ? entry.ref.length : null })),
		});
		if (!history.some((entry) => entry.runCount === 1)) throw new Error("no checkpoint for run 1; nothing to restore");

		// Split path step 1: files only (HubPayloads.sessionRestoreWorkspaceOnly).
		const workspaceOnly = await conn.command(
			"session.restore",
			{ sessionId, checkpointRunCount: 1, restore: { messages: false, workspace: true } },
			{ sessionId, timeoutMs: 120_000, localTimeoutMs: 130_000 },
		);
		rec.note("session.restore workspace-only at run 1", {
			ok: workspaceOnly.ok,
			error: workspaceOnly.error,
			payloadKeys: Object.keys(workspaceOnly.payload ?? {}),
			mockEditLines: mockEditLines(),
			gitStatus: gitInWorkspace(["status", "--porcelain"]).stdout.trim().split("\n").filter(Boolean),
		});
		if (!workspaceOnly.ok) throw new Error("workspace-only restore failed");

		// Step 2: the transcript trimmed to run 1 (trimMessagesToCheckpoint) and a restored create.
		const messagesReply = await conn.command("session.messages", { sessionId }, { sessionId });
		const messages: Frame[] = messagesReply.payload?.messages ?? [];
		const trimmed = trimMessagesToCheckpoint(messages, 1);
		rec.note("transcript split at run 1 (C1 keeps run 1's user message)", {
			sourceCount: messages.length,
			sourceRoles: messages.map((message) => message.role),
			keptCount: trimmed.length,
			runSpans: messages.map(userRunSpan),
		});
		const metadata: Frame = {
			source: "android",
			interactive: true,
			provider: PROVIDER_ID,
			model: MODEL_ID,
			restoredFromSessionId: sessionId,
			restoredCheckpointRunCount: 1,
		};
		const kept = filteredCheckpoints(checkpoint, 1);
		if (kept) metadata.checkpoint = kept;
		const restoredCreate = await conn.command(
			"session.create",
			sessionCreatePayload(
				{
					initialMessages: trimmed,
					metadata,
					runtimeOptions: { clientContributions: [{ kind: "toolExecutor", executor: "askQuestion", capabilityName: "tool_executor.askQuestion" }] },
				},
				{ cwd: WORKSPACE, workspaceRoot: WORKSPACE, enableSpawnAgent: true, enableAgentTeams: true },
			),
			{ timeoutMs: 120_000, localTimeoutMs: 150_000 },
		);
		const restoredId = restoredCreate.payload?.session?.sessionId ?? restoredCreate.payload?.snapshot?.sessionId;
		rec.note("restored session.create", {
			ok: restoredCreate.ok,
			error: restoredCreate.error,
			sessionId: restoredId,
			status: restoredCreate.payload?.session?.status,
			metadataKeys: Object.keys(restoredCreate.payload?.session?.metadata ?? {}),
		});
		if (!restoredId) throw new Error("restored session.create failed");
		const detached = await conn.command("session.detach", { sessionId }, { sessionId });
		rec.note("session.detach of the source", { ok: detached.ok, error: detached.error });
		const restoredMessages = await conn.command("session.messages", { sessionId: restoredId }, { sessionId: restoredId });
		rec.note("session.messages of the restored session", {
			count: restoredMessages.payload?.messages?.length,
			roles: (restoredMessages.payload?.messages ?? []).map((message: Frame) => message.role),
		});
	} finally {
		await conn.close().catch(() => undefined);
		const checkout = gitInWorkspace(["checkout", "--", "."]);
		const clean = gitInWorkspace(["clean", "-fd"]);
		const status = gitInWorkspace(["status", "--porcelain"]).stdout.trim();
		console.log(`  workspace reset: checkout ${checkout.code}, clean ${clean.code}, ${status ? "still dirty" : "clean"}`);
	}
}

// ── main ──────────────────────────────────────────────────────────────────────

const only = argValue("--only", "");
const scenarios: Array<[string, () => Promise<void>]> = [
	["01", scenario01],
	["02", scenario02],
	["03", scenario03],
	["04", scenario04],
	["05", scenario05],
	["06", scenario06],
	["07", scenario07],
];
/** Scenarios that run only when `--only` names them (07 needs a git workspace and resets it). */
const OPT_IN = new Set(["07"]);

try {
	for (const [id, run] of scenarios) {
		if (only ? !only.split(",").includes(id) : OPT_IN.has(id)) continue;
		await run();
	}
	console.log(`done; fixtures in ${OUT_DIR}`);
	process.exit(0);
} catch (error) {
	console.error("recording failed:", error);
	process.exit(1);
}
