package cc.noharry.agent.appserver.skills

import android.content.Context
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.RunFacts

/**
 * A host's skills and subagent types as the server's own tests know them: small ones in this module's test assets
 * (`test-host/`), each standing for a kind a host ships. None of them is any real host's.
 */
internal fun testHostSkills(context: Context): List<RuntimeSkill> = listOf(
    // In every run's system prompt, the same every run.
    RuntimeSkill(
        "progress-notes", "进度提示", "多步任务里说明在做什么", "$SKILLS/progress-notes/SKILL.md",
        enabled = true, preload = true, systemPrompt = true,
    ),
    // Off until the person turns it on.
    RuntimeSkill("task-orchestration", "多服务任务编排", "几个服务一起用", "$SKILLS/task-orchestration/SKILL.md", enabled = false, preload = true),
    // Carried only when the request's words match its manifest's triggers; its sections follow the subagent types.
    RuntimeSkill(
        EVENT_SKILL, "活动筹备", "筹备一场活动", "$SKILLS/event-planning/SKILL.md", enabled = true, preload = true,
        manifestPath = "$SKILLS/event-planning/runtime.json", mainAgentOnly = true,
    ),
    // Only while a running plugin says the `a2a` section holds.
    RuntimeSkill(
        "agent-delegation", "交给个人助手", "交给用户自己的助手", "$SKILLS/agent-delegation/SKILL.md", enabled = true, preload = true,
        requiresSection = "a2a",
    ),
    // Only in a run a running plugin gives the tool server it describes.
    RuntimeSkill(
        SCREEN_SKILL, "操作屏幕", "一步步点屏幕", "$SKILLS/screen-steps/SKILL.md", enabled = true, preload = true,
        requiresToolServer = SCREEN_TOOL_SERVER,
    ),
    // In the catalog only, read when needed.
    RuntimeSkill("catalog-only", "用到时再读", "用到时再读的说明", "$SKILLS/catalog-only/SKILL.md", enabled = true),
)

/** The skills switched on at first, in their order. */
internal val TEST_DEFAULT_SKILLS = listOf("progress-notes", EVENT_SKILL, "agent-delegation", SCREEN_SKILL, "catalog-only")

/** The host's subagent types: two that split an event's work, and one that checks a service's answer online. */
internal val TEST_SUBAGENT_KINDS = listOf(
    SubagentKind(
        "event-venues", android.R.string.untitled, "venues for an event: capacity and prices for a date and a budget",
        "$AGENTS/event-venues/AGENT.md", tools = DEFAULT_SUBAGENT_TOOLS + SubagentToolGroup.DEVICE_QUERY,
    ),
    SubagentKind(
        "event-guests", android.R.string.untitled, "when and how to tell each guest", "$AGENTS/event-guests/AGENT.md",
        maxLookups = 6,
    ),
    SubagentKind(
        FACT_CHECK_KIND, android.R.string.untitled, "check online what a service returned; finish without waiting",
        "$AGENTS/fact-check/AGENT.md", maxLookups = 6, searchApi = true,
        tools = setOf(SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH), requiresWebLookup = true,
    ),
)

/** A host whose runs offer [TEST_SUBAGENT_KINDS] and add a rule while the fact check can be spawned. */
internal object TestHostRuns : RunAddOns {
    override fun subagentKinds(): List<SubagentKind> = TEST_SUBAGENT_KINDS

    override fun systemRules(facts: RunFacts): List<String> = listOfNotNull(
        SEARCH_RULE.takeIf { facts.webSearch },
        FACT_CHECK_RULE.takeIf { FACT_CHECK_KIND in facts.subagentTypes },
    )
}

internal const val EVENT_SKILL = "event-planning"
internal const val SCREEN_SKILL = "screen-steps"
internal const val SCREEN_TOOL_SERVER = "screen_tools"
internal const val FACT_CHECK_KIND = "fact-check"
internal const val SEARCH_RULE = "Search the web in one step: several search calls in the same response, then answer once."
internal const val FACT_CHECK_RULE = "After a service's answer, spawn agent=fact-check in the same response and do not wait_agent."

/** Words that plan an event, which the event skill's manifest picks. */
internal const val EVENT_TEXT = "下周六给老王筹备一场生日聚会，帮我安排一下"

private const val SKILLS = "test-host/skills"
private const val AGENTS = "test-host/agents"
