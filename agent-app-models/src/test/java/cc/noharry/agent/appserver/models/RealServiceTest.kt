package cc.noharry.agent.appserver.models

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.TurnResult
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.modelPrice
import cc.noharry.agent.appserver.protocol.ModelUsage
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.cost
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.functionTools
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each service against the service itself, for a person with its key (never part of the build's checks: the
 * `realServiceTest` task runs it). For each service whose key is in `AGENT_E2E_<ID>_KEY` (`DEEPSEEK`, `OPENAI`, …, the
 * service's id in capitals), on its default endpoint or `AGENT_E2E_<ID>_BASE_URL` with `AGENT_E2E_<ID>_ENDPOINT`
 * (`chat_completions`, `responses`, `messages`), with its first listed model or `AGENT_E2E_<ID>_MODEL`: it lists the
 * models, runs a plain turn and one that calls a tool, and checks that both report usage and, for a model the data
 * prices, a cost. A service without a key is skipped. Keys go only to their service; nothing printed carries one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RealServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyServiceWithAKeyListsItsModelsRunsATurnAndCallsATool() = runBlocking {
        assumeTrue("run with ./gradlew :agent-app-models:realServiceTest", System.getProperty("agent.e2e") == "true")
        AppServerTesting.running(PluginAdditions(providerAdapters = ModelsPlugin.adapters()))
        val outcomes = ServiceCatalog.services.map { service ->
            val key = env(service, "KEY") ?: return@map Outcome(service.id, skipped = "no AGENT_E2E_${service.id.uppercase()}_KEY")
            runCatching { check(service, key) }.getOrElse { Outcome(service.id, failure = it.scrubbed(key)) }
        }
        outcomes.forEach { println(it.line()) }
        AppServerTesting.reset()
        assertTrue("services with a key that failed: ${outcomes.filter { it.failure != null }.map { it.service }}", outcomes.none { it.failure != null })
    }

    private suspend fun check(service: ModelService, key: String): Outcome {
        val chosen = env(service, "ENDPOINT")?.let { name -> ProviderEndpoint.entries.single { it.wireName == name } }
        val known = service.endpoints.firstOrNull { chosen == null || it.endpoint == chosen } ?: service.endpoints.first()
        val kind = chosen ?: known.endpoint
        val listing = ProviderProfile("e2e", service.label, kind.provider, key, "", env(service, "BASE_URL") ?: known.baseUrl, kind)
        val listed = withTimeout(TIMEOUT_MILLIS) { AppServerTesting.models(context, listing, key) }
        val model = env(service, "MODEL") ?: service.models.firstOrNull()?.id
            ?: return Outcome(service.id, listed = listed.size, failure = "no model: set AGENT_E2E_${service.id.uppercase()}_MODEL")
        val profile = listing.copy(model = model)
        val plain = turn(profile, "Reply with the single word: pong", tools = false)
        val tooled = turn(profile, "Call the secret_number tool, then tell me the number it returned.", tools = true)
        val price = modelPrice(model)
        val cost = price?.let { listOf(plain, tooled).sumOf { turn -> turn.usage.cost(it) } }
        val problems = listOfNotNull(
            "the plain turn ended ${plain.result.status}: ${plain.result.error?.code}".takeIf { plain.result.status != TurnStatus.COMPLETED },
            "the tool turn called no tool".takeIf { !tooled.calledTool },
            "the tool turn ended ${tooled.result.status}: ${tooled.result.error?.code}".takeIf { tooled.result.status != TurnStatus.COMPLETED },
            "no usage came back".takeIf { listOf(plain, tooled).any { it.usage.inputTokens <= 0 || it.usage.outputTokens <= 0 } },
            "a priced model cost nothing".takeIf { cost != null && cost <= 0 },
        )
        return Outcome(
            service.id, model = model, endpoint = kind.wireName, listed = listed.size, usage = plain.usage + tooled.usage,
            cost = cost?.let { "%.6f %s".format(it, price.currency) } ?: "not priced in the data",
            failure = problems.joinToString("; ").ifEmpty { null },
        )
    }

    private suspend fun turn(profile: ProviderProfile, text: String, tools: Boolean): Turn {
        val config = ProviderConfig.load(context).let { it.copy(profiles = it.profiles + profile) }
        val probe = functionTools("probe") {
            tool("secret_number", "Returns the secret number.", readOnly = true) { "{\"number\":42}" }
        }
        val builder = AgentRuntimeBuilder.create()
            .model(config.client(profile, stream = true))
            .workspaceRoot(Files.createTempDirectory("e2e").toFile())
        if (tools) builder.tools(probe)
        return builder.build().use { runtime ->
            val events = withTimeout(TIMEOUT_MILLIS) { runtime.startThread().runStreamed(text).toList() }
            val result = events.filterIsInstance<ThreadEvent.TurnCompleted>().lastOrNull()?.result
                ?: events.filterIsInstance<ThreadEvent.TurnFailed>().lastOrNull()?.let { failed ->
                    TurnResult(failed.turnId, TurnStatus.FAILED, "", emptyList(), error = failed.error)
                }
                ?: error("the turn ended without a result")
            val called = events.filterIsInstance<ThreadEvent.ItemStarted>().map { it.item }.filterIsInstance<ThreadItem.ToolCall>()
                .any { it.tool.endsWith("secret_number") }
            val usage = result.usage?.let { ModelUsage(1, it.inputTokens.toLong(), it.cacheReadTokens.toLong(), it.cacheWriteTokens.toLong(), it.outputTokens.toLong()) } ?: ModelUsage()
            Turn(result, called, usage)
        }
    }

    private fun env(service: ModelService, name: String): String? = System.getenv("AGENT_E2E_${service.id.uppercase()}_$name")?.trim()?.ifEmpty { null }

    /** What a failure says, with the key taken out wherever it appears. */
    private fun Throwable.scrubbed(key: String): String = "${javaClass.simpleName}: ${message.orEmpty().replace(key, "<key>")}".take(MAX_FAILURE_CHARS)

    private class Turn(val result: TurnResult, val calledTool: Boolean, val usage: ModelUsage)

    private class Outcome(
        val service: String,
        val skipped: String? = null,
        val model: String = "",
        val endpoint: String = "",
        val listed: Int = 0,
        val usage: ModelUsage = ModelUsage(),
        val cost: String = "",
        val failure: String? = null,
    ) {
        fun line(): String = when {
            skipped != null -> "$service: skipped ($skipped)"
            else -> "$service: ${if (failure == null) "passed" else "FAILED ($failure)"} model=$model endpoint=$endpoint listed=$listed " +
                "input=${usage.inputTokens} cached=${usage.cacheReadTokens} output=${usage.outputTokens} cost=$cost"
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 180_000L
        const val MAX_FAILURE_CHARS = 400
    }
}
