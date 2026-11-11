package cc.noharry.agent.appserver.sample

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.server.AppServer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One screen as a client of the app server, the way any front end is: it connects over the protocol, starts turns
 * and shows the current thread from the state the server publishes. It reaches nothing of the server but the protocol.
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var client: ServerClient
    private lateinit var transcript: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val input = EditText(this).apply { hint = getString(R.string.input_hint) }
        val simulated = CheckBox(this).apply { text = getString(R.string.simulated); isChecked = true }
        val send = Button(this).apply { text = getString(R.string.send) }
        status = TextView(this).apply { text = getString(R.string.connecting) }
        transcript = TextView(this).apply { setTextIsSelectable(true) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(PADDING, PADDING, PADDING, PADDING)
                addView(ScrollView(context).apply { addView(transcript) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                addView(status)
                addView(simulated)
                addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(send)
            },
        )
        client = ServerClient(AppServer.connect(this), ClientInfo(name = "agent-app-sample", version = "0.1"), scope = scope)
        scope.launch {
            client.connect()
            client.request(SettingsRequests.RUN_SET, RunSettingsParams(simulatedBackend = simulated.isChecked))
            client.state.collect(::render)
        }
        simulated.setOnCheckedChangeListener { _, on ->
            scope.launch { client.request(SettingsRequests.RUN_SET, RunSettingsParams(simulatedBackend = on)) }
        }
        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.text.clear()
            scope.launch { client.request(ClientRequests.TURN_START, TurnStartParams(text)) }
        }
    }

    override fun onDestroy() {
        client.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: ServerState) {
        transcript.text = state.items.mapNotNull(::line).joinToString("\n\n")
        status.text = when {
            !state.connected -> getString(R.string.connecting)
            state.isRunning -> getString(R.string.running)
            else -> state.providerLabel
        }
    }

    /** The person's words and the answers; tool calls, thinking and status lines are left out. */
    private fun line(item: ConversationItem): String? = when (item) {
        is ConversationItem.TurnHeader -> "> ${item.fullText}"
        is ConversationItem.MessageItem -> when (item.role) {
            TranscriptRole.ASSISTANT -> item.content
            TranscriptRole.ERROR -> "! ${item.content}"
            else -> null
        }
        else -> null
    }

    private companion object {
        const val PADDING = 32
    }
}
