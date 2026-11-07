@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.DefaultThreadRuntime
import cc.noharry.agent.spi.model.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadOptionPatchTest {
    @Test fun resumingOnlySceneRetainsEverySavedApprovalPolicy() = runBlocking {
        for (policy in listOf(ApprovalPolicy.APPROVE_ALL, ApprovalPolicy.DENY_ALL, ApprovalPolicy.REMEMBER)) {
            val root = threadRoot()
            approvalBuilder(root).build().use { runtime ->
                val thread = runtime.startThread(ThreadOptions(threadId = "saved", approvalPolicy = policy))
                if (policy == ApprovalPolicy.REMEMBER) thread.runStreamed("set temperature").collect { event ->
                    if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).approve()
                }
            }
            approvalBuilder(root).build().use { runtime ->
                val thread = runtime.resumeThread("saved", ThreadOptions(scene = "new-scene"))
                var requested = 0
                thread.runStreamed("set temperature").collect { event ->
                    if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                        requested++
                        (event.item as Approval).deny()
                    }
                }
                assertEquals("Policy $policy must survive an unrelated option patch", 0, requested)
            }
        }
    }

    @Test fun individualOverridesCanBeClearedWithoutResettingOtherDefaults() = runBlocking {
        val model = ThreadScriptModel(listOf(finishEvents("done")))
        threadBuilder(model).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "reset", model = ModelRef("custom", "custom-model"),
                scene = "keep-scene", workspace = "default", instructions = "SAVED_THREAD_INSTRUCTIONS",
                approvalPolicy = ApprovalPolicy.DENY_ALL))
            val thread = runtime.resumeThread("reset", ThreadOptions(reset = setOf(ThreadOption.MODEL, ThreadOption.INSTRUCTIONS)))
            assertEquals(TurnStatus.COMPLETED, thread.run("hello").status)
            assertNull(model.requests.single().modelRef)
            assertFalse(model.requests.single().system.orEmpty().contains("SAVED_THREAD_INSTRUCTIONS"))
            val saved = (runtime as DefaultThreadRuntime).store.load("reset")!!.options
            assertEquals("keep-scene", saved.scene)
            assertEquals("default", saved.workspace)
            assertEquals(ApprovalPolicy.DENY_ALL, saved.approvalPolicy)
            assertTrue(saved.reset.isEmpty())
        }
    }

    @Test fun settingNewValuesWinsOverResetAndExplicitAskReplacesPolicy() = runBlocking {
        val model = ThreadScriptModel(listOf(finishEvents("done")))
        threadBuilder(model).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "replace", instructions = "OLD", approvalPolicy = ApprovalPolicy.DENY_ALL))
            runtime.resumeThread("replace", ThreadOptions(instructions = "NEW_INSTRUCTIONS",
                approvalPolicy = ApprovalPolicy.ASK, reset = setOf(ThreadOption.INSTRUCTIONS))).run("hello")
            assertTrue(model.requests.single().system.orEmpty().contains("NEW_INSTRUCTIONS"))
            assertEquals(ApprovalPolicy.ASK, (runtime as DefaultThreadRuntime).store.load("replace")!!.options.approvalPolicy)
        }
    }

    @Test fun resumingWithoutChangesLeavesTheSavedThreadUntouched() = runBlocking {
        val root = threadRoot()
        approvalBuilder(root).build().use { it.startThread(ThreadOptions(threadId = "quiet", scene = "kept")) }
        val file = root.walkTopDown().single { it.name.endsWith(".thread.json") }
        check(file.setLastModified(0L))
        approvalBuilder(root).build().use { runtime ->
            runtime.resumeThread("quiet")
            runtime.resumeThread("quiet", ThreadOptions(scene = "kept"))
            assertEquals(0L, file.lastModified())
            runtime.resumeThread("quiet", ThreadOptions(scene = "changed"))
            assertNotEquals(0L, file.lastModified())
        }
    }
}
