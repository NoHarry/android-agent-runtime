package cc.noharry.agent.appserver.sample.notes

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.server.AppServer
import kotlin.coroutines.resume
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** The assistant's screen, a client of the server: talk, answer what asks first, and connect another model. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var client: ServerClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = field(R.string.base_url)
        val model = field(R.string.model)
        val apiKey = field(R.string.api_key).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val useModel = Button(this).apply { text = getString(R.string.use_model) }
        val transcript = TextView(this).apply { setTextIsSelectable(true) }
        val status = TextView(this).apply { text = getString(R.string.connecting) }
        val input = field(R.string.input_hint)
        val send = Button(this).apply { text = getString(R.string.send) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(PADDING, PADDING, PADDING, PADDING)
                addView(ScrollView(context).apply { addView(transcript) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                listOf(status, input, send, baseUrl, model, apiKey, useModel).forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            },
        )
        // guide:start client
        client = ServerClient(
            AppServer.connect(this),
            ClientInfo(name = "notes-screen", version = "0.1"),
            ClientCapabilities(approvals = true),
            scope = scope,
        )
        // A tool that waits for a yes is asked here; the first client to answer decides.
        client.approvals = { request -> askPerson(request) }
        scope.launch {
            client.connect()
            client.state.collect { state ->
                transcript.text = state.transcript()
                status.text = if (state.isRunning) getString(R.string.running) else state.providerLabel
            }
        }
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.text.clear()
            scope.launch { runCatching { client.request(ClientRequests.TURN_START, TurnStartParams(text)) }.onFailure { status.text = it.message } }
        }
        // guide:end client
        useModel.setOnClickListener {
            val choice = ModelChoice(baseUrl.text.toString().trim(), model.text.toString().trim(), apiKey.text.toString().trim())
            scope.launch { runCatching { client.use(choice) }.onFailure { status.text = it.message } }
        }
    }

    // guide:start approval
    /** Asks the person about a tool call that waits for a yes; another client answering first closes the dialog. */
    private suspend fun askPerson(request: ApprovalRequest): ApprovalDecision = suspendCancellableCoroutine { answer ->
        val dialog = AlertDialog.Builder(this)
            .setMessage(getString(R.string.approve_tool, request.toolName.substringAfterLast("__")))
            .setPositiveButton(R.string.allow) { _, _ -> answer.resume(ApprovalDecision.APPROVE) }
            .setNegativeButton(R.string.deny) { _, _ -> answer.resume(ApprovalDecision.DENY) }
            .setCancelable(false)
            .show()
        answer.invokeOnCancellation { dialog.dismiss() }
    }
    // guide:end approval

    override fun onDestroy() {
        client.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun field(hint: Int) = EditText(this).apply { this.hint = getString(hint); setSingleLine() }

    private companion object {
        const val PADDING = 32
    }
}
