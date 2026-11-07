package cc.noharry.agent.runtime.eventlog

import java.io.File
import java.nio.file.Files

/** Removes contained event data without following a nested symlink outside the event-log root. */
internal fun deleteEventLogTree(file: File) {
    if (Files.isSymbolicLink(file.toPath())) {
        Files.delete(file.toPath())
        return
    }
    if (!file.exists()) return
    if (file.isDirectory) {
        val children = checkNotNull(file.listFiles()) { "Cannot list event-log data for deletion" }
        children.forEach(::deleteEventLogTree)
    }
    check(file.delete()) { "Cannot delete event-log data" }
}
