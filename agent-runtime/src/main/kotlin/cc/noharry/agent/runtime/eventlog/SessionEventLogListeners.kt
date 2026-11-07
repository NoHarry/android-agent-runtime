@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.SessionEvent
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalAgentRuntimeApi::class)
internal class SessionEventLogListeners {
    private val listeners = CopyOnWriteArrayList<(SessionEvent) -> Unit>()

    fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners.remove(listener) }
    }

    fun notifyAccepted(event: SessionEvent) {
        listeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }
}
