@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.android

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Unpinned, Robolectric runs this library's tests on Android 4.4, which has no no-backup directory.
@Config(sdk = [33])
class AndroidAgentRuntimeTest {
    private val model = object : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        override fun complete(request: ModelRequest) = flowOf(ModelEvent.Completed)
    }

    @Test
    fun aNamedRuntimeKeepsItsFilesApart() {
        val context = RuntimeEnvironment.getApplication()

        AndroidAgentRuntime.builder(context, "second").modelClient(model).build().close()

        assertTrue(File(context.filesDir, "agent-runtime/runtimes/second/workspace").isDirectory)
        assertFalse(File(context.filesDir, "agent-runtime/workspace").exists())
        assertThrows(IllegalArgumentException::class.java) { AndroidAgentRuntime.builder(context, "../up") }
        assertThrows(IllegalArgumentException::class.java) { AndroidAgentRuntime.builder(context, "") }
    }
}
