package cc.noharry.agent.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A message of the conversation so far, oldest first; [role] is `user` or `assistant`. */
data class VoiceHistoryMessage(val role: String, val text: String)

/** Everything one judgment sees: the open turn and the conversation around it. */
data class VoiceJudgeRequest(
    val snapshot: VoiceTurnSnapshot,
    val history: List<VoiceHistoryMessage>,
    /** `listening`, `thinking` (the agent works) or `speaking` (its reply is being played). */
    val assistantState: String = ASSISTANT_LISTENING,
    /** What the assistant is saying right now; with `speaking`, Jev is also asked whether the words cut in. */
    val assistantSpeech: String = "",
    /** The request the agent is working on; when set, Jev is also asked whether the words replace it. */
    val currentTask: String = "",
    /** What the agent has done and is doing for [currentTask] (its steps, tools, what it has said so far). */
    val taskProgress: String = "",
    /** The session may be dismissed by voice: Jev is also asked whether the words end the conversation. */
    val canEndSession: Boolean = false,
    /**
     * The host has a companion chat service (闲聊) for small talk: with the agent idle, Jev is also asked whether the
     * words are small talk rather than something to do or look up.
     */
    val askSmallTalk: Boolean = false,
)

const val ASSISTANT_LISTENING = "listening"
const val ASSISTANT_THINKING = "thinking"
const val ASSISTANT_SPEAKING = "speaking"

fun interface VoiceTurnJudge {
    /** Throws on transport or protocol failure; the turn then ends as uncertain. */
    suspend fun judge(request: VoiceJudgeRequest): VoiceJudgment
}

/**
 * Jev through TypeSafe's systemone endpoint: three nouls on the recognizer's text. No audio leaves the device here;
 * the text, including side talk, does.
 */
class JevTurnJudge(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    timeoutMillis: Long,
    private val transcriptNotes: String = SHERPA_TRANSCRIPT_NOTES,
    /** Who the user talks to, named in the judge's instructions; unnamed by default. */
    private val identity: VoiceAssistantIdentity = VoiceAssistantIdentity.UNNAMED,
    client: OkHttpClient = JEV_TURN_HTTP_CLIENT,
    /** Each answered call's token usage as reported (0 when the response has none), for cost accounting. */
    private val onUsage: (inputTokens: Int, outputTokens: Int) -> Unit = { _, _ -> },
) : VoiceTurnJudge {
    private val http = client.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun judge(request: VoiceJudgeRequest): VoiceJudgment {
        val started = System.nanoTime()
        val body = jevTurnBody(model, request, transcriptNotes, identity)
        val httpRequest = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        val response = try {
            http.newCall(httpRequest).await()
        } catch (failed: IOException) {
            // A timed-out call only cancels its own stream: the next judgments would reuse a connection that may have
            // gone quiet (owner 2026-10-18: three judgments in a row timed out). Start them on a new one.
            http.connectionPool.evictAll()
            throw failed
        }
        val judgment = parseJevTurn(response, latencyMillis = (System.nanoTime() - started) / 1_000_000)
        val (input, output) = parseJevUsage(response)
        onUsage(input, output)
        return judgment
    }
}

fun jevTurnBody(
    model: String,
    request: VoiceJudgeRequest,
    transcriptNotes: String = SHERPA_TRANSCRIPT_NOTES,
    identity: VoiceAssistantIdentity = VoiceAssistantIdentity.UNNAMED,
): String {
    val snapshot = request.snapshot
    val history = request.history.filter { it.text.isNotBlank() }.takeLast(HISTORY_SENT)
    return buildJsonObject {
        put("model", model)
        putJsonObject("state") {
            put("setting", SETTING.naming(identity))
            put("transcript_notes", transcriptNotes)
            put("timing_notes", TIMING_NOTES)
            if (snapshot.called) put("addressing_notes", CALLED_NOTES.naming(identity))
            put("conversation_history", buildJsonArray {
                history.forEach { message -> add(buildJsonObject { put("role", message.role); put("text", message.text) }) }
            })
            put("assistant_last_message", history.lastOrNull { it.role == "assistant" }?.text ?: "(none yet)")
            put("assistant_state", request.assistantState)
            if (request.assistantState == ASSISTANT_SPEAKING) {
                put("assistant_current_speech", request.assistantSpeech.ifBlank { "(not yet transcribed)" })
            }
            if (request.currentTask.isNotBlank()) put("assistant_current_task", request.currentTask)
            if (request.currentTask.isNotBlank() && request.taskProgress.isNotBlank()) put("assistant_task_progress", request.taskProgress)
            put("current_user_transcript", snapshot.transcript)
            if (snapshot.trajectory.isNotEmpty()) {
                put("transcript_trajectory", buildJsonArray {
                    snapshot.trajectory.forEach { (text, ago) -> add(buildJsonObject { put("text", text); put("ms_ago", ago) }) }
                })
            }
            put("turn_elapsed_ms", snapshot.turnElapsedMillis)
            snapshot.silenceMillis?.let { put("silence_since_last_word_ms", it) }
            put("asr_final", snapshot.asrFinal)
        }
        putJsonObject("questions") {
            noul(TURN_COMPLETE, EOS_INSTRUCTIONS, EOS_TRUE, EOS_FALSE)
            noul(HOLDING_FLOOR, HOLDING_INSTRUCTIONS, HOLDING_TRUE, HOLDING_FALSE)
            noul(ADDRESSED, ADDRESSEE_INSTRUCTIONS.naming(identity), ADDRESSEE_TRUE, ADDRESSEE_FALSE)
            if (request.assistantState == ASSISTANT_SPEAKING) {
                noul(TAKING_FLOOR, TAKING_FLOOR_INSTRUCTIONS, TAKING_FLOOR_TRUE, TAKING_FLOOR_FALSE)
            }
            if (request.currentTask.isNotBlank()) {
                noul(REPLACES_TASK, REPLACES_TASK_INSTRUCTIONS, REPLACES_TASK_TRUE, REPLACES_TASK_FALSE)
            }
            if (request.canEndSession) noul(ENDS_SESSION, ENDS_SESSION_INSTRUCTIONS, ENDS_SESSION_TRUE, ENDS_SESSION_FALSE)
            if (request.askSmallTalk && request.currentTask.isBlank()) noul(SMALL_TALK, SMALL_TALK_INSTRUCTIONS.naming(identity), SMALL_TALK_TRUE, SMALL_TALK_FALSE)
        }
    }.toString()
}

fun parseJevTurn(response: String, latencyMillis: Long = 0): VoiceJudgment {
    val answers = Json.parseToJsonElement(response).jsonObject["answers"]?.jsonObject ?: throw IOException("Jev answer has no answers.")
    fun score(name: String): Double {
        val value = (answers[name] as? JsonObject)?.get("noul")?.jsonPrimitive?.doubleOrNull
        if (value == null || !value.isFinite() || value !in 0.0..1.0) throw IOException("Jev answer has no valid $name.")
        return value
    }
    val takingFloor = if (TAKING_FLOOR in answers) score(TAKING_FLOOR) else null
    val replacesTask = if (REPLACES_TASK in answers) score(REPLACES_TASK) else null
    val endsSession = if (ENDS_SESSION in answers) score(ENDS_SESSION) else null
    val smallTalk = if (SMALL_TALK in answers) score(SMALL_TALK) else null
    return VoiceJudgment(score(TURN_COMPLETE), score(HOLDING_FLOOR), score(ADDRESSED), latencyMillis, takingFloor, replacesTask, endsSession, smallTalk = smallTalk)
}

/** `usage.input_tokens` / `usage.output_tokens` of a systemone response; zeros when absent. */
fun parseJevUsage(response: String): Pair<Int, Int> {
    val usage = runCatching { Json.parseToJsonElement(response).jsonObject["usage"] as? JsonObject }.getOrNull() ?: return 0 to 0
    fun count(name: String) = usage[name]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    return count("input_tokens") to count("output_tokens")
}

private fun kotlinx.serialization.json.JsonObjectBuilder.noul(name: String, instructions: String, yes: String, no: String) {
    putJsonObject(name) {
        put("type", "noul")
        put("instructions", instructions)
        putJsonObject("criteria") {
            put("true", yes)
            put("false", no)
        }
    }
}

private suspend fun Call.await(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(IOException("Jev connection failed: ${e.message}"))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (!it.isSuccessful) throw IOException("Jev HTTP ${it.code}.")
                    it.body?.string() ?: throw IOException("Empty Jev response.")
                }
            }
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val JEV_TURN_HTTP_CLIENT = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
private const val HISTORY_SENT = 6
private const val TURN_COMPLETE = "turn_complete"
private const val HOLDING_FLOOR = "holding_floor"
private const val ADDRESSED = "addressed_to_agent"
private const val TAKING_FLOOR = "taking_floor"
private const val REPLACES_TASK = "replaces_task"
private const val ENDS_SESSION = "ends_session"
private const val SMALL_TALK = "small_talk"

// Where the assistant's name and wake phrase go in the instructions ([naming]).
private const val CALLED = "{called}"
private const val WAKE = "{wake}"
private const val NAME = "{name}"
private const val NAMED = "{named}"

// Prompts adapted from AgoraIO-Community/convoai-jev-vad (server/src/jev_eos.py): same questions, our setting.
private const val SETTING =
    "Open-mic voice session with an AI assistant$CALLED on a tablet or a phone. The user " +
        "tapped to wake it and the microphone then stays open until they end the session, so it hears everyone " +
        "nearby: people talking to each other, phone calls, radio or video. Speech is mostly Mandarin Chinese, " +
        "sometimes with English words. The assistant controls the device and apps and replies as soon as a turn " +
        "addressed to it is judged complete."

const val SHERPA_TRANSCRIPT_NOTES =
    "current_user_transcript is a live partial from an on-device streaming recognizer: its best guess so far, not " +
        "a final transcript. It has no punctuation, may spell English in capitals, and makes word errors (homophones " +
        "are common in Chinese): judge the intent, not the exact characters. Fillers (嗯, 啊, 那个, 就是, um), repeated " +
        "words and self-corrections are normal speech. Breaths, coughs and laughter are usually not transcribed."

const val CLOUD_TRANSCRIPT_NOTES =
    "current_user_transcript is a live partial from a cloud streaming recognizer: its best guess so far, not a " +
        "final transcript. Punctuation is inserted automatically; a trailing period or question mark is NOT " +
        "evidence the user finished. Word errors happen (homophones are common in Chinese): judge the intent. " +
        "Fillers (嗯, 啊, 那个, 就是, um), repeated words and self-corrections are normal speech."

private const val TIMING_NOTES =
    "silence_since_last_word_ms: how long the microphone has been quiet since the last voiced sound (absent = the " +
        "user is still making sound). asr_final: the recognizer closed its segment after a pause of its own. " +
        "transcript_trajectory: how the text grew during this turn (oldest first, age in ms); rapid recent growth " +
        "means the speaker is in full flow. turn_elapsed_ms: time since the turn opened."

private const val CALLED_NOTES =
    "The speaker called the assistant just before this turn (said its wake word$WAKE or pressed its mic " +
        "button), and current_user_transcript is what they said right after. Words right after calling the " +
        "assistant are almost always for it; score them as addressed unless they plainly talk to someone else."

private const val EOS_INSTRUCTIONS =
    "You are the end-of-turn detector in a live spoken conversation. You do NOT hear audio: you see only the " +
        "recognizer's text so far and client-measured timing. Decide whether the speaker has finished what they " +
        "wanted to say and now expects a response. Use conversation_history and assistant_last_message: if the " +
        "assistant just asked something, a short direct answer is a complete turn ('第二个', '好的', '不用了', 'yes'). " +
        "A sentence can be complete yet clearly a lead-in ('我想问一下', '是这样的', '那个'). Trailing connectors " +
        "(然后, 还有, 而且, 但是, 因为, 就是, and, but) and announced-but-unsaid content mean not complete. " +
        "Within-turn pauses are usually under ~800 ms, so longer silence after a plausible request is strong " +
        "evidence they are done, while silence after a dangling connector means they are still thinking. " +
        "asr_final supports completion but is not sufficient alone. Examples: '导航去广州塔' → complete. " +
        "'导航去广州塔然后' → not complete. '帮我把闹钟' → not complete. '打开蓝牙' → complete. If you say complete, " +
        "the assistant acts at once and cuts off a speaker who was about to continue."
private const val EOS_TRUE =
    "A complete thought, question, request or answer that hands over: nothing signals more is coming, no trailing " +
        "connector, no announced content still unsaid."
private const val EOS_FALSE =
    "Still going: mid-sentence, trailing off, thinking aloud, ended on a connector or lead-in, or gave only part " +
        "of what the request needs."

private const val HOLDING_INSTRUCTIONS =
    "Judging from the TEXT only, is the speaker explicitly signalling that they need more time and intend to keep " +
        "the floor, thinking aloud or stalling rather than handing over? Cues: '等一下', '我想想', '让我想想', '嗯…', " +
        "'那个…', 'hold on', 'let me think'. Judge the MOST RECENT words: a request for time is released as soon as " +
        "the speaker says something that hands over or moves on. Use transcript_trajectory: an old holding phrase " +
        "followed by new content is no longer holding."
private const val HOLDING_TRUE =
    "The latest words ask for time or deliberation: hedges, fillers, '我想想', '等一下', or a bare lead-in with " +
        "nothing after it that hands over."
private const val HOLDING_FALSE =
    "No such words at the end: the speaker is done, released an earlier 'hold on', moved on to content, or is " +
        "simply mid-sentence without asking for time."

private const val ADDRESSEE_INSTRUCTIONS =
    "The microphone picks up everyone nearby. Judging from the TEXT only, is this utterance directed at the " +
        "assistant$NAME, as opposed to another person nearby, a phone call, the speaker talking to themselves, or " +
        "radio/video audio? Use conversation_history and assistant_last_message: a reply that fits what the " +
        "assistant just said or asked is almost certainly for it, even if short ('第一个', '对', '不用了'). " +
        "Requests the assistant can act on (navigation, music, alarms, lights, phone, apps, settings, " +
        "questions to look up) and anything naming $NAMED are addressed to it. Cues pointing away: another person " +
        "named or addressed ('老婆', '妈', '你吃了吗'), chat between people ('昨天那个电影挺好看的', '晚上吃什么'), " +
        "replying to something the assistant never said, or fragments that read like a call or a broadcast. " +
        "Narration, storytelling, a lecture, lyrics or dialogue that sounds like a video, podcast, audiobook or radio " +
        "is NOT addressed to the assistant, however long it runs. Unlike a phone call with one person, this mic is " +
        "open in a shared space: with no cue either way, lean towards the assistant only when the words are a " +
        "plausible request to it. Judge an unfinished partial by where it is heading; a fragment that cannot yet be " +
        "read as a request is not addressed. If the turn mixes both, judge by the MOST RECENT part."
private const val ADDRESSEE_TRUE =
    "Plausibly for the assistant: it answers, follows up, asks, requests something the assistant can do, or reacts " +
        "to what the assistant said."
private const val ADDRESSEE_FALSE =
    "Meant for someone or something else: addresses another person, is chat between people, responds to a question " +
        "the assistant never asked, reads like overheard call, video or broadcast speech, or is a fragment that is " +
        "not yet any request."

private const val TAKING_FLOOR_INSTRUCTIONS =
    "The assistant is speaking right now (assistant_current_speech is what it is saying) and the microphone produced " +
        "the words in current_user_transcript. Echo cancellation is on but imperfect. From TEXT only, decide whether " +
        "someone is genuinely trying to take the floor, so the assistant should stop talking now, or is merely " +
        "reacting, talking to someone else, or whether the words are the assistant's own voice coming back. " +
        "Backchannels ('嗯', '对', '好的', '哦', 'uh-huh', 'okay') are NOT interruptions. Neither is a fragment that " +
        "repeats or paraphrases what the assistant is saying (residual echo), nor a single ambiguous word, nor side " +
        "talk between other people. Real interruptions: '等一下', '停', '别说了', '不对', '不是', '算了', a new request " +
        "to the assistant, a correction, or several words of new content for it. If the assistant just asked " +
        "something and the words answer it ('第二个', '可以'), that IS taking the floor. Cutting the assistant off on " +
        "a backchannel or its own echo is worse than reacting a moment late."
private const val TAKING_FLOOR_TRUE =
    "A deliberate attempt to stop the assistant or change course, a new request to it, or an answer to what it just " +
        "asked."
private const val TAKING_FLOOR_FALSE =
    "A backchannel, acknowledgement, laugh, side talk, a short ambiguous fragment, or text mirroring the assistant's " +
        "own speech."

private const val REPLACES_TASK_INSTRUCTIONS =
    "The assistant is still working on a task: assistant_current_task is what it was asked, assistant_task_progress " +
        "(when given) what it has done and is doing for it, and it may also be speaking about it. The words in " +
        "current_user_transcript are for the assistant; read them against that progress. Stopping throws the current work " +
        "away, so decide it only when the speaker clearly no longer wants that task: an explicit cancel ('算了', " +
        "'不用了', '先别弄了', '停下来', '别查了'), or a change of mind that makes it pointless ('不去了，改去公司', " +
        "'换成导航回家', '不对，我要的是…' replacing the whole request). Everything else goes on with the task, which " +
        "then takes the words in as well: a new request of any kind, even unrelated ('说一句鼓励我的话', '讲个笑话', " +
        "'再放首歌', '顺便把空调打开', '也帮我订个提醒'), a question about something else ('今天几号', '你是谁'), a " +
        "changed or added detail ('不走高速', '要近一点的', '是南站'), an answer to what the assistant asked, or a " +
        "question about this task's own progress ('查到哪了', '还要多久'). When unsure, the task goes on."
private const val ENDS_SESSION_INSTRUCTIONS =
    "This session was opened by the assistant's wake word (or a long press) and listens hands-free until it is " +
        "dismissed or idle. Decide whether the words in current_user_transcript dismiss the assistant: the speaker " +
        "is done with it and wants it to stop listening and go away ('退下吧', '退出', '没事了', '好了，没事了', " +
        "'再见', '拜拜', '你先休息吧', '不用了，谢谢', '关闭语音'). Not a dismissal: any request or question, even short; " +
        "thanks that come with more to say; cancelling the current task while still talking ('算了', '别查了', " +
        "'不用导航了'), which only stops that task. When unsure, it is not a dismissal."
private const val ENDS_SESSION_TRUE =
    "Dismisses the assistant: the speaker ends the voice conversation and wants it to stop listening."
private const val ENDS_SESSION_FALSE =
    "Not a dismissal: a request, a question, an answer, or cancelling a task while the conversation goes on."
private const val REPLACES_TASK_TRUE =
    "Stops the current task: an explicit cancel, or a change of mind that makes the current task pointless."
private const val REPLACES_TASK_FALSE =
    "The task goes on: any new request or question (it is handled as well), a changed detail or constraint, an " +
        "answer to the assistant, or a question about the task's own progress."

// 闲聊 (owner 2026-10-13): small talk goes to a companion chat service, everything else to the agent. A request
// misread as small talk goes undone, so the question leans hard towards the agent.
private const val SMALL_TALK_INSTRUCTIONS =
    "The words in current_user_transcript are for the assistant$NAME, which is idle. Decide whether they are small " +
        "talk or companionship: chatting, sharing feelings or the day, banter, greetings, compliments, asking its " +
        "opinion, or asking it for a joke, a story or a few kind words ('今天有点累', '你觉得我穿这个好看吗', '讲个笑话', " +
        "'陪我聊会儿', '你喜欢什么颜色'). NOT small talk: anything it must act on or look up: the device and home (闹钟, 蓝牙, 亮度, " +
        "灯, 音量), navigation and places, music, radio or video, calls and messages, reminders and the calendar, " +
        "settings and apps, the screen, and questions about facts, weather, news, time, prices, nearby shops or " +
        "anything with a definite answer to find ('今天几号', '附近有什么好吃的', '帮我放首歌'). Words that mix chat " +
        "with such a request are not small talk. An answer to something the assistant just asked about a task is not " +
        "small talk. When unsure, it is not small talk."
private const val SMALL_TALK_TRUE =
    "Chat or companionship only: feelings, the day, opinions, jokes, stories, greetings; nothing to do, control, " +
        "play, find or look up."
private const val SMALL_TALK_FALSE =
    "A request to act, control, play, navigate, call, remind, look up or show something, a factual question, an " +
        "answer about a task, or a mix that includes any of these."

/** These instructions with [identity]'s name and wake phrase filled in, or worded for an unnamed assistant. */
private fun String.naming(identity: VoiceAssistantIdentity): String {
    val name = identity.name.trim()
    val spokenAs = identity.spokenAs.trim().takeIf { it.isNotEmpty() && name.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    val wake = identity.wakePhrase.trim()
    return replace(CALLED, if (name.isEmpty()) "" else " called $name$spokenAs")
        .replace(WAKE, if (wake.isEmpty()) "" else " 「$wake」")
        .replace(NAME, if (name.isEmpty()) "" else " $name")
        .replace(NAMED, name.ifEmpty { "it" })
}
