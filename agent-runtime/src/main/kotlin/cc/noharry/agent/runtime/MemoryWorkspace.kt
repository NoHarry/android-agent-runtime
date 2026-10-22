package cc.noharry.agent.runtime

import java.io.File

internal class MemoryWorkspace(
    private val memoryRoot: File,
) {
    fun initialize() {
        memoryRoot.mkdirs()
        MemoryCategory.entries.forEach { category ->
            File(memoryRoot, category.directoryName).mkdirs()
        }
        val index = File(memoryRoot, MEMORY_INDEX_FILE)
        if (!index.exists()) {
            rebuildIndex()
        }
    }

    fun writeMemory(category: MemoryCategory, name: String, content: String): File {
        val safeName = safeMemoryFileName(name)
        val file = File(File(memoryRoot, category.directoryName), safeName)
        file.parentFile?.mkdirs()
        file.writeText(content)
        rebuildIndex()
        return file
    }

    fun rebuildIndex(): File {
        memoryRoot.mkdirs()
        val index = File(memoryRoot, MEMORY_INDEX_FILE)
        index.writeText(indexContent())
        return index
    }

    private fun indexContent(): String {
        val sections = MemoryCategory.entries.joinToString(separator = "\n\n") { category ->
            val entries = categoryFiles(category)
                .joinToString(separator = "\n") { file ->
                    val link = "${category.directoryName}/${file.name}"
                    "- [${file.nameWithoutExtension}]($link)\n  ${memoryPreview(file)}"
                }
                .ifBlank { "- _No entries._" }
            "## ${category.directoryName}\n\n$entries"
        }
        return "# Memory Index\n\n$sections\n"
    }

    private fun categoryFiles(category: MemoryCategory): List<File> =
        File(memoryRoot, category.directoryName)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.name }

    private fun memoryPreview(file: File): String =
        file.readLines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_MEMORY_PREVIEW_CHARS)
            .orEmpty()
}

internal enum class MemoryCategory(
    val directoryName: String,
) {
    USER("user"),
    FEEDBACK("feedback"),
    ROUTINES("routines"),
    REFERENCE("reference"),
    SESSION("session"),
}

private fun safeMemoryFileName(name: String): String {
    require(SAFE_MEMORY_NAME.matches(name)) { "Memory name contains unsafe characters." }
    return if (name.endsWith(".md")) name else "$name.md"
}

private val SAFE_MEMORY_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}(\\.md)?")
private const val MEMORY_INDEX_FILE = "MEMORY.md"
private const val MAX_MEMORY_PREVIEW_CHARS = 120
