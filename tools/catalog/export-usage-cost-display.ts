#!/usr/bin/env bun
/**
 * Generates `app/src/main/res/raw/provider_usage_cost.json`: which providers the kernel says
 * not to show a usage cost for. The terminal's verbose line (A11.9) shows a turn's cost only
 * when `shouldShowCliUsageCost(providerId)` is true (apps/cli/src/utils/usage-cost-display.ts →
 * `@cline/llms` `shouldShowProviderUsageCost`), i.e. when the provider's
 * `metadata.usageCostDisplay` resolves to "show". Subscription providers (openai-codex,
 * openai-codex-cli, Cline Pass in cli-v3.0.69) price their turns from a per-token catalog, so
 * the run result carries a cost the user does not pay; the app hides it like the terminal.
 *
 *   {schema: 1, generatedAt, source, providers: {"<id>": "hide" | "subscription"}}
 *
 * Only the providers whose value is not "show" are listed (the SDK's default for a missing or
 * unknown value is "show"). Every value comes from the SDK's own
 * `resolveProviderUsageCostDisplay`, never typed by hand. Run from anywhere:
 *
 *   CLINE_FORK_ROOT=~/workspace/cline-termux-v3-phone bun tools/catalog/export-usage-cost-display.ts
 *
 * It imports the fork's `@cline/llms` TypeScript source (no `build:sdk` needed) and falls back
 * to `dist/index.js` only when that import fails. The fork is read, never written.
 *
 * A separate file from `fallback_providers.json` on purpose: work package P2 regenerates that
 * bundle (schema 2, provider capabilities) in parallel; folding this map into it is a closeout
 * step once both are merged.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const forkRoot = resolve(process.env.CLINE_FORK_ROOT ?? join(process.env.HOME ?? "", "workspace/cline-termux-v3-phone"));
const here = dirname(fileURLToPath(import.meta.url));
const outFile = resolve(process.env.USAGE_COST_OUT ?? join(here, "../../app/src/main/res/raw/provider_usage_cost.json"));

let llmsEntry = "sdk/packages/llms/src/index.ts";
let llms: Record<string, any>;
try {
	llms = await import(join(forkRoot, llmsEntry));
} catch (error) {
	console.warn(`source import failed (${(error as Error).message}); falling back to dist/index.js`);
	llmsEntry = "sdk/packages/llms/dist/index.js";
	llms = await import(join(forkRoot, llmsEntry));
}
const Models = (llms.Models ?? llms.default?.Models ?? llms) as Record<string, any>;
const getProviderIds: () => string[] = Models.getProviderIds ?? llms.getProviderIds;
const resolveDisplay: (id: string) => string = llms.resolveProviderUsageCostDisplay ?? Models.resolveProviderUsageCostDisplay;
if (!getProviderIds || !resolveDisplay) {
	throw new Error("@cline/llms does not expose getProviderIds/resolveProviderUsageCostDisplay");
}

const providers: Record<string, string> = {};
for (const id of [...getProviderIds()].sort()) {
	const display = resolveDisplay(id);
	if (display !== "show") providers[id] = display;
}

const cliVersion = await Bun.file(join(forkRoot, "apps/cli/package.json"))
	.json()
	.then((pkg: { version?: unknown }) => (typeof pkg.version === "string" ? pkg.version : null))
	.catch(() => null);
const out = {
	schema: 1,
	generatedAt: new Date().toISOString(),
	source: { package: "@cline/llms", entry: llmsEntry, cliVersion, forkRoot: forkRoot.replace(process.env.HOME ?? "", "~") },
	providers,
};
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, `${JSON.stringify(out, null, 1)}\n`);
console.log(`wrote ${outFile}: ${Object.keys(providers).length} providers without a shown cost (${Object.keys(providers).join(", ")}), cli ${cliVersion ?? "unknown"}`);
