package cc.noharry.agent.appserver.runtime
import android.content.Context
import android.content.Intent
import android.net.Uri
import cc.noharry.agent.appserver.protocol.WorkspaceSelectionState

internal data class SafWorkspace(
    val uri: Uri,
    val label: String,
)

internal fun selectedSafWorkspace(context: Context): SafWorkspace? {
    val prefs = context.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
    val value = prefs.getString(KEY_URI, null)?.takeIf { it.isNotBlank() } ?: return null
    val uri = Uri.parse(value)
    return SafWorkspace(uri = uri, label = uri.lastPathSegment ?: "SAF workspace")
}

internal fun workspaceSelectionState(context: Context): WorkspaceSelectionState {
    val selected = selectedSafWorkspace(context)
    return WorkspaceSelectionState(
        label = selected?.label.orEmpty(),
        safEnabled = selected != null,
    )
}

internal fun saveSafWorkspace(context: Context, uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
    context.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_URI, uri.toString())
        .apply()
}

internal fun clearSafWorkspaceSelection(context: Context) {
    context.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_URI)
        .apply()
}

/** Why the folder picker did not open, as the run status says it: [detail] from its launch, blank when there is none. */
internal fun safPickerUnavailableMessage(detail: String = ""): String =
    if (detail.isBlank()) "SAF picker unavailable: no document-tree picker installed." else "SAF picker unavailable: $detail"


internal const val SAF_WORKSPACE_SOURCE = "saf://selected-tree"

private const val SAF_PREFS = "app_server_saf_workspace"
private const val KEY_URI = "tree_uri"
