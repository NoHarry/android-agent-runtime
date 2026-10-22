package cc.noharry.agent.samples.quickstart.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.model.OpenAiModelClient
import cc.noharry.agent.runtime.android.AndroidAgentRuntime
import cc.noharry.agent.runtime.functionTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One screen around the runtime: an OpenAI-compatible endpoint you type in (the key is kept only while the screen is
 * open), a tool of the app's own, and the answer as it streams.
 */
class QuickstartActivity : Activity() {
    private val scope = MainScope()
    private var runtime: AgentRuntime? = null
    private var running: Job? = null
    private var turn = 0

    private lateinit var baseUrl: EditText
    private lateinit var model: EditText
    private lateinit var apiKey: EditText
    private lateinit var input: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = field("Base URL", "https://api.openai.com/v1")
        model = field("Model", "gpt-4o-mini")
        apiKey = field("API key", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        input = field("Ask the agent", "What device am I on, and what time is it?")
        output = TextView(this).apply { setTextIsSelectable(true) }
        val send = Button(this).apply {
            text = "Send"
            setOnClickListener { send() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
            listOf(baseUrl, model, apiKey, input, send, output).forEach(::addView)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
    }

    private fun send() {
        val question = input.text.toString().trim()
        if (question.isEmpty() || running?.isActive == true) return
        val agent = runtime ?: buildRuntime().also { runtime = it }
        output.append("\n> $question\n")
        running = scope.launch {
            agent.run(AgentRequest(requestId = "turn-${++turn}", sessionId = "quickstart", input = question)).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> output.append(event.text)
                    is AgentEvent.NativeToolCallStarted -> output.append("[tool] ${event.toolName}\n")
                    is AgentEvent.Failed -> output.append("\n[failed] ${event.code}: ${event.message}\n")
                    else -> Unit
                }
            }
            output.append("\n")
        }
    }

    private fun buildRuntime(): AgentRuntime {
        val key = apiKey.text.toString()
        val device = functionTools("device") {
            tool("device_info", "The phone or tablet this app runs on.", readOnly = true) {
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
            }
            tool("now", "The current local date and time.", readOnly = true) { java.util.Date().toString() }
        }
        return AndroidAgentRuntime.builder(this)
            .modelClient(OpenAiModelClient({ key }, model = model.text.toString(), baseUrl = baseUrl.text.toString(), stream = true))
            .toolProvider(device)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PADDING = 32
    }
}
