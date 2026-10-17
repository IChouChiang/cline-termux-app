#!/usr/bin/env bun
/**
 * Generates `app/src/main/res/raw/fallback_providers.json`, the provider list the app
 * uses when the settings helper (the dashboard) is not running (settings-providers.md
 * §0.4). One row per provider id the kernel's registry knows, in the order the
 * dashboard's `listLocalProviders` sorts them (popular rank, then name, then id):
 *
 *   {id, name, popular, authKind: "oauth" | "local" | "api-key", supportsBaseUrl, defaultModelId, capabilities}
 *
 * `authKind` is the desktop's `getProviderAuthKind` (lib/provider-connection.ts):
 * "oauth" when the provider has the `oauth` capability or is one of the OAuth ids,
 * "local" for `local-auth`, else "api-key". `supportsBaseUrl` is true only where
 * `cline auth --baseurl` is accepted ("base URL is only supported for OpenAI and
 * OpenAI-compatible providers", apps/cli/src/commands/auth.ts). `capabilities` is the
 * registry's provider capability array, sorted and deduplicated (schema 2, decision A2):
 * the app treats every chat model of a provider with `reasoning` as reasoning-capable
 * while the live catalog is unavailable. Values are copied from the registry, never typed
 * by hand. Run from anywhere:
 *
 *   CLINE_FORK_ROOT=~/workspace/cline-termux-v3-phone bun tools/catalog/export-fallback-providers.ts
 *
 * It imports the fork's `@cline/llms` TypeScript source (`sdk/packages/llms/src/index.ts`),
 * so no `build:sdk` is needed and a stale `dist` cannot leak into the bundle. Only when the
 * source import fails does it fall back to `sdk/packages/llms/dist/index.js` (then the fork's
 * SDK must be built for the checked-out tag), and it says so on stderr.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const forkRoot = resolve(process.env.CLINE_FORK_ROOT ?? join(process.env.HOME ?? "", "workspace/cline-termux-v3-phone"));
const here = dirname(fileURLToPath(import.meta.url));
const outFile = resolve(process.env.FALLBACK_OUT ?? join(here, "../../app/src/main/res/raw/fallback_providers.json"));

/** `source.entry` in the output: which `@cline/llms` entry the rows came from. */
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
const getProvider: (id: string) => Promise<any> = Models.getProvider ?? llms.getProvider;
if (!getProviderIds || !getProvider) {
	throw new Error("@cline/llms does not expose getProviderIds/getProvider");
}

/** `OAUTH_PROVIDER_IDS` (desktop lib/provider-connection.ts). */
const OAUTH_PROVIDER_IDS = new Set(["cline", "cline-pass", "oca", "openai-codex"]);
/** Providers `cline auth --baseurl` accepts. */
const BASE_URL_PROVIDER_IDS = new Set(["openai-native", "openai-compatible"]);

function authKind(id: string, capabilities: string[]): "oauth" | "local" | "api-key" {
	if (capabilities.includes("oauth") || OAUTH_PROVIDER_IDS.has(id)) return "oauth";
	if (capabilities.includes("local-auth")) return "local";
	return "api-key";
}

/** `getPopularRank` (core local-provider-service.ts): metadata.popularRank, else last. */
function popularRank(metadata: Record<string, unknown> | undefined): number {
	const value = metadata?.popularRank;
	return typeof value === "number" && Number.isFinite(value) ? value : Number.MAX_SAFE_INTEGER;
}

const rows: Array<{ rank: number; row: Record<string, unknown> }> = [];
for (const id of getProviderIds()) {
	const info = await getProvider(id);
	const capabilities: string[] = Array.isArray(info?.capabilities)
		? [...new Set<string>(info.capabilities.filter((c: unknown): c is string => typeof c === "string"))].sort()
		: [];
	const metadata = (info?.metadata ?? {}) as Record<string, unknown>;
	const rank = popularRank(metadata);
	rows.push({
		rank,
		row: {
			id,
			name: info?.name ?? id,
			popular: capabilities.includes("popular") || rank !== Number.MAX_SAFE_INTEGER,
			authKind: authKind(id, capabilities),
			supportsBaseUrl: BASE_URL_PROVIDER_IDS.has(id),
			defaultModelId: typeof info?.defaultModelId === "string" && info.defaultModelId.trim() ? info.defaultModelId : null,
			capabilities,
		},
	});
}
rows.sort((a, b) => {
	if (a.rank !== b.rank) return a.rank - b.rank;
	const byName = String(a.row.name).localeCompare(String(b.row.name));
	return byName || String(a.row.id).localeCompare(String(b.row.id));
});

const defaults = await import(join(forkRoot, "sdk/packages/shared/src/providers/defaults.ts"));
/** The CLI version the fork is checked out at (`apps/cli/package.json`), for provenance. */
const cliVersion = await Bun.file(join(forkRoot, "apps/cli/package.json"))
	.json()
	.then((pkg: { version?: unknown }) => (typeof pkg.version === "string" ? pkg.version : null))
	.catch(() => null);
const out = {
	schema: 2,
	generatedAt: new Date().toISOString(),
	source: { package: "@cline/llms", entry: llmsEntry, cliVersion, forkRoot: forkRoot.replace(process.env.HOME ?? "", "~") },
	clineDefaultModelId: defaults.CLINE_DEFAULT_MODEL_ID ?? null,
	providers: rows.map((entry) => entry.row),
};
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, `${JSON.stringify(out, null, 1)}\n`);
const reasoning = out.providers.filter((p) => (p.capabilities as string[]).includes("reasoning")).length;
console.log(
	`wrote ${outFile}: ${out.providers.length} providers (${out.providers.filter((p) => p.popular).length} popular, ${reasoning} with reasoning) from ${llmsEntry}, cli ${cliVersion ?? "unknown"}`,
);
