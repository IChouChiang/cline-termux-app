#!/usr/bin/env bun
/**
 * Exports upstream's built-in provider + model catalog (@cline/llms) into the app
 * asset `app/src/main/assets/catalog/catalog.json`, so the model picker works
 * without the dashboard helper. Run from anywhere:
 *
 *   CLINE_FORK_ROOT=~/workspace/cline-termux-v3-phone bun tools/catalog/export-catalog.ts
 *
 * The fork's SDK packages must be built (`bun run build:sdk` in the fork).
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const forkRoot = resolve(process.env.CLINE_FORK_ROOT ?? join(process.env.HOME ?? "", "workspace/cline-termux-v3-phone"));
const here = dirname(fileURLToPath(import.meta.url));
const outFile = resolve(process.env.CATALOG_OUT ?? join(here, "../../app/src/main/assets/catalog/catalog.json"));

const llms = await import(join(forkRoot, "sdk/packages/llms/dist/index.js"));
const Models = (llms.Models ?? llms.default?.Models ?? llms) as Record<string, any>;

type ReasoningOption = { type: string; values?: unknown[]; min?: number; max?: number; default?: unknown };

const getProviderIds: () => string[] = Models.getProviderIds ?? llms.getProviderIds;
const getProvider: (id: string) => Promise<any> = Models.getProvider ?? llms.getProvider;
const getModelsForProvider: (id: string) => Promise<Record<string, any>> = Models.getModelsForProvider ?? llms.getModelsForProvider;
if (!getProviderIds || !getProvider || !getModelsForProvider) {
	console.error("llms exports:", Object.keys(llms).slice(0, 60).join(", "));
	throw new Error("@cline/llms does not expose getProviderIds/getProvider/getModelsForProvider");
}

// Featured (Recommended / Cline Pass / Free / Cloud) tiers: upstream's bundled
// fallback for the live feed, generated next to the model catalog. Bun runs the
// TS source directly; its only import is a type.
let featured: Record<string, unknown> | null = null;
try {
	const generated = await import(join(forkRoot, "sdk/packages/llms/src/catalog/cline-recommended.generated.ts"));
	featured = generated.GENERATED_CLINE_RECOMMENDED_MODELS ?? null;
} catch (error) {
	console.warn("featured tiers unavailable:", (error as Error).message);
}

const providers = [];
for (const id of getProviderIds()) {
	const info = await getProvider(id);
	const models = await getModelsForProvider(id);
	const metadata = (info?.metadata ?? {}) as Record<string, unknown>;
	providers.push({
		id,
		name: info?.name ?? id,
		description: info?.description,
		docsUrl: info?.docsUrl,
		baseUrl: info?.baseUrl,
		defaultModelId: info?.defaultModelId,
		protocol: info?.protocol,
		client: info?.client,
		capabilities: info?.capabilities ?? [],
		env: info?.env ?? [],
		popularRank: typeof metadata.popularRank === "number" ? metadata.popularRank : null,
		authKind: metadata.authKind ?? metadata.auth ?? null,
		configFields: Array.isArray(metadata.configFields) ? metadata.configFields : null,
		models: Object.values(models ?? {})
			.map((m: any) => ({
				id: m.id,
				name: m.name ?? m.id,
				contextWindow: m.contextWindow ?? null,
				maxTokens: m.maxTokens ?? null,
				maxInputTokens: m.maxInputTokens ?? null,
				capabilities: m.capabilities ?? [],
				reasoningOptions: (m.reasoningOptions ?? []) as ReasoningOption[],
				pricing: m.pricing ?? null,
				family: m.family ?? null,
				releaseDate: m.releaseDate ?? null,
				deprecated: m.deprecated ?? false,
			}))
			.sort((a: any, b: any) => String(b.releaseDate ?? "").localeCompare(String(a.releaseDate ?? "")) || a.id.localeCompare(b.id)),
	});
}
providers.sort((a, b) => (a.popularRank ?? 1e9) - (b.popularRank ?? 1e9) || a.name.localeCompare(b.name));

const out = {
	schema: 1,
	generatedAt: new Date().toISOString(),
	source: { package: "@cline/llms", forkRoot: forkRoot.replace(process.env.HOME ?? "", "~"), modelsVersion: llms.getGeneratedModelsVersion?.() ?? null },
	featured,
	providers,
};
mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, JSON.stringify(out));
const totalModels = providers.reduce((n, p) => n + p.models.length, 0);
console.log(`wrote ${outFile}: ${providers.length} providers, ${totalModels} models, ${(JSON.stringify(out).length / 1024).toFixed(0)} KB${featured ? ", featured tiers included" : ", no featured tiers"}`);
