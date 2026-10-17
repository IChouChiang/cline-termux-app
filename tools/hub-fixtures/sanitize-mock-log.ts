/**
 * Copies the mock provider's request log into the fixture directory with the
 * repeated system prompt collapsed and the workstation's home directory masked.
 *
 *   bun tools/hub-fixtures/sanitize-mock-log.ts <requests.jsonl> <mock-requests.jsonl>
 *
 * The first chat request keeps its full system prompt (it documents what the
 * runtime sends); later requests whose system prompt is byte-identical carry
 * a short marker instead, since the ~5 KB prompt is otherwise repeated per turn.
 * The tools array (the schema the runtime advertises) is kept in every request.
 */
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";

const [source, target] = process.argv.slice(2);
if (!source || !target) {
	console.error("usage: sanitize-mock-log.ts <source.jsonl> <target.jsonl>");
	process.exit(2);
}

const home = homedir();
const seenSystemPrompts = new Map<string, number>();
const lines = readFileSync(source, "utf8").split("\n").filter(Boolean);
const output: string[] = [];

for (const line of lines) {
	const entry = JSON.parse(line) as Record<string, any>;
	const body = entry.body;
	if (body && Array.isArray(body.messages)) {
		body.messages = body.messages.map((message: Record<string, any>) => {
			if (message.role !== "system" || typeof message.content !== "string") return message;
			const hash = createHash("sha256").update(message.content).digest("hex").slice(0, 16);
			const firstIndex = seenSystemPrompts.get(hash);
			if (firstIndex === undefined) {
				seenSystemPrompts.set(hash, entry.index);
				return message;
			}
			return {
				role: "system",
				content: `<identical to the system prompt of request ${firstIndex}: ${message.content.length} chars, sha256 ${hash}>`,
			};
		});
	}
	let serialized = JSON.stringify(entry);
	if (home) serialized = serialized.split(home).join("/home/<user>");
	output.push(serialized);
}

writeFileSync(target, `${output.join("\n")}\n`);
console.log(`wrote ${output.length} requests to ${target}`);
