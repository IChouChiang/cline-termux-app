package dev.cline.termux.data.marketplace

import dev.cline.termux.data.customize.normalizeMatchValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/*
 * Port of `lib/marketplace.ts` (`fetchMarketplaceCatalog`'s parser and types) and the entry
 * matching of `marketplace-view.tsx` (customize-and-rest.md §3.8). Entries without id, type,
 * name, tagline, description or `install.command`, or with no `install.args`, are dropped.
 */

data class MarketplaceEnvVar(val name: String, val required: Boolean? = null, val description: String? = null, val url: String? = null)

data class MarketplaceAuthor(val name: String, val url: String? = null)

data class MarketplaceInstall(val args: List<String>, val command: String, val env: List<MarketplaceEnvVar>? = null, val notes: String? = null)

/** `MarketplaceEntry`. */
data class MarketplaceEntry(
    val id: String,
    val type: MarketplaceType,
    val name: String,
    val featured: Boolean? = null,
    val tagline: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val author: MarketplaceAuthor? = null,
    val homepage: String? = null,
    val repo: String? = null,
    val icon: String? = null,
    val license: String? = null,
    val verified: Boolean? = null,
    val install: MarketplaceInstall,
) {
    /** "<type>:<id>", the installed-keys format. */
    val key: String get() = entryKey(type, id)

    fun toListEntry(): MarketplaceListEntry =
        MarketplaceListEntry(id, type, name, tagline, description, tags, author?.name, featured == true)

    /** The parsed entry object, as the desktop sends it to `install_marketplace_entry` / `list_marketplace_installed_entries`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type.wire)
        put("name", name)
        featured?.let { put("featured", it) }
        put("tagline", tagline)
        put("description", description)
        put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
        author?.let { a -> put("author", buildJsonObject { put("name", a.name); a.url?.let { put("url", it) } }) }
        homepage?.let { put("homepage", it) }
        repo?.let { put("repo", it) }
        icon?.let { put("icon", it) }
        license?.let { put("license", it) }
        verified?.let { put("verified", it) }
        put(
            "install",
            buildJsonObject {
                put("args", buildJsonArray { install.args.forEach { add(JsonPrimitive(it)) } })
                put("command", install.command)
                install.env?.let { env ->
                    put(
                        "env",
                        buildJsonArray {
                            env.forEach { v ->
                                add(
                                    buildJsonObject {
                                        put("name", v.name)
                                        v.required?.let { put("required", it) }
                                        v.description?.let { put("description", it) }
                                        v.url?.let { put("url", it) }
                                    },
                                )
                            }
                        },
                    )
                }
                install.notes?.let { put("notes", it) }
            },
        )
    }

    /** Desktop `entryMatchValues`: id, name and install args, normalised. */
    fun matchValues(): Set<String> = (listOf(id, name) + install.args).map(::normalizeMatchValue).filter { it.isNotEmpty() }.toSet()

    /** Desktop `entryMatchesLocalItem` against a local item's `getLocalMarketplaceMatchValues`. */
    fun matchesLocal(localMatchValues: List<String>): Boolean {
        val values = matchValues()
        return localMatchValues.map(::normalizeMatchValue).filter { it.isNotEmpty() }.any { it in values }
    }
}

data class MarketplaceCounts(val total: Int, val plugins: Int, val skills: Int, val mcps: Int)

/** `MarketplaceCatalog`. */
data class MarketplaceCatalog(
    val version: Int,
    val generatedAt: String?,
    val baseUrl: String?,
    val counts: MarketplaceCounts,
    val tags: List<MarketplaceTagInfo>,
    val entries: List<MarketplaceEntry>,
) {
    val tagLabels: Map<String, String> get() = tags.associate { it.id to it.label }

    fun find(type: MarketplaceType, id: String): MarketplaceEntry? = entries.firstOrNull { it.type == type && it.id == id }

    fun find(key: String): MarketplaceEntry? = entries.firstOrNull { it.key == key }

    companion object {
        val EMPTY = MarketplaceCatalog(1, null, null, MarketplaceCounts(0, 0, 0, 0), emptyList(), emptyList())

        fun parse(data: JsonElement?): MarketplaceCatalog {
            val root = data as? JsonObject ?: JsonObject(emptyMap())
            val rawCounts = root["counts"] as? JsonObject ?: JsonObject(emptyMap())
            val tags = (root["tags"] as? JsonArray).orEmpty().mapNotNull { element ->
                val t = element as? JsonObject ?: return@mapNotNull null
                val id = t.string("id") ?: return@mapNotNull null
                val label = t.string("label") ?: return@mapNotNull null
                MarketplaceTagInfo(id, label, count(t["count"]))
            }
            val entries = (root["entries"] as? JsonArray).orEmpty().mapNotNull { parseEntry(it as? JsonObject ?: return@mapNotNull null) }
                .filter { it.install.args.isNotEmpty() }
            return MarketplaceCatalog(
                version = count(root["version"]).takeIf { it != 0 } ?: EMPTY.version,
                generatedAt = root.string("generatedAt"),
                baseUrl = root.string("baseUrl"),
                counts = MarketplaceCounts(
                    total = count(rawCounts["total"]).takeIf { it != 0 } ?: entries.size,
                    plugins = count(rawCounts["plugins"]),
                    skills = count(rawCounts["skills"]),
                    mcps = count(rawCounts["mcps"]),
                ),
                tags = tags,
                entries = entries,
            )
        }

        private fun parseEntry(c: JsonObject): MarketplaceEntry? {
            val install = c["install"] as? JsonObject ?: JsonObject(emptyMap())
            val id = c.string("id") ?: return null
            val type = MarketplaceType.parse(c.string("type")) ?: return null
            val name = c.string("name") ?: return null
            val tagline = c.string("tagline") ?: return null
            val description = c.string("description") ?: return null
            val command = install.string("command") ?: return null
            return MarketplaceEntry(
                id = id,
                type = type,
                name = name,
                featured = c.bool("featured"),
                tagline = tagline,
                description = description,
                tags = stringArray(c["tags"]),
                author = (c["author"] as? JsonObject)?.let { a -> a.string("name")?.let { MarketplaceAuthor(it, a.string("url")) } },
                homepage = c.string("homepage")?.ifEmpty { null },
                repo = c.string("repo")?.ifEmpty { null },
                icon = c.string("icon")?.ifEmpty { null },
                license = c.string("license")?.ifEmpty { null },
                verified = c.bool("verified"),
                install = MarketplaceInstall(
                    args = stringArray(install["args"]),
                    command = command,
                    env = parseEnv(install["env"]),
                    notes = install.string("notes"),
                ),
            )
        }

        private fun parseEnv(value: JsonElement?): List<MarketplaceEnvVar>? {
            val env = (value as? JsonArray ?: return null).mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val name = o.string("name") ?: return@mapNotNull null
                MarketplaceEnvVar(name, o.bool("required"), o.string("description"), o.string("url"))
            }
            return env.ifEmpty { null }
        }

        private fun stringArray(value: JsonElement?): List<String> =
            (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        private fun count(value: JsonElement?): Int =
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.toInt() ?: 0

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}
