package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class BootstrapPromptProviderTest {
    @Test
    fun promptVersionChangesWhenProtocolRulesChange() {
        val prompt = BootstrapPromptProvider.build()

        assertTrue(prompt.version.contains("v10"))
    }

    @Test
    fun promptKeepsProtocolRulesCompact() {
        val prompt = BootstrapPromptProvider.build().text
        val protocolRules = prompt.lines()
            .filter { it.startsWith("- ") }
            .filter { rule ->
                rule.contains("search_tools") ||
                    rule.contains("update_todo") ||
                    rule.contains("finish(response)")
            }

        assertTrue("todo/search/finish protocol rules must stay compact", protocolRules.size <= 3)
        assertTrue(prompt.contains("Search with search_tools(query)"))
        assertTrue(prompt.contains("emit it in the same round as the related backend or workspace calls"))
        assertTrue(prompt.contains("including the first useful business call"))
        assertTrue(prompt.contains("Prefer finish(response) as the explicit completion signal"))
        assertFalse(prompt.contains("Never"))
        assertFalse(prompt.contains("never"))
        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= STATIC_PROMPT_SOFT_BUDGET_BYTES)
    }

    @Test
    fun aToolIndexThatFitsKeepsItsSummaries() {
        val index = ToolDomainIndex(listOf(ToolDomain("device", 320, "取消当前进行中的 AiOT 设备发现扫描。 / 开始发现附近的 AiOT 设备。", emptyList())))
        val prompt = BootstrapPromptProvider.build(toolDomainIndex = index, contextCatalog = defaultContextCatalog()).text

        assertTrue(prompt.contains(index.toPromptSection()))
    }

    @Test
    fun aCarWithManyToolDomainsStillGetsAPromptWithinBudget() {
        // A real device's catalog: many command_<domain>_ prefixes, each with long Chinese descriptions.
        val summary = (1..5).joinToString(" / ") { "调节车内设备的状态并返回执行结果，支持多个音区与座位$it" }
        val index = ToolDomainIndex((1..30).map { ToolDomain("domain$it", 40 - it, summary, emptyList()) })
        val skills = SkillsCatalog((1..8).map { SkillCatalogEntry("skill-$it", "通过当前 Adapter 接口完成点播、候选选择和当前播放控制，保留用户的音源、顺序及只查询限制。") })
        assertTrue("the full index alone is over the budget", index.toPromptSection().toByteArray().size > STATIC_PROMPT_SOFT_BUDGET_BYTES)

        val prompt = BootstrapPromptProvider.build(
            toolDomainIndex = index,
            contextCatalog = defaultContextCatalog(),
            skillsCatalog = skills,
            scene = "debug-console",
            hostPrompt = "You are Ada.",
        ).text

        val static = prompt.substringBefore("\n\nHost instructions:\n")
        assertTrue(static.toByteArray(Charsets.UTF_8).size <= STATIC_PROMPT_SOFT_BUDGET_BYTES)
        assertTrue("summaries go first; every domain stays", prompt.contains("- domain1 (39 tools)\n") && prompt.contains("- domain30 (10 tools)"))
        assertFalse(prompt.contains(summary))
        assertTrue(prompt.contains("Host instructions:\nYou are Ada."))
    }

    @Test
    fun evenMoreDomainsListOnlyTheLargest() {
        val index = ToolDomainIndex((1..400).map { ToolDomain("area_domain_$it", 500 - it, "摘要", emptyList()) })
        val prompt = BootstrapPromptProvider.build(toolDomainIndex = index, contextCatalog = defaultContextCatalog()).text

        assertTrue(prompt.toByteArray(Charsets.UTF_8).size <= STATIC_PROMPT_SOFT_BUDGET_BYTES)
        assertTrue(prompt.contains("- area_domain_1 (499 tools)"))
        assertTrue(prompt.contains("and ${400 - MAX_LISTED_DOMAINS} more domains"))
        assertFalse(prompt.contains("area_domain_${MAX_LISTED_DOMAINS + 1} "))
    }

    @Test
    fun aPromptOverTheSoftBudgetIsStillSentWhole() {
        val context = ContextCatalog((1..120).map { ContextPathEntry("/very/long/context/path/$it/", "一段很长的上下文路径说明，用来撑满预算") })
        val prompt = BootstrapPromptProvider.build(contextCatalog = context).text

        assertTrue(prompt.toByteArray(Charsets.UTF_8).size > STATIC_PROMPT_SOFT_BUDGET_BYTES)
        assertTrue(prompt.contains("/very/long/context/path/120/"))
    }

    @Test
    fun promptPreventsStandaloneSkillReadsWhenBusinessCanRun() {
        val prompt = BootstrapPromptProvider.build(
            skillsCatalog = SkillsCatalog(listOf(SkillCatalogEntry("debug_voice_progress", "Progress voice cues."))),
        ).text

        assertTrue(prompt.contains("Read skill or context files only when the compact catalog is insufficient"))
        assertTrue(prompt.contains("current task"))
    }

    @Test
    fun promptAdvertisesConcreteSessionCurrentPath() {
        val prompt = BootstrapPromptProvider.build(contextCatalog = defaultContextCatalog()).text

        assertTrue(prompt.contains("/context/session/current.json"))
        assertFalse(prompt.contains("/context/session/ — current session history"))
    }

    @Test
    fun promptOnlyAdvertisesConfiguredSceneRules() {
        val withoutRules = BootstrapPromptProvider.build(
            scene = "debug-console",
            contextCatalog = defaultContextCatalog(),
        ).text
        val withRules = BootstrapPromptProvider.build(
            scene = "debug-console",
            contextCatalog = defaultContextCatalog(),
            sceneRules = setOf("debug-console"),
        ).text

        assertTrue(withoutRules.contains("Current scene: debug-console"))
        assertFalse(withoutRules.contains("Scene rules: /context/scenes/debug-console/AGENTS.md"))
        assertFalse(withRules.contains("/context/scenes/debug-console/AGENTS.md"))
        assertTrue(withRules.contains("Scene-specific runtime rules"))
    }

    @Test
    fun promptAdvertisesStandardAgentsSkillsPathOnly() {
        val prompt = BootstrapPromptProvider.build(
            contextCatalog = defaultContextCatalog(),
            skillsCatalog = SkillsCatalog(listOf(SkillCatalogEntry("debug", "Debug skill."))),
        ).text

        assertTrue(prompt.contains("/.agents/skills/{name}/SKILL.md"))
        assertFalse(prompt.contains("\nRead /skills/{name}/SKILL.md"))
        assertFalse(prompt.contains("\n- /skills/{name}/SKILL.md"))
        assertFalse(prompt.contains("/context/scenes/{scene}/"))
    }
}
