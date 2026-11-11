package cc.noharry.agent.appserver.runtime

import cc.noharry.agent.runtime.SessionHistory
import java.io.File

/** Each durable storage root owns its history; concurrent conversations never rebind another root's store. */
internal object RuntimeHistories {
    private val histories = object : LinkedHashMap<String, SessionHistory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionHistory>?): Boolean = size > 64
    }
    @Synchronized fun forRoot(root: File): SessionHistory = histories.getOrPut(root.canonicalPath) { SessionHistory() }
    @Synchronized fun forget(sessionId: String?) {
        if (sessionId == null) histories.values.forEach { it.clearAll() }
        else histories.values.forEach { it.clearSession(sessionId) }
    }
    /** Drops process caches while leaving durable history available to the next runtime. */
    @Synchronized fun clearCache() = histories.clear()
}
