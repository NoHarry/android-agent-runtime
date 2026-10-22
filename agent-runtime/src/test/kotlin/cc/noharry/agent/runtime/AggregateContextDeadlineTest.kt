package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ContextStability
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.RuntimeContextBlock
import cc.noharry.agent.kernel.api.RuntimeContextContribution
import cc.noharry.agent.kernel.api.RuntimeContextDatum
import cc.noharry.agent.kernel.api.RuntimeContextDatumKind
import cc.noharry.agent.kernel.api.RuntimeContextProvider
import cc.noharry.agent.kernel.api.RuntimeContextRequest
import cc.noharry.agent.kernel.api.RuntimeContextScalar
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.RuntimeContextHooks
import cc.noharry.agent.kernel.core.failed

/**
 * 20ms is an aggregate monotonic deadline across all providers — not N×20ms serial budget.
 */
@OptIn(ExperimentalAgentRuntimeApi::class)
class AggregateContextDeadlineTest {
    @Test
    fun multi_slow_providers_share_aggregate_budget_and_skip_after_deadline() =
        runBlocking {
            val calls = AtomicInteger(0)
            val clock = AtomicLong(0L)
            val slow =
                object : RuntimeContextProvider {
                    override val id: String = "slow-1"

                    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
                        calls.incrementAndGet()
                        clock.addAndGet(21_000_000L)
                        return fact(id, "slow_fact", "SLOW1")
                    }
                }
            val neverReached =
                object : RuntimeContextProvider {
                    override val id: String = "slow-2"

                    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
                        calls.incrementAndGet()
                        return fact(id, "slow_fact_2", "SLOW2")
                    }
                }
            val suffix =
                RuntimeContextHooks.collectContextSystemSuffix(
                    contextProviders = listOf(slow, neverReached),
                    request = AgentRequest("agg-1", "s", "ping"),
                    runId = "agg-1",
                    referenceTimeEpochMillis = 0L,
                    monotonicNanos = clock::get,
                )
            assertTrue("first provider contribution retained", suffix.contains("SLOW1"))
            assertFalse("expired aggregate must skip second provider", suffix.contains("SLOW2"))
            assertTrue("providers called=${calls.get()}", calls.get() == 1)
        }

    @Test
    fun provider_exception_fail_open_continues_remaining_budget() =
        runBlocking {
            val boomCalls = AtomicInteger(0)
            val okCalls = AtomicInteger(0)
            val boom =
                object : RuntimeContextProvider {
                    override val id: String = "boom"

                    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
                        boomCalls.incrementAndGet()
                        throw RuntimeException("provider failed")
                    }
                }
            val ok =
                object : RuntimeContextProvider {
                    override val id: String = "ok"

                    override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
                        okCalls.incrementAndGet()
                        return fact(id, "ok_pred", "OK_VALUE")
                    }
                }
            val seen = mutableListOf<ModelRequest>()
            val runtime =
                AgentRuntimeBuilder.create()
                    .modelClient(
                        object : ModelClient {
                            override val providerConcurrency: ProviderConcurrencyDeclaration =
                                ProviderConcurrencyDeclaration.serialized()
                            override fun complete(request: ModelRequest): Flow<ModelEvent> {
                                seen += request
                                return flowOf(
                                    ModelEvent.ToolCall("f", "finish", """{"response":"ok"}"""),
                                    ModelEvent.Completed,
                                )
                            }
                        },
                    )
                    .contextProvider(boom)
                    .contextProvider(ok)
                    .build()
            try {
                runtime.run(AgentRequest("agg-ex", "s", "ping")).toList()
                assertTrue("boom must be invoked", boomCalls.get() == 1)
                assertTrue("ok must still run after fail-open: boom=${boomCalls.get()} ok=${okCalls.get()}", okCalls.get() == 1)
                assertTrue("model must run", seen.isNotEmpty())
                val system = seen.first().system.orEmpty()
                assertTrue("system should include surviving provider: $system", system.contains("OK_VALUE"))
            } finally {
                runtime.close()
            }
        }

    private fun fact(providerId: String, predicate: String, value: String): RuntimeContextContribution =
        RuntimeContextContribution(
            providerId = providerId,
            blocks =
                listOf(
                    RuntimeContextBlock(
                        id = "$providerId-live",
                        stability = ContextStability.LIVE,
                        data =
                            listOf(
                                RuntimeContextDatum(
                                    id = "1",
                                    kind = RuntimeContextDatumKind.FACT,
                                    subject = "self",
                                    predicate = predicate,
                                    value = RuntimeContextScalar.Text(value),
                                ),
                            ),
                        payloadDigest = value,
                    ),
                ),
            fingerprint = value,
        )
}
