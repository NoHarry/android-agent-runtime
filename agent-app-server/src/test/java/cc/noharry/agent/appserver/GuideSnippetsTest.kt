package cc.noharry.agent.appserver

import cc.noharry.agent.appserver.protocol.AppServerMethods
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host guide (`agent-app-server/README.md`) shows only code that compiles: each code block after a line
 * `<!-- quote: <file> <region> -->` is that file's lines between its `guide:start <region>` and `guide:end <region>`
 * (the samples', which build with every check; the marker lines of regions inside it left out), and every Kotlin block
 * has such a line or a `<!-- published: <file> <region> -->` one: that region as an app outside this repository writes
 * it, each `project(":<module>")` the published `"cc.noharry:<module>"`, with at most one line more, the BOM at the
 * version this repository releases. Every protocol method it and the package tool's README name (`turn/start`,
 * `"method": "plugins/install"`) is one the protocol has.
 */
class GuideSnippetsTest {
    private val android = File("..").canonicalFile

    @Test
    fun everyKotlinBlockIsQuotedFromASampleAndStillMatchesIt() {
        val blocks = blocks(File(android, GUIDE).readLines())
        assertTrue("the guide has no quoted blocks", blocks.any { it.quote != null })
        blocks.forEach { block ->
            if (block.language == KOTLIN) assertTrue("a Kotlin block at line ${block.line} quotes no sample", block.quote != null)
            val (path, region) = block.quote ?: return@forEach
            val quoted = region(File(android, path), region)
            if (!block.published) {
                assertEquals("$GUIDE line ${block.line} quotes $path $region", quoted, dedent(block.lines))
                return@forEach
            }
            val lines = dedent(block.lines)
            val bom = lines.filter { BOM.containsMatchIn(it) }
            assertTrue("$GUIDE line ${block.line}: more than one BOM line", bom.size <= 1)
            bom.forEach { assertEquals("$GUIDE line ${block.line}: the BOM's version", releaseVersion(), BOM.find(it)!!.groupValues[1]) }
            assertEquals("$GUIDE line ${block.line} gives $path $region as published", quoted.map(::published), lines - bom.toSet())
        }
    }

    @Test
    fun everyProtocolMethodTheDocsNameIsOneTheProtocolHas() {
        val methods = AppServerMethods.all.map { it.name }.toSet()
        listOf(GUIDE, PACKAGE_TOOL_README).forEach { doc ->
            val text = File(android, doc).readText()
            val named = (METHOD.findAll(text) + JSON_METHOD.findAll(text)).map { it.groupValues[1] }.toSortedSet()
            assertTrue("$doc names no protocol method", named.isNotEmpty())
            assertEquals("methods $doc names that the protocol does not have", emptySet<String>(), named - methods)
        }
    }

    private class Block(val line: Int, val language: String, val quote: Pair<String, String>?, val published: Boolean, val lines: List<String>)

    /** [line] as an app outside this repository writes it: a module of this repository by its published coordinates. */
    private fun published(line: String): String = PROJECT.replace(line) { "\"$GROUP:${it.groupValues[1]}\"" }

    /** The version this repository releases next: its VERSION_NAME, less `-SNAPSHOT`. */
    private fun releaseVersion(): String =
        File(android, "gradle.properties").readLines().single { it.startsWith("VERSION_NAME=") }.substringAfter('=').removeSuffix("-SNAPSHOT")

    /** The fenced blocks of [lines], each with the quote line right above it, if any. */
    private fun blocks(lines: List<String>): List<Block> {
        val found = mutableListOf<Block>()
        var index = 0
        while (index < lines.size) {
            val fence = FENCE.matchEntire(lines[index].trim())
            if (fence == null) {
                index++
                continue
            }
            val end = (index + 1 until lines.size).first { lines[it].trim() == "```" }
            val above = lines.subList(0, index).lastOrNull { it.isNotBlank() }?.let { QUOTE.matchEntire(it.trim()) }
            val quote = above?.let { it.groupValues[2] to it.groupValues[3] }
            found += Block(index + 1, fence.groupValues[1], quote, above?.groupValues?.get(1) == "published", lines.subList(index + 1, end))
            index = end + 1
        }
        return found
    }

    /** The lines of [file] between its markers of [name], without their common indent. */
    private fun region(file: File, name: String): List<String> {
        val lines = file.readLines()
        val start = lines.indexOfFirst { marker(it) == "start" to name }
        val end = lines.indexOfFirst { marker(it) == "end" to name }
        assertTrue("${file.name} has no region $name", start in 0 until end)
        return dedent(lines.subList(start + 1, end).filter { marker(it) == null })
    }

    private fun marker(line: String): Pair<String, String>? = MARKER.find(line)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun dedent(lines: List<String>): List<String> {
        val kept = lines.dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        val indent = kept.filter(String::isNotBlank).minOfOrNull { line -> line.length - line.trimStart().length } ?: 0
        return kept.map { if (it.isBlank()) "" else it.substring(indent).trimEnd() }
    }

    private companion object {
        const val GUIDE = "agent-app-server/README.md"
        const val PACKAGE_TOOL_README = "tools/agent-plugin/README.md"
        const val KOTLIN = "kotlin"
        val FENCE = Regex("""```(\w+)""")
        val QUOTE = Regex("""<!-- (quote|published): (\S+) (\S+) -->""")
        const val GROUP = "cc.noharry"
        val PROJECT = Regex("""project\(":([a-z0-9-]+)"\)""")
        val BOM = Regex("""platform\("cc\.noharry:agent-bom:([^"]+)"\)""")
        val MARKER = Regex("""guide:(start|end) (\S+)""")

        /** A method in code spans: `area/name`, alone or with its params after it (not a path, which has more). */
        val METHOD = Regex("""`([a-z][A-Za-z0-9]*/[a-z][A-Za-z0-9]*)(?=[`\s{])""")
        val JSON_METHOD = Regex(""""method":\s*"([^"]+)"""")
    }
}
