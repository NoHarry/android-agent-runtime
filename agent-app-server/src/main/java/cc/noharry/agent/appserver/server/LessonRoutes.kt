package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.MemoryLessons
import cc.noharry.agent.appserver.protocol.MemoryRequests
import cc.noharry.agent.appserver.runtime.loadRuntimeMemoryEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The lessons the runtime draws from runs (`memory/lessons`), which every app server answers. What the model remembers
 * about the person is another thing, with requests of its own while the memory plugin runs.
 */
internal class LessonRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(MemoryRequests.LESSONS) {
            val evidence = withContext(Dispatchers.IO) { loadRuntimeMemoryEvidence(app) }
            MemoryLessons(evidence.activeCount, evidence.pendingCount, evidence.active, evidence.pending)
        }
    }
}
