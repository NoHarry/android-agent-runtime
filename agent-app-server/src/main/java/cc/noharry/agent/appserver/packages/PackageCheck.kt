package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.skills.checkCustomSkill

/**
 * A package that passed every check: its [manifest], all its [files] (signature.json among them when it came with
 * one), the [digests] of all but signature.json, and its publisher's key ([publisherKey], hex of its DER encoding),
 * null when it is unsigned.
 */
internal class CheckedPackage(
    val manifest: PackageManifest,
    val files: Map<String, ByteArray>,
    val digests: Map<String, String>,
    val publisherKey: String?,
) {
    val signed: Boolean get() = publisherKey != null

    /** signature.json as it is installed: the package's own, or the digests alone for one that came without. */
    fun signatureFile(): ByteArray = files[PackageFormat.SIGNATURE] ?: PackageSignature(digests).toJson().toByteArray(Charsets.UTF_8)

    /** The text of [path], which the checks found to be UTF-8. */
    fun text(path: String): String = packageText(path, files.getValue(path))

    /** rules.md's lines, without blank ones. */
    fun ruleLines(): List<String> = manifest.rules?.let { text(it).lines().map(String::trimEnd).filter(String::isNotBlank) }.orEmpty()

    /** A skill's SKILL.md. */
    fun skillText(skill: ManifestSkill): String = text("${skill.path}/${PackageFormat.SKILL_FILE}")

    /** A tool's script. */
    fun scriptText(tool: ManifestTool): String = text(tool.script)
}

/** The checks of a downloaded package, before anything of it is written: its zip, its signature, its manifest. */
internal fun checkPackage(bytes: ByteArray, serverVersion: String = PackageFormat.SERVER_VERSION): CheckedPackage =
    checkPackage(readPackageArchive(bytes), serverVersion)

/**
 * The checks of a package's [files] (from its zip, or read again from where it is installed): digests and signature
 * first, then the manifest, then what the manifest names. Unsigned and without signature.json, its digests are its own.
 */
internal fun checkPackage(files: Map<String, ByteArray>, serverVersion: String = PackageFormat.SERVER_VERSION): CheckedPackage {
    val manifestBytes = files[PackageFormat.MANIFEST] ?: refuse(PackageProblem.NO_MANIFEST)
    val signature = files[PackageFormat.SIGNATURE]?.let { PackageSignature.parse(packageText(PackageFormat.SIGNATURE, it)) }
        ?: PackageSignature(files.mapValues { sha256Hex(it.value) })
    val key = verifyPackageSignature(files, signature)
    val manifest = parseManifest(packageText(PackageFormat.MANIFEST, manifestBytes), serverVersion)
    checkPackageContents(manifest, files)
    return CheckedPackage(manifest, files, signature.digests, key)
}

/**
 * What the manifest names is there and as the format says: each skill's SKILL.md, rules.md within its cap, each
 * tool's script (and no script that no tool names), the rest.
 */
internal fun checkPackageContents(manifest: PackageManifest, files: Map<String, ByteArray>) {
    manifest.tools.firstOrNull { it.script !in files }?.let { refuse(PackageProblem.BAD_TOOL, "${it.script} is missing") }
    val named = manifest.tools.map { it.script }.toSet()
    files.keys.firstOrNull { it.startsWith(PackageFormat.SCRIPTS_DIR) && it !in named }?.let { refuse(PackageProblem.CARRIES_SCRIPTS, "$it: no tool names it") }
    manifest.skills.forEach { skill ->
        val path = "${skill.path}/${PackageFormat.SKILL_FILE}"
        val text = files[path]?.let { packageText(path, it) } ?: refuse(PackageProblem.BAD_SKILL, "$path is missing")
        val check = checkCustomSkill(text, emptySet())
        if (check.errors.isNotEmpty()) refuse(PackageProblem.BAD_SKILL, "$path: ${check.errors.first().code.name}")
        if (check.name != skill.name) refuse(PackageProblem.BAD_SKILL, "$path names ${check.name}")
    }
    manifest.rules?.let { path ->
        val text = files[path]?.let { packageText(path, it) } ?: refuse(PackageProblem.BAD_RULES, "$path is missing")
        val lines = text.trimEnd().lines()
        if (lines.size > PackageFormat.MAX_RULE_LINES || text.trimEnd().length > PackageFormat.MAX_RULE_CHARS) {
            refuse(PackageProblem.BAD_RULES, "${lines.size} lines, ${text.trimEnd().length} characters")
        }
    }
    manifest.icon?.let { path ->
        val icon = files[path] ?: refuse(PackageProblem.BAD_MANIFEST, "$path is missing")
        if (icon.size > PackageFormat.MAX_ICON_BYTES) refuse(PackageProblem.TOO_LARGE, path)
    }
}
