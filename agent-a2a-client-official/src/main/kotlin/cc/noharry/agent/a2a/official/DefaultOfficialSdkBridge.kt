package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aAgentCard
import cc.noharry.agent.a2a.A2aClientConfig
import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aSendMessageRequest
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.a2aproject.sdk.client.AndroidClientFactory
import org.a2aproject.sdk.client.Client
import org.a2aproject.sdk.client.ClientEvent
import org.a2aproject.sdk.client.MessageEvent
import org.a2aproject.sdk.client.TaskEvent
import org.a2aproject.sdk.client.TaskUpdateEvent
import org.a2aproject.sdk.client.config.ClientConfig
import org.a2aproject.sdk.client.http.A2ACardResolver
import org.a2aproject.sdk.client.http.AndroidA2AHttpClient
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext
import org.a2aproject.sdk.spec.A2AClientException
import org.a2aproject.sdk.spec.A2AClientHTTPError
import org.a2aproject.sdk.spec.A2AClientJSONError
import org.a2aproject.sdk.spec.A2AError
import org.a2aproject.sdk.spec.AgentCard
import org.a2aproject.sdk.spec.AgentInterface
import org.a2aproject.sdk.spec.CancelTaskParams
import org.a2aproject.sdk.spec.DataPart
import org.a2aproject.sdk.spec.Message
import org.a2aproject.sdk.spec.Part
import org.a2aproject.sdk.spec.Task
import org.a2aproject.sdk.spec.TaskIdParams
import org.a2aproject.sdk.spec.TaskQueryParams
import org.a2aproject.sdk.spec.TaskState
import org.a2aproject.sdk.spec.TextPart

@OptIn(ExperimentalA2aClientApi::class)
internal class DefaultOfficialSdkBridge(
    private val config: A2aClientConfig,
) : OfficialSdkBridge {
    private val activeClients = OfficialResourceRegistry<Client>(Client::close)

    override suspend fun getAgentCard(remote: RemoteAgentRef, headers: Map<String, String>): A2aAgentCard =
        safeCall(discovery = true) { mapCard(resolveCard(remote, headers)) }

    override suspend fun sendMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
        headers: Map<String, String>,
    ): A2aSendMessageResult = safeCall {
        withClient(remote, headers, streaming = false) { client ->
            val event = AtomicReference<ClientEvent?>()
            client.sendMessage(
                mapMessage(request.message),
                listOf(BiConsumer { value, _ -> event.set(value) }),
                null,
                callContext(headers),
            )
            when (val value = event.get()) {
                is MessageEvent -> A2aSendMessageResult.Message(mapMessage(value.message))
                is TaskEvent -> A2aSendMessageResult.Task(mapTask(value.task))
                is TaskUpdateEvent -> A2aSendMessageResult.Task(mapTask(value.task))
                else -> throw OfficialSdkFailure(A2aClientError.Protocol("missing_send_result"))
            }
        }
    }

    override fun streamMessage(
        remote: RemoteAgentRef,
        request: A2aSendMessageRequest,
        headers: Map<String, String>,
    ): Flow<A2aStreamEvent> = stream(remote, headers) { client, consumer, errors ->
        client.sendMessage(mapMessage(request.message), listOf(consumer), errors, callContext(headers))
    }

    override suspend fun getTask(
        remote: RemoteAgentRef,
        taskId: String,
        headers: Map<String, String>,
    ): A2aTask = safeCall {
        withClient(remote, headers, streaming = false) { client ->
            mapTask(client.getTask(TaskQueryParams(taskId), callContext(headers)))
        }
    }

    override suspend fun cancelTask(
        remote: RemoteAgentRef,
        taskId: String,
        headers: Map<String, String>,
    ): A2aTask = safeCall {
        withClient(remote, headers, streaming = false) { client ->
            mapTask(client.cancelTask(CancelTaskParams(taskId), callContext(headers)))
        }
    }

    override fun resubscribeTask(
        remote: RemoteAgentRef,
        taskId: String,
        headers: Map<String, String>,
    ): Flow<A2aStreamEvent> = stream(remote, headers) { client, consumer, errors ->
        client.subscribeToTask(TaskIdParams(taskId, null), listOf(consumer), errors, callContext(headers))
    }

    override fun close() {
        activeClients.close()
    }

    private fun stream(
        remote: RemoteAgentRef,
        headers: Map<String, String>,
        start: (Client, BiConsumer<ClientEvent, AgentCard>, Consumer<Throwable>) -> Unit,
    ): Flow<A2aStreamEvent> = callbackFlow {
        checkOpen()
        val lease = OfficialStreamResourceLease<Client>(::release)
        val callbacks = OfficialStreamCallbacks(
            emitEvent = { sendEvent(it) },
            emitTerminal = { sendTerminal(it) },
            mapFailure = ::mapFailure,
        )
        val worker = launch(Dispatchers.IO) {
            try {
                val client = createClient(resolveCard(remote, headers), streaming = true)
                register(client)
                if (!lease.attach(client)) return@launch
                val consumer = BiConsumer<ClientEvent, AgentCard> { event, _ ->
                    try {
                        val mapped = mapEvent(event)
                        callbacks.onEvent(mapped.event, mapped.terminal)
                    } catch (error: Throwable) {
                        callbacks.onError(error)
                    }
                }
                // The SDK types its handler Consumer<Throwable> (JSpecify) yet signals a clean end with null;
                // the erased cast keeps the null-tolerant handler without a generated parameter null check.
                @Suppress("UNCHECKED_CAST")
                val errors = Consumer<Throwable?> { error ->
                    if (error == null) callbacks.onComplete() else callbacks.onError(error)
                } as Consumer<Throwable>
                lease.runIfActive { client ->
                    if (!activeClients.runIfActive(client) { start(it, consumer, errors) }) {
                        callbacks.onError(OfficialSdkFailure(A2aClientError.Configuration("client_closed")))
                    }
                }
            } catch (error: Throwable) {
                callbacks.onError(error)
            }
        }
        awaitClose {
            callbacks.cancel()
            worker.cancel()
            lease.cancel()
        }
    }.buffer(config.maxStreamBufferEvents)

    private fun ProducerScope<A2aStreamEvent>.sendEvent(event: A2aStreamEvent): Boolean {
        val result = trySend(event)
        if (result.isSuccess) return true
        if (!result.isClosed) close(OfficialSdkFailure(A2aClientError.Protocol("stream_buffer_overflow")))
        return false
    }

    private fun ProducerScope<A2aStreamEvent>.sendTerminal(event: A2aStreamEvent) {
        if (sendEvent(event)) close()
    }

    private suspend fun <T> safeCall(discovery: Boolean = false, block: () -> T): T = try {
        checkOpen()
        runInterruptible(Dispatchers.IO) { block() }
    } catch (failure: OfficialSdkFailure) {
        throw failure
    } catch (error: Throwable) {
        throw OfficialSdkFailure(mapFailure(error, discovery))
    }

    private fun <T> withClient(
        remote: RemoteAgentRef,
        headers: Map<String, String>,
        streaming: Boolean,
        block: (Client) -> T,
    ): T {
        val client = createClient(resolveCard(remote, headers), streaming)
        register(client)
        return try {
            block(client)
        } finally {
            release(client)
        }
    }

    private fun resolveCard(remote: RemoteAgentRef, headers: Map<String, String>): ValidatedCard {
        val builder = A2ACardResolver.builder()
            .httpClient(AndroidA2AHttpClient())
            .baseUrl((remote.cardUrl ?: remote.endpoint).toString())
            .authHeaders(headers)
        remote.cardUrl?.rawPath?.takeIf { it.isNotBlank() }?.let(builder::agentCardPath)
        val card = builder.build().agentCard
        return when (
            val result = OfficialAgentCardPolicy.validate(
                remote,
                card,
                config.allowLoopbackHttpForDebug,
            )
        ) {
            is OfficialAgentCardPolicy.Result.Accepted -> ValidatedCard(card, result.agentInterface)
            is OfficialAgentCardPolicy.Result.Rejected -> throw OfficialSdkFailure(result.error)
        }
    }

    private fun createClient(validated: ValidatedCard, streaming: Boolean): Client {
        val transport = JSONRPCTransport(
            AndroidA2AHttpClient(),
            validated.card,
            validated.agentInterface,
            emptyList(),
        )
        val clientConfig = ClientConfig.Builder()
            .setStreaming(streaming)
            .setUseClientPreference(true)
            .build()
        return AndroidClientFactory.create(validated.card, clientConfig, transport)
    }

    private fun register(client: Client) {
        if (!activeClients.register(client)) {
            throw OfficialSdkFailure(A2aClientError.Configuration("client_closed"))
        }
    }

    private fun release(client: Client) {
        activeClients.release(client)
    }

    private fun checkOpen() {
        if (!activeClients.isOpen()) throw OfficialSdkFailure(A2aClientError.Configuration("client_closed"))
    }

    private fun callContext(headers: Map<String, String>): ClientCallContext =
        ClientCallContext(emptyMap(), headers)

    private fun mapCard(validated: ValidatedCard): A2aAgentCard {
        val card = validated.card
        return A2aAgentCard(
            name = card.name(),
            description = card.description(),
            url = URI(validated.agentInterface.url()),
            protocolVersion = null,
            streaming = card.capabilities().streaming(),
        )
    }

    private fun mapMessage(message: A2aMessage): Message {
        val builder = Message.builder()
            .role(if (message.role == A2aMessage.Role.USER) Message.Role.ROLE_USER else Message.Role.ROLE_AGENT)
            .parts(message.parts.map(::mapPart))
        message.messageId?.let(builder::messageId)
        message.contextId?.let(builder::contextId)
        message.taskId?.let(builder::taskId)
        return builder.build()
    }

    private fun mapMessage(message: Message): A2aMessage = A2aMessage(
        messageId = message.messageId(),
        role = if (message.role() == Message.Role.ROLE_USER) A2aMessage.Role.USER else A2aMessage.Role.AGENT,
        parts = message.parts().map(::mapPart),
        contextId = message.contextId(),
        taskId = message.taskId(),
    )

    private fun mapPart(part: A2aPart): Part<*> = when (part) {
        is A2aPart.Text -> TextPart(part.text)
        is A2aPart.Data -> DataPart(mapValue(part.value))
    }

    private fun mapPart(part: Part<*>): A2aPart = when (part) {
        is TextPart -> A2aPart.Text(part.text())
        is DataPart -> A2aPart.Data(mapValue(part.data()))
        else -> throw OfficialSdkFailure(A2aClientError.Protocol("unsupported_official_part"))
    }

    private fun mapValue(value: A2aValue): Any = when (value) {
        A2aValue.Null -> throw OfficialSdkFailure(A2aClientError.Protocol("null_data_part_unsupported"))
        is A2aValue.BooleanValue -> value.value
        is A2aValue.NumberValue -> value.value
        is A2aValue.StringValue -> value.value
        is A2aValue.ListValue -> value.values.map(::mapValue)
        is A2aValue.ObjectValue -> value.values.mapValues { mapValue(it.value) }
    }

    private fun mapValue(value: Any?): A2aValue = when (value) {
        null -> A2aValue.Null
        is Boolean -> A2aValue.BooleanValue(value)
        is Number -> A2aValue.NumberValue(value.toDouble())
        is String -> A2aValue.StringValue(value)
        is List<*> -> A2aValue.ListValue(value.map(::mapValue))
        is Map<*, *> -> A2aValue.ObjectValue(value.entries.associate { entry ->
            val key = entry.key as? String
                ?: throw OfficialSdkFailure(A2aClientError.Protocol("non_string_data_key"))
            key to mapValue(entry.value)
        })
        else -> throw OfficialSdkFailure(A2aClientError.Protocol("unsupported_data_value"))
    }

    private fun mapTask(task: Task): A2aTask = A2aTask(
        id = task.id(),
        contextId = task.contextId(),
        status = mapState(task.status().state()),
        statusMessage = task.status().message()?.let(::mapMessage),
    )

    private fun mapState(state: TaskState): A2aTask.Status = when (state) {
        TaskState.TASK_STATE_SUBMITTED -> A2aTask.Status.SUBMITTED
        TaskState.TASK_STATE_WORKING -> A2aTask.Status.WORKING
        TaskState.TASK_STATE_INPUT_REQUIRED,
        TaskState.TASK_STATE_AUTH_REQUIRED,
        -> A2aTask.Status.INPUT_REQUIRED
        TaskState.TASK_STATE_COMPLETED -> A2aTask.Status.COMPLETED
        TaskState.TASK_STATE_CANCELED -> A2aTask.Status.CANCELLED
        TaskState.TASK_STATE_FAILED,
        TaskState.TASK_STATE_REJECTED,
        -> A2aTask.Status.FAILED
        TaskState.UNRECOGNIZED -> A2aTask.Status.UNKNOWN
    }

    private fun mapEvent(event: ClientEvent): MappedEvent = when (event) {
        is MessageEvent -> MappedEvent(A2aStreamEvent.Message(mapMessage(event.message)), terminal = true)
        is TaskEvent -> mappedTaskEvent(event.task)
        is TaskUpdateEvent -> mappedTaskEvent(event.task)
    }

    private fun mappedTaskEvent(task: Task): MappedEvent = MappedEvent(
        event = A2aStreamEvent.Task(mapTask(task)),
        terminal = task.status().state().isFinal,
    )

    private fun mapFailure(error: Throwable, discovery: Boolean = false): A2aClientError {
        if (error is OfficialSdkFailure) return error.error
        val http = error.causes().filterIsInstance<A2AClientHTTPError>().firstOrNull()
        if (http?.code == HTTP_UNAUTHORIZED || http?.code == HTTP_FORBIDDEN) {
            return A2aClientError.Authentication("authentication_rejected")
        }
        val rpc = error.causes().filterIsInstance<A2AError>().firstOrNull()
        if (rpc?.code != null) return A2aClientError.Rpc(rpc.code)
        return when {
            error.causes().any { it is SocketTimeoutException || it is InterruptedIOException } ->
                A2aClientError.Timeout()
            error.causes().any { it is A2AClientJSONError } ->
                A2aClientError.Protocol("invalid_protocol_document")
            discovery -> A2aClientError.Discovery("card_discovery_failed")
            error is A2AClientException -> A2aClientError.Transport("official_sdk_call_failed")
            else -> A2aClientError.Protocol("unexpected_official_sdk_failure")
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private data class MappedEvent(val event: A2aStreamEvent, val terminal: Boolean)

    private data class ValidatedCard(
        val card: AgentCard,
        val agentInterface: AgentInterface,
    )

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
