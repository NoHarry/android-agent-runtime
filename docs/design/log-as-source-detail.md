# The session log as the source: detailed design

Status: design for implementation. Overview and rationale: `log-as-source.md`. This document is the build spec: an
implementer of one unit (section 12) reads sections 1 and 2, the sections its unit names, and nothing else needs
deciding. Everything here ships in one release: SDK 0.6.0 and the Bothy release built on it.

Paths: `R/` is `agent-runtime/src/main/kotlin/cc/noharry/agent/`, `API/` is `agent-api/src/main/kotlin/cc/noharry/agent/`,
`AS/` is `agent-app-server/src/main/java/cc/noharry/agent/appserver/`, `P/` is
`agent-app-protocol/src/main/kotlin/cc/noharry/agent/appserver/protocol/`, `B/` is Bothy's
`workspace/apps/android/` (repository `bothy-api`), `PL/x` is `B/plugins/agent-app-x/`. Line numbers are as of SDK
`8a08a6b` and Bothy `65cf8513`.

## The decisions in one page

- **One log per thread**: `threads/<sha256(threadId)>/log/*.jsonl` under the runtime's storage root, one JSON record
  per line, one sequence (`seq`) per thread from 0, runs and recorded turns as spans of it. A second log, the **host
  stream**, takes facts that belong to no thread. Both are append-only; nothing else holds conversation state.
- **Records are typed by dotted names** (`tool.call`, `message`, ...). The catalogue in section 1.12 is closed: an
  unknown type is refused unless the record says it may be ignored (`fact` and `diag` always may).
- **The request is a header plus a surface.** The header (system prompt parts, tools, model, cache key, tool choice,
  image view, the client's wire profile) is logged when it changes; the surface is the list of model messages, built
  from `message` records and changed only by logged `history.edit` records. The request sent to the model is rebuilt
  from the projection of the log, so it cannot differ from what the log says; a digest of it is logged with every
  `model.request`. A test rebuilds every request of a scenario suite byte-equal from the log prefix.
- **The model's output is logged as it arrived**: blocks (reasoning with its signature, text, server search), each
  call with its raw argument string and both its written and unique ids, then the response (usage, finish, status).
- **Every reader is a projection**: history, tool activation, thread state and approvals, items and turns, the
  conversation list, usage, runs (outcome, evidence, obligations, budget), todo, sub-agents, facts; the app server's
  transcript and deliveries; each plugin's own. Snapshots carry a version and the `seq` and hash of the record they
  reflect; anything that does not match is rebuilt.
- **Appends that fail stop the work**: the turn fails with `STORAGE_FAILED`, the log is reopened from disk. The log is
  fsynced before a model request, before an acting tool, at a turn's end and before any write a caller waits on, in
  group commits shared by all threads.
- **Credentials are redacted in place** (values the host names as secrets, everywhere; credential patterns in
  diagnostics only), never a reason to drop a record.
- **Opening repairs**: a torn last line is cut; open work (a round without its messages, a running tool, a pending
  approval, an open span) gets closers appended before the thread is used. A pending approval does not survive a
  process death.
- **The app server keeps no conversation files**: `turns.jsonl`, `meta.json`, the session query files, the
  usage ledger, `subagents.jsonl` and the transcript restore go. `ConversationItem.id` stays a `Long`, now derived from
  the log (`seq shl 12 or part`) and stable across restarts; the protocol moves to version 2 with additive fields only.
- **Turns without a run** (companion, 豆包 and spoken plan lines, startup failures) are recorded spans
  (`AgentThread.record`), and with `forModel` they enter the model's history, replacing the "latest external
  exchange" prompt block.
- **Plugins and the voice layer append namespaced facts** (`PluginContext.facts`), to a thread or the host stream,
  idempotent by key and ordered before the tool result of the call that made them; deliveries are logged, so a plan,
  sub-agent or A2A report that was due survives a restart. Plugin files become caches of plugin projections; the
  person's memory and settings stay where they are.
- **Sub-agents are threads** whose logs name their parent; the parent's log has the catalogue and the deliveries.
- **Old data is imported once** per thread on first open, into a staged log renamed into place, then the old files
  are deleted; 0.5 event streams are archived beside the log for the debug trajectory, not imported.

## 1. The log

### 1.1 What is in it and what is not

In the log: everything the system knows about a conversation. The thread's creation and options, every turn's input
(as the model got it and as the person saw it), every model request (header changes and a digest), every block and
call of every response, every tool start and result, every message that enters the model's history and every edit of
it, approvals, questions, steering, notices, obligations, todo lists, tool activations, usage (the turn's and side
calls'), sub-agent lifecycle, recorded turns, host and plugin facts, diagnostics the runtime keeps per run.

Not in it, by design:

- Live deltas (text, reasoning and exec output as they stream). They are emitted to collectors and folded into a
  `model.block` or `tool.result` when complete. A process death loses an unfinished block's text, as in dsh.
- Bytes over 32 KiB of any string field and every image: they are blobs (1.5), named by hash in the record.
- Settings, credentials, model files, installed plugins, the person's memory book, the runtime's learned lessons
  (`memory/lessons/`), prices, the plan watcher's sensor state, audio. These are not conversation information
  (`log-as-source.md`).

### 1.2 Storage layout

`AgentRuntimeBuilder.storage(dir)` names the root; it is the only storage option (`sessionEventLogRoot` and
`sessionEventLog` go, section 9). On Android it is in no-backup storage: `AndroidAgentRuntime.builder` keeps
`noBackupFilesDir/agent-runtime/conversations`
(`agent-runtime-android/src/main/kotlin/cc/noharry/agent/runtime/android/AndroidAgentRuntime.kt:35-36`); the app server
uses `noBackupFilesDir/app-server` (section 5.1).

```
<root>/
  threads/
    <sha256 hex of the UTF-8 thread id>/      one directory per thread (the id itself is in the log)
      lock                                    advisory lock file, held by the writer (1.9)
      log/
        0000000000.jsonl                      segments, named by the seq of their first record, 10 digits
        0000041873.jsonl
      blobs/<first 2 hex>/<sha256>            content-addressed bytes (1.5)
      cache/<projection id>.json              projection snapshots (3.2); deletable at any time
      legacy/v2/...                           archived 0.5 event streams of an imported thread (8.2)
  host/
    lock, log/, blobs/, cache/                the host stream (1.11), same format
  index/
    threads.json                              conversation list index (3.3, P5); deletable
    usage.json                                usage index (3.3, P6); deletable
  .staging/<uuid>/                            a new or imported thread being built (1.10, 8)
  .trash/<uuid>/                              a deleted thread being removed (5.6)
```

A new thread is built in `.staging/<uuid>/` (segment 0 with its header line and `thread.created`, fsynced), then
renamed to `threads/<sha>/` and the `threads/` directory fsynced. If the target exists the thread already exists and
the staged directory is removed; creating a thread twice with one id returns the existing one, as `ThreadStore.create`
does today (`R/runtime/thread/ThreadStore.kt:52-57`). At startup the runtime removes `.staging/*` and `.trash/*`.

### 1.3 Segments and the record envelope

A segment is UTF-8 text. Its first line is a header, every other line one record, every line ends with `\n`:

```json
{"log":"agent-thread","v":3,"thread":"debug-session-1731628800123","first":0,"at":1731628800123}
{"seq":0,"t":1731628800123,"type":"thread.created","data":{...}}
{"seq":7,"t":1731628801502,"type":"tool.result","span":"9f2c...","turn":"req-42","cause":[5],"data":{...}}
{"seq":8,"t":1731628801503,"type":"fact","span":"9f2c...","turn":"req-42","ign":1,"data":{"ns":"plans",...}}
```

| Key | Type | Meaning |
|---|---|---|
| `seq` | Long | Position in the thread, contiguous from 0 across segments |
| `t` | Long | Wall clock, epoch ms, when appended |
| `type` | String | Record type (1.12) |
| `span` | String? | The run or recorded turn the record belongs to; absent for thread-level records |
| `turn` | String? | Public turn id of that span |
| `cause` | Long[]? | Earlier seqs this record answers (a result its call, a decision its ask) |
| `ign` | 1? | Present on records a reader may skip when it does not know the type |
| `data` | Object | The payload, keys in the order the codec declares |

Keys are always written in this order and absent keys are omitted, so a record's line is a pure function of the record
(`LogCodec.encode`). A segment rolls over at 4 MiB, at a record boundary: the next record starts a new segment whose
header has `first` equal to its seq. The host stream's header has `"log":"agent-host"` and no `thread`.

### 1.4 Sequence, spans and turns

- `seq` is allocated by the writer at append, under the thread's lock: `last + 1`. Record 0 is always
  `thread.created`.
- A **span** is one execution: a model run (today's execution id, `R/runtime/thread/ThreadExecution.kt:35`, which the
  v2 log called `runId`) or a recorded turn. It opens with `run.started` and closes with `run.finished`. Imported
  history messages carry span ids `i-<n>` (one per old run) without `run.*` records; they only group messages. A thread has at most one open span: the scheduler already serialises a session's runs
  (`R/kernel/core/state/SessionRunScheduler.kt`), and a run waiting for approval is still that session's owner. A
  `CANCEL_AND_REPLACE` turn's `run.started` is appended only after the replaced span's `run.finished`.
- A **turn** is the public turn id. It has one or more spans: a retry after process loss is a new span of the same
  turn (`run.started.attempt` counts them); an approval continuation is the same span; an answer to a blocking
  question is a new turn whose span names the span it continues (`run.started.continues`).
- Records outside a span (`thread.*`, `fact` appended by the host while nothing runs, `subagent.settled` of a child
  finishing after its parent's run) have no `span`.

### 1.5 Payload encoding, references and blobs

- JSON with `kotlinx.serialization`'s encoder, compact. Runtime records use integers only (durations in ms); a host
  fact's body is `SessionJson`, whose numbers keep their literal text (`API/spi/SessionJson.kt`).
- **Blobs.** A string field longer than 32 KiB (UTF-8) is written as `{"$blob":"<sha256>","n":<bytes>}` and its
  bytes go to `blobs/`. Images are always blobs: `{"sha256","mediaType","bytes","sourcePath","detail"}`. Attachments
  (`SessionAttachmentRef`) are blobs of the thread. A blob is written (temp file, fsync, rename, directory fsync) by
  the flusher before the line that names it, in the same batch (1.7); a blob that exists is not rewritten. Blobs are
  never deleted except with their thread.
- **References.** Only in `message` records (1.12, surface), three fields may name an earlier record instead of
  repeating its value: `content` (`{"$ref":<seq>}`: the `content` of a `tool.result` or the `text` of a
  `user.message`/`input.received`), `args` (the `arguments` of a `tool.call`), `reasoning` (the `text` of a
  `model.block`). The writer emits a reference only when the value is byte-equal to the named field and the named
  record is in the same span; otherwise it writes the value. This keeps a tool result's content in the log once.

### 1.6 Redaction

Redaction never drops a record and never changes its shape.

- **Known secrets.** `AgentRuntimeBuilder.redact(values: () -> Collection<String>)` (and
  `PluginContext.redact`, 6.2) name the values that are credentials: model service keys, MCP and A2A tokens, the
  image service key. The set is read at most once a second. Every occurrence of a value of 8 characters or more, in
  any string of any record, model-visible or not, becomes `[redacted]`. Because the record is redacted before it
  enters memory or the projections, what the model is sent afterwards is the redacted form: a key the person pastes is
  never stored and never resent. Exactness (section 2) holds by construction.
- **Credential patterns** (`sk-[A-Za-z0-9_-]{16,}`, `Bearer\s+\S+`, `Authorization:\s*\S+`, `api[_-]?key=\S+`)
  are redacted only in `diag` records, in facts of kind `transport` (any namespace) and in `error.message` fields,
  which the model never reads. Model-visible content and tool schemas are never pattern-redacted: a schema with a
  `password` property is kept whole.
- The v2 rule that rejected a whole event for a credential-like key or value
  (`R/runtime/eventlog/SessionEventPayloadCodec.kt:317-384, 500-517`) goes.
- Redaction is applied to the encoded line (secrets are plain tokens; JSON escaping does not split them). When it
  replaced anything, the record is decoded again from the redacted line, and that decoded record is the one kept in
  memory, so memory and disk are equal.

### 1.7 Writing

```kotlin
internal class ThreadLog {                                 // R/runtime/log/ThreadLog.kt
    val threadId: String
    val lastSeq: Long                                      // last appended (in memory)
    val durableSeq: Long                                   // last fsynced
    val state: LogState                                    // OPEN, FAILED, READ_ONLY, CLOSED

    /** Appends one record; synchronous, never suspends, never blocks on I/O. Throws LogFailedException. */
    fun append(type: RecordType, data: RecordData, span: SpanRef? = null, cause: LongArray = EMPTY): LogRecord
    /** Returns once every record up to [seq] is durable. Throws LogFailedException. */
    suspend fun flush(seq: Long = lastSeq)
    fun <S : Any> read(projection: Projection<S>): S       // under the thread lock, a short critical section
    fun subscribe(listener: (LogRecord) -> Unit): AutoCloseable
}
```

`append`, under the thread's lock: check `OPEN`; allocate `seq`; encode; redact (1.6); move long strings to pending
blobs; add the record to the in-memory tail; apply every registered projection (section 3); enqueue the line and its
blobs for the flusher; release the lock; call listeners. Listeners must not append to the same log from inside the
call (they queue work instead); `append` from a listener throws `IllegalStateException`.

**Group commit.** One flusher thread per process (`LogFlusher`) serves every log of every root. It takes all queued
lines (up to 4 MiB a batch), and for each log in the batch writes its blobs, then its lines with one
`FileChannel.write`, then `force(false)`; a segment created in the batch also fsyncs the `log/` directory. It then
raises `durableSeq` and resumes the waiters. When nobody waits for a flush it lingers up to 50 ms for more records;
a flush request wakes it at once.

**Failure.** An exception while writing log L (disk full, I/O error): the flusher truncates L's active segment to its
size before the batch and forces it, drops L's queued lines, sets L to `FAILED(cause)`, and fails L's waiters with
`LogFailedException(code = "STORAGE_FAILED")`. From then on every `append` to L throws. The records after
`durableSeq` existed only in memory and are gone with the instance: the registry evicts L, and the next use of the
thread opens it again from disk, where recovery (section 4) closes the span. The runtime treats `LogFailedException`
from any append or flush as fatal to the turn: the loop stops without further appends, the turn ends with
`TurnFailed(TurnError("STORAGE_FAILED", ..., recoverable = true))`, and no request or tool that was about to start
starts. Other threads are unaffected.

### 1.8 Flush points

`flush()` is called, and the work waits for it, at exactly these points:

| # | After appending | Before |
|---|---|---|
| F1 | `thread.created` | returning the new `AgentThread` |
| F2 | `model.request` (and the header records before it) | `ModelClient.complete` |
| F3 | `tool.started` of a call that is not read-only (any tool except native reads, `search_tools`, `web_search`, `update_todo`, and backend tools whose descriptor says `READ_ONLY`) | running the tool |
| F4 | `run.finished` | emitting `TurnCompleted`/`TurnFailed`, releasing the session's lane |
| F5 | `approval.asked` and its placeholder | emitting the approval item |
| F6 | `input.received` | returning `SteerResult.ACCEPTED` |
| F7 | a `fact` appended with `durable = true` (the default), `usage.recorded`, `delivery.*` | returning from the append call |
| F8 | the last record of an import, of a repair, of `AgentThread.record` | returning |
| F9 | any record | writing a projection snapshot that covers it (3.2) |
| F10 | `run.started` and the turn's `user.message` | emitting `TurnStarted` |

Everything else becomes durable within the linger window or with the next flush. A record that is not durable when
the process dies is lost with everything after it; no record that some action depended on can be in that tail.

### 1.9 Single writer

- In a process: `ThreadLogs` (`R/runtime/log/ThreadLogs.kt`) is a registry keyed by canonical root and thread id
  holding at most one `ThreadLog` each. Every runtime built on the same root shares it: the app server builds a
  runtime per run and they all write through the one instance. A log with no open span, no subscriber and no use for
  5 minutes is closed (its projections snapshotted, its lock released).
- Across processes: opening for write takes `FileChannel.tryLock()` on `lock` and holds it until close. A second
  process that finds it held opens the log `READ_ONLY` (durable records only, closers applied in memory only, no
  turns, `LOG_LOCKED`). The host stream has its own lock.

### 1.10 Opening

1. Take the lock (1.9).
2. List `log/`, sorted. Read each segment's header: `log` and `thread` must match, `v` must be 3, `first` must equal
   the next expected seq.
3. Read records from the first segment needed (3.2: from the lowest snapshot's `from`, or 0). In the last segment, a
   final line without `\n` or that does not parse is a torn tail: truncate the file to the end of the last good line
   and force it, and remember the cut for `log.repaired`.
4. Any other bad line, a seq that is not `previous + 1`, or a segment whose `first` is wrong: the log is `CORRUPT`. It
   opens `READ_ONLY` up to the last good record and `logStatus` reports `CORRUPT` with that seq; turns fail with
   `LOG_CORRUPT`. Nothing is rewritten.
5. A record whose `type` this build does not know and that has no `ign`: `NEWER_FORMAT`, opened `READ_ONLY` the same
   way. A known `type` with a field this build does not know: the field is ignored (records are extended only by
   optional fields; 10.2 enforces this).
6. Load projections from snapshots and fold the rest (3.2).
7. Repair (section 4), flush, and the log is `OPEN`.

Format versions: v3 is this document. Every older form (0.5's per-run v2 streams, the thread store's JSON files, the
app server's files) is imported (section 8), never read in place. A future v4 is written by migrating into
`.staging/` and renaming over `log/` once verified; v3 segments are never edited in place.

### 1.11 The host stream

`<root>/host/` is a log with the same envelope, writer and flush rules, for facts that belong to no thread: a plan
fired while no conversation is open, plan book edits made on a page, A2A delegations being polled, deliveries waiting
for a thread, usage of side calls made outside any conversation, imported usage. Record 0 is `host.created`. It has no
spans. Hosts reach it through `AgentRuntime.hostLog` (6.1) and plugins through `PluginContext.facts` with a null
thread (6.2).

### 1.12 Record catalogue

Every record type, its payload and its writer. `?` marks an optional field; `ModelRefJson` is
`{"provider","model","variant"?}`; `UsageJson` is `{"in","out","reasoning","cacheRead","cacheWrite"}` (Longs);
`ErrorJson` is `{"code","message","recoverable"}`. Payload classes live in `R/runtime/log/records/` with one codec
each; the public mirror is `SessionEventPayload` (section 9).

**Thread**

| Type | Payload | Written by, when |
|---|---|---|
| `thread.created` | `id`, `createdAt`, `origin`: `USER`/`SUBAGENT`/`IMPORTED`/`EXTERNAL`, `options`: `{model?: ModelRefJson, workspace?, scene?, instructions?, approvalPolicy}`, `parent?`: `{thread, seq, callId, depth}` | `startThread`, sub-agent spawn, import; seq 0. `EXTERNAL`: a thread the app server opens for another agent (A2A inbound tasks) |
| `thread.options` | `set`: same keys as `options`, `reset`: `[MODEL\|WORKSPACE\|SCENE\|INSTRUCTIONS]` | `resumeThread` with changed options |
| `thread.title` | `title`, `source`: `AUTO`/`USER`/`HOST` | host (the app server's first-turn rule, 5.6) |
| `thread.imported` | `from`: `agent-runtime-0.5`/`app-server-0.5`, `counts`: `{turns, messages, images}`, `legacy?`: `"legacy/v2"`, `warnings`: [String] | importer, after the imported records |
| `import.turn` | `source`: `SDK`/`APP_SERVER`, `turn`: the old turn snapshot as stored (`TurnResult` JSON, or a `SessionTurn` row) | importer, one per old turn |

**Spans**

| Type | Payload | Written by, when |
|---|---|---|
| `run.started` | `kind`: `MODEL`/`RECORDED`, `attempt`, `tag?` (host's kind of turn), `continues?`: span, `model?`, `scene?`, `workspace?`, `approvalPolicy` (in force), `ledgerSalt?` | admission of a run; `AgentThread.record` |
| `run.status` | `status`: `QUEUED`/`RUNNING`/`WAITING_FOR_APPROVAL`/`WAITING_FOR_ANSWER`/`RESUMED` | every status change (`RunStateController`) |
| `run.finished` | `status`: `COMPLETED`/`WAITING_FOR_ANSWER`/`INTERRUPTED`/`FAILED`, `finalResponse`, `error?`, `completion?`: `{outcome, requiredTotal, requiredSucceeded, requiredUnresolved, unresolvedCodes, digest}`, `result`: `RuntimeResult` JSON (`R/runtime/reliability/ReliabilityJsonCodecs.kt`), `rounds`, `toolCalls`, `usage`: UsageJson, `recovered?`: true | terminal path; repair |

**Input**

| Type | Payload | Written by, when |
|---|---|---|
| `user.message` | `text` (the model input), `shown?` (as the person gave it, when different), `parts`: [`{"att": SessionAttachmentRef}` or `{"img": image blob}`], `origin`: `TURN`/`ANSWER`, `answering?`: question item id | at `run.started` of a turn with input |
| `input.received` | `requestId`, `text`, `shown?`, `answering?` | steering accepted into the queue (`RuntimeSteeringQueue`) |
| `input.consumed` | `requestId`, `request`: seq of the `model.request` whose response consumed it | when the model completed that response |
| `input.dropped` | `requestId`, `reason` (code) | queue closed with the input unconsumed; repair |

**Requests**

| Type | Payload | Written by, when |
|---|---|---|
| `request.header` | `reason`: `INITIAL`/`CHANGE`/`FALLBACK`/`IMPORT`, `system`: [part], `tools`: [ToolJson], `model?`, `cacheKey?`, `toolChoice?`, `imageView`: `{enabled, keepNewest}`, `wire`: String | before a `model.request` whose header differs in anything but tools (2.2) |
| `request.tools` | `added`: [ToolJson], `removed`: [name], `order?`: [name] | before a `model.request` whose tools differ |
| `model.request` | `purpose`: `TURN`/`COMPACTION`, `round`, `attempt`, `digest`: `"sha256:<hex>"`, `messages`, `bytes`, `compaction?`: `{from, to, template}` | before `complete`; F2 |

A system part is `{"text": String}` or `{"same": n}` (part `n` of the previous header, unchanged). A ToolJson is
`{"name", "schema": <raw JSON text>, "description"?, "deferred"?: true}`.

**Responses**

| Type | Payload | Written by, when |
|---|---|---|
| `model.block` | `request`: seq, `index`, `kind`: `REASONING`/`TEXT`/`SEARCH`, `text?`, `signature?`, `search?`: `{provider, query, results?}` | when the block closes (2.3) |
| `tool.call` | `request`, `index`, `callId` (as made unique), `writtenId?` (as received, when different), `name`, `arguments` (raw string as received) | when the call arrives in the stream |
| `model.response` | `request`, `status`: `COMPLETED`/`FAILED`/`CANCELLED`, `blocks`, `finish?`, `usage`: UsageJson, `model?`, `fingerprint?`, `flushMs?` (the wait at F2 before the request), `firstEventMs?`, `durationMs`, `error?`, `replay?`: SessionJson | stream end, failure or cancellation; repair |
| `model.retry` | `request`, `attempt` (the next), `delayMs`, `model?`, `fallback?` | before waiting to retry |

**Surface** (the model's history; section 2)

| Type | Payload | Written by, when |
|---|---|---|
| `message` | `origin` (below), `m`: `{role, content \| content:{$ref}, images?, toolCallId?, toolName?, args? \| args:{$ref}, signature?, reasoning? \| reasoning:{$ref}, said?, additions?: [name]}` | wherever the loop adds a message to its history today (2.3) |
| `history.edit` | `op`: `REPLACE`/`INSERT_AFTER`/`STRIP_IMAGES`, `from`: NodeId, `to?`: NodeId, `messages?`: [`{origin, m}`], `reason`: `COMPACTION`/`TRIM`/`CLOSER`/`APPROVAL`/`IMPORT` | compaction, trimming, approval outcomes, repair |

`m` mirrors `ModelMessage` (`API/spi/model/ModelClient.kt:70-104`): `said` is `assistantText`, `signature` is
`thinkingSignature`, `reasoning` is `reasoningContent`, `additions` is `toolAdditions`. `origin` is one of `INPUT`,
`RUN_CONTEXT`, `STEER`, `ANSWER`, `NOTICE`, `CORRECTION`, `TOOL_ANNOUNCE`, `ROUND`, `FINAL`, `FAILURE_NOTICE`,
`CANCEL_NOTICE`, `PLACEHOLDER`, `CLOSER`, `EXTERNAL`, `IMPORTED`, `SUMMARY`; it is for readers and does not change the
message. A NodeId is `"<seq>"` for a `message` record and `"<seq>.<i>"` for the i-th message an edit inserted.

**Tools**

| Type | Payload | Written by, when |
|---|---|---|
| `tool.started` | `callId`, `name` (as run), `arguments?` (as run, when different from the call as written: `call_tool` unwrapped, a host's note taken off), `source` (`DynamicToolSource`), `server?`, `readOnly`, `approvedBy?`: `USER`/`REMEMBERED`/`AUTOMATIC` | before the tool body; F3 |
| `tool.result` | `callId`, `name`, `content` (raw, model-visible), `images?`, `ok`, `code?`, `status?`, `retryable?`, `durationMs`, `network?`: `{domain, decision, status?, truncated}`, `file?`: `{path, added, removed, bytes}`, `output?` (exec output for display) | when the tool returns |
| `tools.activation` | `activated`: [`{name, server?, source, schema: sha256}`], `unavailable`: [name] | `ToolActivationStore` changes |

**Interaction**

| Type | Payload | Written by, when |
|---|---|---|
| `question.asked` | `callId?`, `text`, `choices`, `async`, `expiresAt?` | `ask`/`ask_async` |
| `question.closed` | `question`: seq, `by`: `ANSWER`/`EXPIRED`/`CANCELLED`/`PROCESS_ENDED`, `answer?`: seq | answer, expiry, cancel, repair |
| `approval.asked` | `callId`, `tool`, `arguments`, `domain?`, `reason`, `alwaysAsk`, `policy` (in force), `expiresAt` | an approval gate that has to ask; F5 |
| `approval.decided` | `asked?`: seq, `callId?` (absent only for imported remembered domains), `state`: `APPROVED`/`DENIED`/`EXPIRED`/`CANCELLED`, `by`: `USER`/`REMEMBERED`/`AUTOMATIC`/`POLICY`/`EXPIRY`/`PROCESS_ENDED`, `scope?`: `ONCE`/`THREAD`, `remember?`: domain, `reason?` | every decision, including those made without asking |

**Loop**

| Type | Payload | Written by, when |
|---|---|---|
| `loop.notice` | `kind`: `BUDGET_LOW`/`COMPACTION`/`WORKSPACE_CHANGED`/`EMPTY_COMPLETION`/`BLOCKED`/`REPEAT_CALL`, `details`: Object | where the public notice events are emitted today |
| `obligation` | `id`, `action`, `status`, `commit`, `code?` | every `RuntimeObligationLedger` change (`R/kernel/core/loop/RuntimeObligationLedger.kt:39-80`) |
| `todo.set` | `items`: [`{content, status}`], `reason`: `TOOL`/`CLOSED_ON_SUCCESS` | `update_todo`; `closeChecklistOnSuccess` |
| `usage.recorded` | `purpose`, `model`, `usage`: UsageJson, `requests`, `about?`: `{turn?, span?}` | side calls (6.1) |

**Sub-agents** (section 7)

| Type | Payload | Written by, when |
|---|---|---|
| `subagent.spawned` | `child`: thread id, `agent?`, `label`, `task`, `callId` | `spawn_agent`, in the parent |
| `subagent.settled` | `child`, `state`: `FINISHED`/`FAILED`/`CANCELLED`/`STALLED`/`INTERRUPTED`, `result?`, `error?`, `childSeq?`, `usage` | child ends; restart |
| `subagent.delivered` | `children`: [thread id], `via`: `WAIT_AGENT`/`REPORT`, `callId?`, `turn?` | the parent's model receives the result |

**Facts, diagnostics, repair** (all but `log.repaired` carry `ign`)

| Type | Payload | Written by, when |
|---|---|---|
| `fact` | `ns`, `kind`, `key?`, `body`: SessionJson, `about?`: `{thread?, callId?, turn?, span?}` | hosts and plugins (section 6) |
| `diag` | `kind`: `TRACE`/`WORKPAD`/`HANDOFF`/`CONFUSION`/`EVIDENCE`/`TIMING`, `body` | the reliability storage (3.3, P7) |
| `log.repaired` | `tornBytes`, `closed`: `{calls, approvals, questions, inputs, spans}` | repair (section 4) |

**Host stream only**

| Type | Payload |
|---|---|
| `host.created` | `createdAt` |
| `delivery.queued` | `key`, `plugin`, `thread?`, `shown`, `title`, `forModel`, `said?`, `showCard` |
| `delivery.claimed` | `key`, `thread`, `turn?`, `requestId?` (the turn or steering id it is delivered as) |
| `delivery.done` | `key`, `thread`, `turn?`, `outcome`: `TURN`/`STEERED`/`RECORDED`/`DROPPED` |
| `usage.imported` | `day` (`YYYY-MM-DD`), `model`, `usage`, `requests` |
| `import.failed` | `source`, `path`, `error` |

`fact` and `usage.recorded` also appear in the host stream.

**What becomes of the v2 types** (`API/spi/SessionEventType.kt`):

| v2 | v3 |
|---|---|
| `RUN_STARTED` | `run.started` (payload changes) |
| `USER_MESSAGE` | `user.message` (adds `shown`, `origin`, image blobs) |
| `USER_INTERACTION` | split: `input.received`/`input.consumed`/`input.dropped`, `question.asked`/`question.closed`; approvals become `approval.*` |
| `MODEL_REQUEST` | `model.request` (a digest, not the request), with `request.header`/`request.tools` before it |
| `ASSISTANT_CHUNK` | gone: `model.block` holds a closed block |
| `ASSISTANT_MESSAGE` | gone: `message` (surface) and `model.response` |
| `MODEL_TOOL_CALL` | `tool.call` (raw arguments, both ids) |
| `TOOL_STARTED` | `tool.started` (adds as-run arguments, read-only, approval source) |
| `TOOL_RESULT` | `tool.result` (`content` is the raw string; the parsed `result` goes) |
| `USAGE` | gone: `model.response.usage`; side calls `usage.recorded` |
| `MODEL_RESPONSE_METADATA`, `MODEL_CALL_FINISHED` | gone: `model.response` |
| `RUN_STATUS` | `run.status` |
| `RUN_FINISHED` | `run.finished` (adds outcome, message, result) |

### 1.13 Size

A typical Bothy voice turn (one input, two rounds, three backend calls, a spoken reply) writes about 30 records:
`run.started`, `user.message`, two `message` (input, run context), two `model.request` (~200 bytes each), three to
five `model.block`, three each of `tool.call`/`tool.started`/`tool.result`, six round `message` records with
references (~150 bytes each), two `model.response`, the final `message`, `run.finished`. That is 5 to 8 KB plus the
tool results' content once. The v2 log wrote the whole request, history included, every round, which grows with the
square of the conversation. The header is written once and again only when it changes; the system prompt's base part
is logged once per change (`{"same": 0}` afterwards) and the per-run context part once per run.

## 2. Exactness

### 2.1 The boundary

The request that must be reproducible is the `ModelRequest` (`API/spi/model/ModelClient.kt:22-33`) that the runtime
passes to `ModelClient.complete`, after the client has prepared it. Two things lie beyond it and are pinned instead
of logged:

- **The provider serialiser.** `OpenAiProviderJson`, `AnthropicProviderJson`, `OpenAiResponsesProviderJson`
  (`R/model/`) turn a `ModelRequest` into the body with no other input than the client's configuration; they are pure
  functions (10.1 tests each one: the same request and configuration give the same bytes).
- **The client's configuration**, which adds what the request does not show: the service's model id, server-side tools
  (`web_search_options`, Anthropic's `web_search_20250305` tool: `R/model/OpenAiProviderJson.kt:128-135`,
  `AnthropicProviderJson.kt:193`), output limits, prompt caching, streaming. The client describes it in one line,
  logged in the header (`wire`), so a change of it is a logged header change.

Two SPI methods, both experimental with defaults (additive; `jvmDefault` keeps `DefaultImpls` for Java hosts):

```kotlin
interface ModelClient {
    // ...existing members...

    /**
     * The request as this client will send it. Called before every request; the runtime logs what it returns and sends
     * it to [complete]. Change only system, tools, toolChoice and promptCacheKey; return the same messages list.
     * Deterministic, quick, safe to call from several threads.
     */
    @ExperimentalAgentRuntimeApi
    fun prepare(request: ModelRequest): ModelRequest = request

    /**
     * What this client sends that [request] does not show, as one line that changes when it does: the model id it
     * sends, server-side tools it adds, output limit, caching, streaming. Logged with the request header.
     */
    @ExperimentalAgentRuntimeApi
    fun wireProfile(request: ModelRequest): String = ""
}
```

- The runtime checks `prepared.messages === request.messages` and fails the run with `MODEL_CLIENT_CONTRACT` if not.
- `AppModelClient` (`AS/model/AppModelClient.kt:40-104`) moves `withToolNote` (`:308-310`) from `complete` into
  `prepare`; `complete` no longer changes the request. Its `ModelRoundRecovery` synthetic `finish` call
  (`:95-98`) is part of the response it emits and is logged like any call.
- `OpenAiModelClient`, `AnthropicModelClient` and the Responses client implement `wireProfile`
  (`"openai-chat model=<id> search=<off|options> maxOut=<n|default> stream=<bool>"` and the like). Every
  `ModelClient` wrapper in the SDK forwards both methods to its delegate: the variant and concurrency wrappers in
  `R/model/`, `AppModelClient`, the service adapters in `agent-app-models`. A wrapper that changes requests implements
  `prepare` itself.

"Byte-equal" means: `ModelRequestCodec.canonical(rebuilt)` equals `ModelRequestCodec.canonical(sent)`.
`ModelRequestCodec` (`R/runtime/log/records/ModelRequestCodec.kt`) writes
`{"system","model","cacheKey","toolChoice","tools":[ToolJson],"messages":[m]}` in that order, with `m` as in
`message` records (1.12) and images as their blob form (hash, type, size, path, detail). `digest` is the SHA-256 of
those bytes. This is stricter than dsh's test, which compares with deep equality.

### 2.2 The header

The header in force at seq `n` is the fold of the `request.header` and `request.tools` records before `n`. It holds
everything of a `ModelRequest` but its messages, plus how the messages are viewed:

```kotlin
internal data class RequestHeader(
    val systemParts: List<String>,        // ModelRequest.system = parts.joinToString("\n\n"), null when empty
    val tools: List<ModelTool>,           // in request order
    val model: ModelRef?,
    val promptCacheKey: String?,
    val toolChoice: String?,
    val imageView: ImageView,             // ModelImageRequestView's settings: imageInputEnabled, newestImages
    val wire: String,
)
```

The system prompt has parts because its base (`BootstrapPromptProvider.build`, `R/kernel/core/context/`) is stable
while the context providers' suffix and the thread's instructions change per run
(`R/kernel/core/context/ContextPromptResolver.kt:3-7`, `R/kernel/core/loop/NativeToolLoop.kt:186-198`, `:937`). The live
resolver returns `[base, suffix]` (suffix omitted when blank); the request's `system` is their join, exactly as
`withContextSuffix` builds it today. When `prepare` returns a `system` that is not the join of the parts, the header
logs it as one part.

Before every `model.request`, the runtime compares the header it is about to use with the one in force:

1. If any field but `tools` differs, it appends a full `request.header` (reason `INITIAL` for the thread's first,
   `FALLBACK` when the retry loop switched to a fallback model, `CHANGE` otherwise). Unchanged system parts are written
   as `{"same": i}`.
2. Else, if `tools` differ, it appends `request.tools`: `removed` (names in force and not wanted), `added` (wanted and
   not in force, or in force with another schema, description or `deferred` flag: removed and added again), and
   `order` when the wanted order is not "in-force order minus removed, then added".
3. Else nothing.

Prompt caches break exactly where the log shows a header record or a `history.edit`; everything else only appends.

### 2.3 The surface

The surface is the model's history: the list of `ModelMessage` the request carries. It is made only of `message`
records and changed only by `history.edit` records. **The loop keeps no list of its own**: every place in
`NativeToolLoop`/`NativeRoundRunner` that does `messages += ...` today appends a `message` record instead, and every
place that reads `messages` reads the history projection (3.3, P1). The list below is complete for today's code;
each line is the `origin` the record carries.

| Where today | What is appended |
|---|---|
| `NativeToolLoop.run`: the first user message, exact input bytes (`:145`) | `user.message`, then `message` `INPUT` (`content` refers to it) |
| `runContextMessage(runContext, runContextAsSystem)` (`:156`) | `message` `RUN_CONTEXT` |
| `announceDeferredTools` (`:773-780`) | `message` `TOOL_ANNOUNCE` (with `additions` for native deferred tools) |
| `appendSteeringInputs` (`:633-646`), `WITHHELD_FINISH_NOTE` | `message` `STEER` (content refers to `input.received`), `message` `NOTICE` for the note |
| `appendBudgetWarningIfNeeded`, `appendTodoReminder`, `RepeatToolCallReminder` via `appendRuntimeNotice` (`:707-715`) | `message` `NOTICE` |
| `implicitFinishOutcome` retry, `emptyCompletionContinueMessages`, obligation correction (`NativeRoundRunner.kt:278-330`) | `message` `CORRECTION` |
| a round's tool calls and results (`flushToolCalls`, `appendToolResult`, `withAssistantText`, `withWireCalls`) | `message` `ROUND` pairs: the call (written form) then its result |
| `RoundOutcome.Finished`: `assistant(response)` (`:414-420`); withheld finish continuation (`:404-410`) | `message` `FINAL` |
| `failedRunNotice` (`:439`) | `message` `FAILURE_NOTICE` |
| `cancelledRunNotice` (`:511`) and the legal round of a cancelled stream (`persistLegalCancellationHistory`) | `message` `CLOSER` pairs, then `message` `CANCEL_NOTICE` |
| `RequiresConfirmation`: the round's messages and `awaitingConfirmationMessages` (`:462-479`) | `message` `ROUND`, then the paused call and `PLACEHOLDER` result |
| `resumeAfterApproval`: the call and its result (`:844-846`) | `history.edit REPLACE` of the placeholder by the result (reason `APPROVAL`) |
| `unconfirmedMessages` (denied, expired, cancelled) | `history.edit REPLACE` of the placeholder by the refusal (reason `APPROVAL`) |
| `resumeUserInput`: the answer (`:899`) | `user.message` `ANSWER`, `message` `ANSWER` |
| `compactAfterRound`, `compactBeforeRun` (`ConversationCompactor`, `HistoryCompactor`) | `history.edit REPLACE` (reason `COMPACTION`) with `SUMMARY` messages |
| `SessionHistoryStore.trimmed` (`R/kernel/core/context/SessionHistoryStore.kt:162-170`) | `history.edit STRIP_IMAGES` / `REPLACE` with no messages (reason `TRIM`), at run start |
| `withStaleConfirmationsClosed` (`:208-210`) | done by repair (4.1) |
| `withClosedToolCalls` (`:215-224`) | never needed: see the invariant below; a defensive `INSERT_AFTER` (reason `CLOSER`) if a test-only check ever finds one |

**The invariant**: every assistant message with a `toolCallId` is immediately followed by the tool message with that
id. The loop only ever appends calls together with their result or placeholder, so the history needs no closers at a
run's end. `SurfaceInvariant.check` runs in tests after every append and at every `run.finished` in debug builds.

**A round, as logged.** The response is logged as it streams, by `BlockAssembler`
(`R/runtime/log/BlockAssembler.kt`), used by `NativeRoundRunner.collect` in place of its `thinking`/`text`
builders:

- Consecutive `ThinkingDelta`s form a `REASONING` block (its `signature` is the last non-blank one); consecutive
  `TextDelta`s a `TEXT` block. A block closes when an event of another kind arrives or the stream ends, and is then
  appended as `model.block`. Blocks are numbered from 0 in the response.
- A `ToolCall` closes the current block and is appended at once as `tool.call` with the next index: `arguments` is the
  string the client emitted, `writtenId` the id it carried when `uniqueToolCall` (`NativeRoundRunner.kt:952-957`)
  changed it (blank or repeated), `callId` the unique id that every later record and the history use.
- A `SearchActivity` is a `SEARCH` block, appended at once.
- `UsageDelta`s are summed; `ModelResponseMetadata` is kept; at `Completed`, or when the stream fails or is
  cancelled, the open block is appended and then `model.response` with the status, usage and timing.

The `ROUND` messages are what the loop computes today, unchanged: `ToolArguments.takeUp`
(`R/kernel/core/loop/WrittenToolCalls.kt:28-41`) gives the call as run, and the history keeps the call as written,
`call_tool` wrapper and host note included; `withAssistantText` puts what the model said on the round's first call;
`nextReasoningContentForHistory` puts the reasoning on the first call and `DEDUPED_REASONING_MARKER` on later ones
when unsigned. The log records the messages that result, with `args`, `content` and `reasoning` as references when
equal (1.5). `tool.started.arguments` records the call as run when it differs.

**Settled calls.** A `tool.call` is settled when a `message` with its `callId` exists, or a later `model.request`
exists in the same span, or the span paused on an approval after it (calls the model wrote after the paused one are
dropped, as today). `RoundSurface.closers(round, records)` (`R/runtime/log/RoundSurface.kt`) builds the messages for
the unsettled calls of a round: for each, in block order, the call message (written form; reasoning is the text of
the `REASONING` blocks before it with `CancellationLegalHistoryTracker.forHistory`'s signature and dedupe rules,
`R/kernel/core/loop/CancellationLegalHistory.kt:39-45`), then a result: the `tool.result` content if there is one,
else `cancelledToolResult(name)` when the run was cancelled live, else `TOOL_OUTCOME_UNKNOWN` when the call has a
`tool.started`, else `TOOL_NOT_STARTED`. The cancellation path (`LegalHistoryCancellationException`) and repair
(4.1) both use it, so a cut stream and a dead process leave the same shape.

**Other inputs to a request, all logged**:

- Steering: `input.received` when queued (F6), a `message` `STEER` when drained into the history at a round's start,
  `input.consumed` after the response that read it, `input.dropped` when the run ends without reading it.
- Retries: `model.response` `FAILED`, `model.retry`, then (with a fallback model) `request.header` `FALLBACK`, then the
  next `model.request` with `attempt + 1`. A retry happens only when no tool result of the failed attempt was
  committed (`NativeToolLoopSupport.kt:430-431`), so the surface is unchanged and the retry's request differs at most
  in its header.
- Compaction: the summariser's call is a `model.request` with `purpose = COMPACTION` and
  `compaction = {from, to, template}`, rebuilt by `CompactionRequests.build(state, from, to, template)` from the
  surface nodes in that range and the header in force (logged first, as for a turn request); `template` is the
  version of the summariser's fixed prompt text. Its outcome is the `history.edit`.
- Images: `ModelImageRequestView` (`R/kernel/core/loop/ModelImageRequestView.kt`) is applied to the surface with the
  header's `imageView`; it is a pure function of the messages and those two settings.
- Ids: the history and every record after `tool.call` use the unique `callId`; `writtenId` keeps what the model sent.
- Recorded turns with `forModel` (5.4): `message` `EXTERNAL`.
- Imported history: `message` `IMPORTED` (section 8).

### 2.4 The reconstruction function

```kotlin
internal object RequestRebuild {                       // R/runtime/log/RequestRebuild.kt
    /** The request the `model.request` at [seq] was sent as, from the records before it alone. */
    fun rebuild(records: List<LogRecord>, seq: Long, blobs: BlobReader): ModelRequest {
        val record = records[seq.toInt()].data as ModelRequestRecord
        val state = HistoryProjection.fold(records.subList(0, seq.toInt()), blobs)
        return when (record.purpose) {
            Purpose.TURN -> state.turnRequest()
            Purpose.COMPACTION -> CompactionRequests.build(state, record.compaction!!)
        }
    }
}

internal fun HistoryState.turnRequest(): ModelRequest = ModelRequest(
    messages = ModelImageRequestView(header.imageView).apply(surface.messages()),
    tools = header.tools,
    system = header.systemParts.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
    modelRef = header.model,
    promptCacheKey = header.promptCacheKey,
    toolChoice = header.toolChoice,
)
```

The live path (`ModelCalls.send`, `R/runtime/log/ModelCalls.kt`, used by `NativeRoundRunner` and the compactors):

```kotlin
val wanted = ModelRequest(messages = imageView.apply(history.surface.messages()), tools = registry.modelTools,
    system = resolved.join(), modelRef = candidate, promptCacheKey = cacheKey(thread), toolChoice = choice)
val prepared = client.prepare(wanted)                          // contract checked
log.appendHeaderChanges(prepared, resolved.parts, client.wireProfile(prepared), imageView)   // 2.2
val sent = history.turnRequest()                               // from the projection, header records applied
if (debugChecks) check(canonical(sent) == canonical(prepared)) // tests and debug builds
log.append(MODEL_REQUEST, ModelRequestRecord(TURN, round, attempt, digest(sent), ...))
log.flush()                                                    // F2
client.complete(sent).collect(blockAssembler)
```

The request sent is the one rebuilt from the projection, so it cannot carry anything the log lacks.

### 2.5 The proof

`RequestRebuildTest` (`agent-runtime/src/test/kotlin/cc/noharry/agent/runtime/log/`): a `RecordingModelClient`
wraps a scripted client and keeps the canonical bytes of every request `complete` receives. After each scenario, for
the i-th `model.request` record: `canonical(RequestRebuild.rebuild(prefix, seq))` equals the i-th captured request,
and its digest equals the record's. It then closes the runtime, opens the thread from disk in a fresh `ThreadLogs`
registry, and repeats the check on the records read back, which proves disk equals memory. Scenarios, each its own
test:

1. Several rounds with backend calls in DIRECT, CALL_TOOL and NATIVE_DEFERRED exposure; tools activated by
   `search_tools`, by memory hints and inherited from earlier turns; `tool_addition` messages.
2. `call_tool` with the arguments as written, a host note (`ToolArguments` + `prepare`, as `AppModelClient` does),
   a call whose own schema owns the note field.
3. Blank and repeated call ids from the client.
4. Anthropic thinking with signatures; DeepSeek reasoning content; reasoning dedupe across calls.
5. Steering mid-stream and between rounds; withheld finish; budget warning; repeat-call reminder; empty completion
   continue; invalid finish retry; obligation correction.
6. Retries with backoff and with a fallback model; a first-event timeout from `withFirstEventTimeout`.
7. Compaction before a run and after a round; trimming of images and old runs.
8. Approval: approve, deny, expire, cancel; a round with calls after the paused one.
9. Blocking question and its answer turn; async question.
10. Cancellation mid-stream with calls started and not started.
11. Process death after each flush point (section 4), then the next turn.
12. An imported 0.5 thread's first turn (`request.header` `INITIAL`, `message` `IMPORTED` before it).
13. A recorded turn with `forModel`, then a model turn.
14. Images in the input, in tool results, and older images dropped by the view.
15. A sub-agent's own requests (its thread) and its parent's after `wait_agent`.

`AppRequestRebuildTest` (`agent-app-server` tests) runs scenarios 1, 2 and 6 through `AppModelClient` with the note
on, and through each `agent-app-models` adapter with a mock transport, and also checks that the transport received
exactly `serialize(rebuilt, profile)`.

## 3. Projections

### 3.1 The reducer

Inside the runtime (`R/runtime/log/Projection.kt`):

```kotlin
internal interface Projection<S : Any> {
    /** Names its snapshot file; `[a-z0-9-]+`, unique per log. */
    val id: String
    /** Raised whenever apply() or the snapshot encoding changes meaning; a snapshot of another version is rebuilt. */
    val version: Int
    fun initial(): S
    /**
     * Folds one record into [state] and returns the state (the same instance may be returned after changing it in
     * place). Deterministic: no clock, no randomness, no I/O but [ctx]; unknown types and ignorable records it does
     * not read are skipped.
     */
    fun apply(state: S, record: LogRecord, ctx: FoldContext): S
    fun encode(state: S): JsonElement
    fun decode(json: JsonElement): S
}

internal interface FoldContext {
    val threadId: String
    fun blob(sha256: String): ByteArray            // throws BlobMissingException: the log is CORRUPT
    fun record(seq: Long): LogRecord               // only records of the span being folded (references, 1.5)
}
```

`ProjectionHost` (one per `ThreadLog`) applies every registered projection to every record under the thread lock, in
append order, live and on open alike; there is no other way to fill a projection. Readers take an immutable view
(`HistoryView`, `ThreadSummary`, ...) the projection publishes after each apply, or call `ThreadLog.read` for a short
critical section. A projection that throws marks the log `CORRUPT` (1.10) rather than continuing with a wrong state.

For hosts (public, experimental, `API/spi/LogProjection.kt`), the same contract over public records, with immutable
state:

```kotlin
@ExperimentalAgentRuntimeApi
interface LogProjection<S : Any> {
    val id: String
    val version: Int
    fun initial(): S
    fun apply(state: S, event: SessionEvent): S      // pure; return state unchanged for events it ignores
    fun encode(state: S): String? = null             // null: no snapshot, folded from the start on open
    fun decode(text: String): S = error("no snapshot")
}
```

A host registers one with `AgentRuntimeBuilder.projection(p, threads = true, hostStream = false)` (it then runs on
every thread log, and on the host stream when `hostStream` is true) and reads it with `AgentThread.read(p)` / `HostLog.read(p)`. The app server's
transcript and every plugin projection are of this kind. Two runtimes registering the same `id` and `version` on one
root share the state; the same `id` with another `version` in one process is an error.

### 3.2 Snapshots

`cache/<id>.json`:

```json
{"id":"history","version":1,"thread":"<thread id>","created":1731628800123,"seq":4182,"hash":"<sha256 of record 4182's line>","from":4170,"state":{...}}
```

- Written after a `run.finished`, every 512 records, and when the log closes; only after `durableSeq >= seq` (F9: the
  log leads, the cache follows). Written with temp file and rename, without fsync: a lost or torn snapshot is just
  rebuilt.
- `from` is the seq to read from to continue: the `run.started` of the span open at `seq`, or `seq + 1` when none is
  open, so references (1.5) resolve.
- Trusted only when `id`, `version` and `thread` match, `created` equals `thread.created.createdAt`, the record at
  `seq` exists and its line hashes to `hash`. Otherwise it is deleted and the projection folds from 0. The hash catches
  a log truncated by repair below the snapshot, a thread deleted and recreated with the same id, and a restored
  backup.
- Rule for every projection: deleting `cache/` and `index/` loses nothing but time.

### 3.3 The projections

Each entry: source records; state; what it replaces; who reads it.

**P1 History** (`history`, internal, `R/runtime/log/projections/HistoryProjection.kt`)
- Source: `request.header`, `request.tools`, `message`, `history.edit`, `model.response` (input tokens), `run.started`,
  `run.finished` (span boundaries for trimming).
- State: `header: RequestHeader` (2.2); `nodes`: persistent list of `Node(id: NodeId, span: String?, origin, message:
  ModelMessage)` with references and blobs resolved (image bytes loaded lazily through `BlobReader`); `lastInputTokens`;
  per span: first node index, round count.
- Replaces: `SessionHistoryStore` and its persistence `*.history.json` with `.data/blobs`
  (`R/kernel/core/context/SessionHistoryStore.kt`, `R/runtime/thread/ThreadStore.kt:134-156`, `ModelHistoryCodec.kt`),
  the public `SessionHistory` (`R/runtime/SessionHistory.kt`) and the app server's `RuntimeHistories`
  (`AS/runtime/RuntimeHistories.kt`), `LoopHistoryStore`'s messages and `contextSystemSuffix`
  (`R/kernel/core/state/LoopHistoryStore.kt`, `runs/{run}/loop-history.json`), `NativeToolLoop.persistedContextSuffix`.
- Readers: `ModelCalls.send` (2.4), `RequestRebuild`, both compactors, the loop's scans (`hasInvalidFinishResponseRetry`,
  `hasTodoUpdate`, `hasStateAdvancingTool`, `hasUnresolvedObligationRetry`, ...: `NativeLoopHistoryScans.kt`, now over
  the current span's nodes), `announceDeferredTools`, `ToolActivationStore.keepShownToolsCallable/keepLoadedToolsDeclared`,
  repair.

**P2 Tool activation** (`activation`, internal)
- Source: `tools.activation`, `run.started`.
- State: per span the activated tools (`name -> {server, source, schemaSha}`) and the unavailable set; the inherited
  set for the next span = the union of earlier spans' activations minus tools later marked unavailable.
- Replaces: `ToolActivationStore`'s session cache (`snapshotSessionActivations`, `inheritSessionActivations`,
  `R/kernel/core/tools/ToolActivationStore.kt:119-190`) and the public experimental `SessionToolActivations`;
  `ToolActivationStore` stays the in-run working set, seeded from P2 and logging each change.
- Readers: `DefaultAgentRuntime.executeAdmittedOrdinary` (`R/runtime/DefaultAgentRuntime.kt:617`), the tool registry.

**P3 Thread state** (`thread`, internal; public view `ThreadSummary`)
- Source: `thread.*`, `run.*`, `user.message`, `question.*`, `approval.*`, `input.*`, `import.turn`, `subagent.*`.
- State:
  ```kotlin
  data class ThreadSummary(
      val id: String, val createdAt: Long, val updatedAt: Long, val origin: Origin, val parent: ParentRef?,
      val title: String?, val options: ThreadOptions, val status: ThreadStatus,
      val openSpan: SpanRef?, val turnIds: List<String>, val turnCount: Int,
      val pendingQuestion: Question?,                  // a blocking question of the last finished turn
      val openApprovals: Map<String, ApprovalAsked>,   // item id -> the ask (live only; repair closes them)
      val rememberedDomains: Set<String>,
      val policyInForce: ApprovalPolicy,
      val lastTurn: LastTurn?,                         // turnId, tag, shown input, final response, status, outcome
      val pendingChildReports: Int,                    // from P9, for the index
  )
  ```
  `updatedAt` is the `t` of the last record that is not `fact`/`diag`.
- Replaces: `*.thread.json` (`ThreadStore.kt:46-109`), `*.attempts.json` (`ThreadAttemptStore.kt`), the app server's
  `meta.json` and the `lifecycleStatus`/`completionOutcome` overlay of `ActiveRunRegistry` (`AS/runtime/ActiveRunRegistry.kt:218-225`).
  The live wait for an approval stays in memory (`ThreadApproval`); its ask and decision are records.
- Readers: `AgentThread.status`, `pendingRequests`, `steer(answering)`; `automaticApproval` (remembered domains);
  `listThreads` through P5; the app server's list and board.

**P4 Items and turns** (`items`, internal; public `ThreadItem`)
- Source: `user.message`, `input.received`, `model.block`, `tool.call`, `tool.started`, `tool.result`, `approval.*`,
  `question.*`, `loop.notice`, `model.retry`, `run.*`, `import.turn`, `usage`-bearing records.
- State: per turn `TurnView(turnId, status, items: LinkedHashMap<String, ThreadItem>, finalResponse, usage, error,
  pending)`; turns in order.
- **Ids**, all derived from the log (the format changes from `"$turnId:item:N"`, `ThreadEventProjection.kt:33`):

  | Item | Id |
  |---|---|
  | `UserMessage` of a turn | `"<seq of user.message>"` |
  | `UserMessage(steer = true)` | `"<seq of input.received>"` |
  | `Reasoning`, `AgentMessage`, `WebSearch`, `ToolCall` | `"<seq of model.request>.<block index + 1>"` |
  | `AgentMessage` of a recorded turn's reply | `"<seq of run.finished>.1"` |
  | `Approval`, `Question` | `"<seq of approval.asked / question.asked>"` |
  | `Notice` | `"<seq of loop.notice / model.retry>"` |
  | `FileChange` | `"<seq of tool.result>.1"` |
  | `Error` of a failed turn | `"<seq of run.finished>.2"` |
  | items of an imported turn | the old ids, verbatim |

  A streaming block's id is known when its first delta arrives (`BlockAssembler` knows the request seq and the next
  index), so live `ItemDelta`s and the item folded later from the closed block have the same id.
- **Live events.** `runStreamed` no longer projects `AgentEvent`s (`ThreadEventProjection`): it emits what P4 emits as
  records apply (`ItemStarted`/`ItemUpdated`/`ItemCompleted`, `UsageUpdated`, `TurnCompleted`/`TurnFailed` after F4),
  plus the `ItemDelta`s of open blocks and exec output that `BlockAssembler` and the exec tool pass through without
  logging.
- Replaces: turn snapshots `.data/turns/*.json` (`ThreadStore.kt:187-190`), `ThreadEventProjection`'s state, the
  stored `TurnResult`; duplicate-turn replay reads P4.
- Readers: `AgentThread.items`, `runStreamed`, `run`'s `TurnResult`, the app server's transcript (P11).

**P5 Conversation list** (cross-thread index `index/threads.json`, `R/runtime/log/ThreadIndex.kt`)
- Source: each thread's P3 `ThreadSummary`.
- State: `threadId -> {summary, lastSeq, lastSegment, segmentBytes}`.
- Kept current when a log appends (on the thread's P3 change, debounced 1 s) and checked on `listThreads`: a thread
  directory missing from the index, or whose last segment's size differs, is opened read-only and folded from its
  snapshots. Never scans log bodies otherwise.
- Replaces: `ThreadStore.list` (`ThreadStore.kt:59-64`), `SessionStore.listSessions` reading every `meta.json`.
- Readers: `AgentRuntime.listThreads` (gains `title`, `parentThreadId`, `lastOutcome`), the app server's `thread/list`
  (`SessionSummary.lifecycleStatus` from `status`, `completionOutcome` from `lastOutcome`).

**P6 Usage** (`usage`, per log, internal; cross-log `index/usage.json`)
- Source: `model.response.usage` (one request each), `usage.recorded`, `usage.imported`, `run.started` (attribution),
  `subagent.settled.usage`.
- State: per turn usage; thread total; buckets per `(day, model)` with requests and token counts, where `day` is the
  record's local date in the device's zone at fold time.
- Cross-log index: the merge of every thread's and the host stream's buckets, each with its `lastSeq`; refreshed
  incrementally like P5.
- Replaces: `model-usage/usage-ledger.json` (`AS/usage/UsageStore.kt:41-147`; prices stay in `model-prices.json`), the
  board's `SessionUsage` (`AS/session/SessionUsage.kt`) and `AppSubagents`' usage map, `TurnResult.usage`.
- Readers: `AgentRuntime.usage()` (the app server's `usage/read`), `TurnResult`, `UsageUpdated`. The app server's
  board sums a thread's and its children's usage in P11 from the same records.

**P7 Runs** (`runs`, internal)
- Source: `run.*`, `model.request`, `model.response`, `tool.started`, `tool.result`, `obligation`, `loop.notice`, `diag`.
- State, per span: status history (`RuntimeRunSnapshot`), `RuntimeResult` (from `run.finished.result`), the obligation
  ledger (fold of `obligation`, with `ledgerSalt` from `run.started`), the budget snapshot (`roundsStarted` = TURN
  requests with `attempt = 1`, `toolCallsUsed` = `tool.started` outside `update_todo`, `startedAtEpochMillis` = the
  span's `run.started.t`, `latestUsageTokens` = the last response's input tokens), and the latest workpad, handoff,
  open confusion, evidence, and the trace list from `diag`. A span with `continues` starts its ledger and budget from
  the span it continues.
- Replaces: the reliability tree `storage/runtime/` (`R/runtime/reliability/FileBackedReliabilityStorage.kt:64-160`):
  `ReliabilityStorage` gets a log-backed implementation (`saveRun` -> `run.status` when the status changed,
  `saveResult` -> carried by `run.finished`, the other saves -> `diag`), and the budget and ledger parts of
  `loop-history.json`; the app server's `debug-run-evidence/*.json` (`AS/evidence/RunEvidence.kt:144-150`) and the
  input of `RunReplayVerifier`.
- Readers: `RuntimeDiagnostics`, approval and answer resumption (budget, ledger), the app server's finalize, evidence
  and trajectory.

**P8 Todo** (`todo`, internal)
- Source: `todo.set`. State: the latest list.
- Replaces: `TodoStore` files and `/memory/todo/{session}` (`R/runtime/run/TodoStore.kt:47-71`); `TodoStorePort` reads
  P8 and appends `todo.set`.
- Readers: the todo prompt section, the `/context/todo.json` view, the app server's todo board.

**P9 Sub-agents** (`subagents`, internal; section 7)
- Source: `subagent.*` in the parent log. State: children `childId -> {label, agent, task, state, result, error,
  usage, delivered}`; `pending` = settled and not delivered.
- Replaces: `Subagents`' finished children and `pendingReports` (`R/runtime/Subagents.kt:64-70`), `AppSubagents`' board,
  deliveries and usage (`AS/skills/AppSubagents.kt:54-201`), `subagents.jsonl`.
- Readers: `Subagents` (restore, `statuses`), the app server's board.

**P10 Fact index** (`facts`, internal)
- Source: `fact`. State: `(ns, kind, key) -> seq` for keyed facts.
- Readers: `appendFact`'s idempotency check (6.3).

**P11 Transcript** (app server, `AS/session/TranscriptProjection.kt`, a `LogProjection`)
- Source: everything P4 reads, through public `SessionEvent`s, plus `fact`s the plugins render (6.2) and the
  app-server facts (`ns = "app"`).
- State: `ConversationItem`s in order with log-derived ids (5.2), turn rows (`SessionTurn` shape) for the board and the
  `/sessions` files, the previous exchange.
- Replaces: `turns.jsonl`, `meta.json`, `summary.json`, `index.json` (`AS/session/SessionStore.kt`,
  `SessionQueryFiles.kt`), `SessionStore.transcriptItems` (`:215-259`), the restore paths of `TranscriptStore`,
  `PreviousExchange` from `turns.jsonl` (`AS/runtime/PreviousExchange.kt:16-20`), `ArtifactStore`'s input.
- Readers: `thread/read`, `thread/select`, `thread/readOlder`, the board, `prepareInput`, the `/sessions` view.

**P12 Deliveries** (app server, host stream)
- Source: `delivery.queued`, `delivery.claimed`, `delivery.done`. State: pending deliveries in queue order, with their
  claim.
- Replaces: the durability gap of `RuntimeSession.deliver`'s in-memory queue (`AS/runtime/RuntimeSession.kt:534-560`).
- Readers: the delivery queue at server start (6.4).

**P13 Plugin projections** (each a `LogProjection` in its plugin; section 6.5): the plan book (host stream), offers
and picks, drawn pages, generated pictures, A2A delegations.

## 4. Resume and recovery

### 4.1 Repair on open

When a log opens for write (1.10 step 7), `Repair.closers(view)` (`R/runtime/log/Repair.kt`) computes the records that
close what the durable log leaves open, from the projections alone; they are appended in this order and flushed
before the thread can be used. They are computed from state, so a crash during repair repairs again only what is
still open.

| Open work (from the projections) | Closers appended |
|---|---|
| An approval asked and not decided | `approval.decided{state = CANCELLED, by = PROCESS_ENDED}`; `history.edit REPLACE` of its placeholder by `INTERRUPTED_CONFIRMATION_RESULT` (`R/kernel/core/loop/NativeToolMessages.kt:76-77`) |
| An approval decided `APPROVED` whose placeholder is still there | `history.edit REPLACE` of the placeholder by the `tool.result` content if there is one, else a `TOOL_OUTCOME_UNKNOWN` result if the call has a `tool.started`, else `TOOL_NOT_STARTED` |
| A `model.request` without `model.response` | `model.response{status = CANCELLED, error = PROCESS_ENDED}` (blocks not logged before the death are lost) |
| Unsettled calls of the span's last round (2.3) | `message CLOSER` pairs from `RoundSurface.closers`: real result, `TOOL_OUTCOME_UNKNOWN` or `TOOL_NOT_STARTED` |
| An async question not closed | `question.closed{by = PROCESS_ENDED}` |
| Steering received and not consumed | `input.dropped{reason = PROCESS_ENDED}` (the items keep showing it, as `retainSteeringInputs` does today) |
| An open span whose last history message is `FINAL` | `run.finished{status = COMPLETED, finalResponse, completion from P7, recovered = true}` |
| Any other open span | `message CANCEL_NOTICE` (`PROCESS_ENDED_NOTICE`: "the request above was interrupted when the app stopped; calls with a result ran, a call whose result is TOOL_OUTCOME_UNKNOWN may have partly taken effect: check before repeating anything that changes something"), then `run.finished{status = INTERRUPTED, error = PROCESS_ENDED, recoverable = true, recovered = true}` |
| An open recorded span | `run.finished{status = INTERRUPTED, error = PROCESS_ENDED}` |
| (always, when anything above or a torn tail was found) | `log.repaired{tornBytes, closed}` |

Decisions in this table:

- **A pending approval does not survive a process death.** The person is not asked again after a restart: the world
  the call was meant for may have changed (a car's state, a page). The next turn's model reads that the call did not run.
- **The model sees an interrupted run.** Today a run cut by a process death is missing from the next request
  entirely (`history.json` is written only at its end); now its input, its finished calls and the notice are there,
  as for a cancelled run.
- A blocking question is not open work: its turn ended `WAITING_FOR_ANSWER` and the thread stays waiting.

Sub-agent children are reconciled by the `Subagents` engine at start (section 7), deliveries by the app server (6.4).

### 4.2 Continuing work that is not a repair

- **Approval, same process.** The paused loop keeps its `PendingNativeToolUse` in memory as today; its history, budget
  and ledger are no longer copied into `loop-history.json` but read back from P1 and P7 when it resumes. On approval:
  `approval.decided`, then the tool (`tool.started`, F3, `tool.result`), then `history.edit REPLACE` of the
  placeholder, then the loop continues in the same span. On denial, expiry or withdrawal: `approval.decided`, the
  `REPLACE` by `unconfirmedMessages(code)`'s result, `run.finished` as today.
- **Answer to a blocking question.** A new turn whose span has `continues` = the waiting span. `resumeUserInput`
  (`R/kernel/core/loop/NativeToolLoop.kt:870-957`) restores the budget and the ledger from P7's chain, appends
  `user.message ANSWER` and `message ANSWER`, computes the context suffix again (a header `CHANGE` when it differs) and
  continues on the thread's history. `userInputHistoryMissingFailure` is raised only when the continued span does not
  exist.
- **A turn retried after a process death** (the host repeats `TurnOptions.turnId`): the earlier attempt was closed by
  repair; the retry is a new span with `attempt + 1`. A turn id whose turn completed replays its `TurnResult` from P4,
  as today. `prepareAttempt`'s interruption of abandoned attempts (`R/runtime/thread/DefaultAgentThread.kt:195-211`)
  is replaced by repair.

### 4.3 Kill at each write point

What a reopened thread looks like when the process dies right after each point of a turn. "Durable" means flushed;
anything after the last durable record is gone.

| Dies after | Reopen gives |
|---|---|
| A thread's staging directory, before its rename | No thread; `.staging` emptied at start |
| `thread.created` (F1) | An empty thread |
| `run.started`, `user.message`, input `message` (F10) | The turn with its input; `CANCEL_NOTICE`; `INTERRUPTED` |
| `model.request` (F2), some blocks or calls not durable | As above plus `model.response CANCELLED` |
| `tool.started` of an acting call (F3) | That call `TOOL_OUTCOME_UNKNOWN`, other calls of the round `TOOL_NOT_STARTED` or their real result; `INTERRUPTED` |
| A plugin fact inside that tool (F7) | As above; the fact stands, so the plugin's projection matches what the tool did |
| `tool.result` in memory only | As `tool.started`: the outcome is unknown to the log |
| The round's `message` records, before the next `model.request` | The round as the loop wrote it; `INTERRUPTED` |
| `approval.asked` and placeholder (F5) | `approval.decided CANCELLED`, placeholder replaced; `INTERRUPTED` |
| `approval.decided APPROVED`, `tool.started` (F3) | Placeholder replaced by `TOOL_OUTCOME_UNKNOWN`; `INTERRUPTED` |
| The `FINAL` message, before `run.finished` is durable | `run.finished COMPLETED`, `recovered` |
| `run.finished` (F4), before a snapshot | Complete; projections fold the tail |
| `delivery.queued` (F7) | Re-delivered at start (6.4) |
| `delivery.claimed`, the delivered turn's `run.started` (F10) | `delivery.done` appended at start; not delivered twice |
| A child's `run.finished`, before the parent's `subagent.settled` | `subagent.settled` from the child's log at start (7) |
| An import's staging, before rename | Old files untouched; import runs again |
| An import's rename, before the old files are deleted | Old files deleted at start |
| A deletion's rename into `.trash` | The thread is gone; `.trash` emptied at start |
| Half a line written (power loss) | Torn tail cut (1.10), then the row for the last durable record |

`KillPointTest` (10.1) runs each of these.

### 4.4 Readers that do not write

A log opened `READ_ONLY` (another process holds it, or it is corrupt or newer) applies the same closers in memory only
and serves reads; turns, records and facts on it fail with `LOG_LOCKED`, `LOG_CORRUPT` or `LOG_NEWER_FORMAT`.

## 5. The app server and its protocol

### 5.1 Storage and runtimes

- One storage root for every conversation, sub-agent thread and the host stream: `noBackupFilesDir/app-server`.
  `runtimeAssembly` (`AS/runtime/SharedRuntimeAssembly.kt:10-22`) passes `.storage(root)` and nothing else; the per
  session roots (`appSessionEventLogRoot`, `AS/trajectory/TrajectoryStorage.kt:27-40`, with its v1 cleanup side effect),
  `.sessionEventLogRoot`, `.sessionHistory(RuntimeHistories.forRoot(...))` (`AS/runtime/RuntimeBuilder.kt:141-142,
  245`) and `RuntimeHistories` go. The session store directory `filesDir/runtime-debug-workspace/sessions` goes after
  import (section 8).
- The server still builds a runtime per run (`buildRunRuntime`, `AS/runtime/RuntimeSessionRunner.kt:462-481`); every
  one of them writes through the process's single `ThreadLog` per thread (1.9).
- `AppConversations` (`AS/session/AppConversations.kt`) holds one long-lived `AgentRuntime` on the root, built without
  a model at server start, for all work outside runs: list, read, record, facts, the host log, subscribe, delete. It
  registers the transcript projection (P11), the deliveries projection (P12) and the plugins' projections; the
  per-run runtimes register the same ones, sharing their state (3.1).
- A session id is a thread id; a request id is the turn id, as today (`AS/runtime/RuntimeTurnRequest.kt`).
- Display text and model text: the shown text goes in `TurnInput.shown` (and `SteerOptions.shown` for steering), the
  model text in the parts; the turn's kind in `TurnOptions.tag` (`"voice"`, `"report"`, `"plan"`...). Both are logged
  (`user.message.shown`, `run.started.tag`), so `SessionTurnRecorder`'s copy of the display text
  (`AS/session/SessionTurnRecorder.kt`) and `TranscriptStore`'s steering map (`AS/session/TranscriptStore.kt:301-303,
  360`) go.
- Evidence: `RunEvidenceRecorder` and `RunReplayVerifier` (`AS/evidence/`) run over P7 and P4 at finalize and when a
  run is loaded; their result is shown, not stored (the files `debug-run-evidence/*.json` go). Transport diagnostics
  (`AS/evidence/AppDiagnostics.kt:168-180`, when diagnostics are on) are appended as `fact{ns = "app", kind =
  "transport"}` to the run's thread instead of `runtime-debug-logs/*.log`.
- `ArtifactStore.persistHtmlArtifacts` (`AS/runtime/ArtifactStore.kt`) takes successful `.html` `write_file` calls
  from P4, with their whole arguments, not the 64 KiB previews of `turns.jsonl` that made larger pages fail.

### 5.2 The transcript and item ids

P11 turns the runtime's items (`AgentThread.items()` and the live `ThreadEvent`s) and facts into `ConversationItem`s.
For runtime items the mapping is today's (`AS/session/ThreadItemProjection.kt:11-58`). Beyond it:

- `TurnHeader` from the turn's `UserMessage` item: label from `run.started.tag` through the plugins' `turnLabel`,
  text from `shown` or the input. A steering header from the steering item's `shown`; the app's own steering (no
  `shown`) shows no bubble, as today.
- The "Runtime result" row from `run.finished` when a call failed or is unknown (what `closeTurn` adds live today).
- `ERROR` rows from `run.finished.error`; `STATUS` rows from `approval.decided` (the confirmation result lines of
  `AS/runtime/RuntimeConfirmation.kt:20-24`); sub-agent lifecycle rows from `subagent.*` (`AS/runtime/RuntimeSession.kt:493-506`);
  `NOTICE` rows from `app.note` facts (`Turns.note`, e.g. connector notes); plugin rows (6.2).
- An imported turn (`import.turn`, `source = APP_SERVER`) gives the rows `transcriptItems` gives today
  (`AS/session/SessionStore.kt:215-259`): header, one row per tool with its previews, the reply; the "Runtime result"
  row only when a tool failed, as for live turns.

Ids:

- `ConversationItem.id = (seq shl 12) or part`, where `"<seq>"` or `"<seq>.<part>"` is the runtime item's id; a fact
  row's `seq` is the fact's; an imported turn's rows use the `import.turn` seq with `part` 0 for the header,
  1 to 4000 for its tools, 4001 for the reply and 4002 for the result row. `seq` must stay below 2^40; a thread that
  reaches it refuses new turns (`LOG_FULL`).
- Rows that are not part of the conversation (configuration missing, busy, approval mode changed, deletion results,
  the "Runtime intake" and diagnostic rows) are **ephemeral**: negative ids from a per-process counter, kept in memory
  after the logged row they followed, never restored. Today's restore drops them too.
- `ToolCallItem.startedAt`/`finishedAt` are `tool.started.t`/`tool.result.t`; `ConfirmationItem.createdAt`/`resolvedAt`
  are `approval.asked.t`/`approval.decided.t`; `ConfirmationItem.mode` is `approval.asked.policy`, so an external run
  shows the policy it ran with.
- `TranscriptStore` keeps only the loaded window of P11's rows for the focused and followed threads, the ephemeral
  rows and the fold flags (client-side, as today). `submitText` no longer opens a header before admission
  (`AS/runtime/RuntimeSession.kt:747`): the header appears when the turn's `user.message` is logged, so a busy or
  misconfigured start leaves none.
- `thread/read` and `thread/select` return the last 300 rows and `hasOlder`; `thread/readOlder` pages back. A loaded
  and an unloaded thread give the same rows with the same ids. `item/removed` is sent only for ephemeral rows.
- Live updates: `AppConversations` subscribes to each followed thread's records; after each, the loaded window is
  refreshed from P11 and `StatePublisher.itemChanges` (`AS/server/StatePublisher.kt:94-116`) diffs it by id as
  today; text deltas come from the run's `ItemDelta`s. P11's state holds the newest 300 rows, per-turn summaries and
  the board rows, so applying a record stays cheap; `thread/readOlder` folds the older turns' records on demand, as P4
  does (section 11).

### 5.3 Protocol changes (`agent-app-protocol`)

```kotlin
const val PROTOCOL_VERSION = 2                                                   // P/Conversation.kt:7

sealed interface ConversationItem {
    val id: Long
    val turnId: String? get() = null      // new; every item class gains `override val turnId: String? = null`
    val at: Long? get() = null            // new; epoch ms of the record the row comes from
}

data class ThreadSnapshot(val threadId: String, val items: List<ConversationItem>, val hasOlder: Boolean = false)

/** thread/readOlder -> ThreadSnapshot of up to [limit] rows before [beforeItemId]. */
data class ThreadReadOlderParams(val threadId: String, val beforeItemId: Long, val limit: Int = 200)

/** turn/steer; the JSON of TextParams plus an optional thread. */
data class TurnSteerParams(val text: String, val threadId: String? = null)
```

- Every addition is an optional field or a new method, so a version-1 client keeps working
  (`ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls = false`: `P/jsonrpc/JsonRpc.kt:53-58`). `ServerClient`
  checks `protocolVersion >= 2` before using `thread/readOlder`; Bothy's clients are built with the server.
- What changes meaning: ids are stable across restarts and reloads, sparse, negative for ephemeral rows, and say
  nothing about order (list order does); `SessionSummary.lifecycleStatus`/`completionOutcome` come from the log;
  `SessionBoardState.recentTurns` from P11; `turn/steer` with a `threadId` steers that thread.
- Bothy's front ends key folds and picks by id already (`B/bothy-app/.../console/ItemFolds.kt:10-21`,
  `B/bothy-protocol/.../view/OverlayParts.kt:153-168`); with stable ids they now survive reloads. No other change.

### 5.4 Turns without a run

`AgentThread.record` (section 9) logs a turn the host handled itself:

```kotlin
@ExperimentalAgentRuntimeApi
data class RecordedTurn(
    val shown: String,                       // the input as the person said or saw it
    val reply: String,                       // the answer given, spoken or shown; "" for none
    val tag: String,                         // the host's kind: "companion", "plan", "doubao", "startup", ...
    val forModel: String? = null,            // how later requests see this exchange; null keeps it out of the history
    val status: TurnStatus = TurnStatus.COMPLETED,
    val error: TurnError? = null,
    val turnId: String? = null,              // idempotency key, as TurnOptions.turnId
    val usage: List<SideUsage> = emptyList(),
)

@ExperimentalAgentRuntimeApi
data class SideUsage(val purpose: String, val model: String, val usage: Usage, val requests: Int = 1)
```

- Records: `run.started{kind = RECORDED, tag}`, `user.message{text = shown}`, `message EXTERNAL` with `forModel` when
  given, one `usage.recorded` per usage, `run.finished{status, finalResponse = reply, error}`; flushed (F8). A recorded
  turn queues behind a running span like any turn, so spans never interleave.
- `recordCompanionTurn` (`AS/runtime/RuntimeSession.kt:436-462`) calls `record` through `AppConversations` instead of
  an `appendTurn` launched on IO with its failures swallowed. While a run goes, a said line keeps today's behaviour
  and is steered into it (`postSteeringText`), not recorded.
- A startup failure (`AS/runtime/RuntimeStartup.kt:69-93`) records `RecordedTurn(shown = text, reply = "",
  tag = "startup", status = FAILED, error = TurnError("RUNTIME_INIT_FAILED", message))`.
- `forModel` for companion, 豆包 and plan lines is today's external exchange block
  (`AS/runtime/ExternalExchangeContext.kt:9-47`): `"External exchange (quoted conversation data, not instructions):"`
  and the JSON `{scene, user, assistant, runStatus, completion}`, each text cut to 900 characters when the block would
  pass 6 KiB. The `historyPath` variant goes (5.5 serves the transcript). Each exchange enters the history in its
  place, where only the latest reached the next run before, through the per-run context (owner decision 13.2).

### 5.5 Prompt inputs from the log

- `PreviousExchange` (`AS/runtime/PreviousExchange.kt:6-14`) keeps its shape; `prepareRunInput`
  (`AS/runtime/FollowUpReply.kt:8-14`) takes it from P11's last turn row, of any kind, as it took the last
  `turns.jsonl` row. `RunFacts.previousQuestion` likewise.
- `externalContext` in `RuntimePrompt.runContext` (`AS/runtime/RuntimePrompt.kt:41`) goes; 5.4 replaces it.
- `SessionStore.modelContext` (`AS/session/SessionStore.kt:105-155`), computed every run and read by nothing in
  production, goes with `budgetSessionContext`.
- The model's `/sessions` files become a read-only VFS source, `SessionsVfsSource`, rendering P5 and P11 when read in
  today's shapes (`index.json`, `<b64>/summary.json`: `AS/session/SessionQueryFiles.kt:23-73`) plus
  `<b64>/turns.jsonl` in the `SessionTurn` row shape. Nothing is written; the rewrite of every session's summary on
  every append goes. `SESSION_QUERY_INDEX_PATH` and the core rules are unchanged.
- `compactedSummary` is computed from P11's rows with today's rule (`AS/session/SessionStore.kt:157-164`).

### 5.6 List, titles, deletion

- `thread/list` reads P5 through `AppConversations`, without threads that have a parent, newest first.
- A new conversation is `AppConversations.startThread(id)` (F1). The app server appends `thread.title{AUTO}` at the
  first turn while the thread has none, with today's rule (`AS/session/SessionStore.kt:82, 269-270`: the shown text's
  first line, 32 characters, `"Debug session"` when blank). Recorded turns name a thread too.
- Deletion (`AS/runtime/SessionDeletion.kt:27-42`): stop the session's run as today; `AppConversations.deleteThread`
  closes the thread's log (pending lines written or dropped), renames its directory and its sub-agent threads' (P9)
  into `.trash/`, fsyncs `threads/`, drops them from P5 and the usage index, and removes the files in the background;
  then plugin data clearing (`DataClearing`) as today. Host-stream facts naming the thread stay; plugin projections
  ignore threads that no longer exist. The in-memory tombstones and the "only if the writer stopped" branch go.
- Pruning past `max_sessions` (`AS/runtime/RuntimeSession.kt:94-106`) deletes the same way.

### 5.7 The debug trajectory and `tools/`

- `TrajectoryRepository` (`AS/trajectory/TrajectoryRepository.kt`): the run catalog is P7's spans of the thread,
  recorded spans included, plus the archived runs of an imported thread (no more `turns.jsonl` merge, `:288-324`, nor
  missing crashed runs); a run
  loads with `diagnostics.querySessionEvents(SessionEventQuery(threadId, spanId = id))`; the live tail is
  `subscribeSessionEvents(threadId)` on `AppConversations`. The reader runtimes, read slots and wipe barrier
  (`TrajectoryStorage.kt:94-216`) go.
- `TrajectoryProjector` maps v3 records; its v2 mapping stays as `projectLegacy`, for archived runs of imported
  threads read with `diagnostics.legacyEvents(threadId)`.
- Bothy: `TrajectoryInspector` (`B/bothy-app/src/main/java/cc/noharry/bothy/app/trajectory/TrajectoryInspector.kt:173`)
  and `trajectory_empty_hint` name the v3 log.
- `tools/session_cache.py` pulls `threads/<sha256>/log/*.jsonl` and `blobs/` from `no_backup/app-server` (default) or
  `--root no_backup/agent-runtime/conversations`, takes `--thread <id>`, resolves `$blob` and `$ref`, and lists children
  from `subagent.spawned`. `tools/session_latency.py` reads `model.request`/`model.response` (`firstEventMs`,
  `durationMs`) and `tool.started`/`tool.result`.

## 6. Host and plugin facts

### 6.1 The runtime's API

Experimental members with default implementations (a default throws `UnsupportedOperationException`; the runtime's
own classes implement them):

```kotlin
interface AgentThread {                                                   // API/AgentThread.kt
    // ...existing members...
    @ExperimentalAgentRuntimeApi suspend fun record(turn: RecordedTurn): TurnResult                 // 5.4
    @ExperimentalAgentRuntimeApi suspend fun appendFact(fact: FactInput): Long                      // its seq
    @ExperimentalAgentRuntimeApi suspend fun recordUsage(usage: SideUsage, turnId: String? = null): Long
    @ExperimentalAgentRuntimeApi suspend fun putBlob(bytes: ByteArray, mediaType: String): SessionAttachmentRef
    @ExperimentalAgentRuntimeApi suspend fun <S : Any> read(projection: LogProjection<S>): S
}

interface AgentRuntime {                                                  // API/AgentRuntime.kt
    // ...existing members...
    @ExperimentalAgentRuntimeApi val hostLog: HostLog
    /** Usage per local day and model across every thread and the host stream (P6's index). */
    @ExperimentalAgentRuntimeApi suspend fun usage(): List<UsageBucket>
}

@ExperimentalAgentRuntimeApi
data class UsageBucket(val day: String, val model: String, val requests: Int, val usage: Usage)

@ExperimentalAgentRuntimeApi
interface HostLog {
    suspend fun appendFact(fact: FactInput): Long
    suspend fun recordUsage(usage: SideUsage): Long
    suspend fun putBlob(bytes: ByteArray, mediaType: String): SessionAttachmentRef
    suspend fun readBlob(sha256: String): ByteArray?
    suspend fun <S : Any> read(projection: LogProjection<S>): S
    suspend fun events(afterSeq: Long = -1, limit: Int? = null): List<SessionEvent>
    fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable
}

@ExperimentalAgentRuntimeApi
data class FactInput(
    val namespace: String,                 // [a-z][a-z0-9-]{0,31}; "runtime" and "app" are reserved
    val kind: String,                      // [a-z][a-z0-9._-]{0,63}
    val body: SessionJson.Object,          // at most 64 KiB encoded
    val key: String? = null,               // idempotency key, at most 128 characters (6.3)
    val durable: Boolean = true,           // false: returns before F7 (high-rate facts)
)
```

`AgentThread.appendFact` on a thread whose span is open puts the fact in that span. While a tool runs, the runtime puts
a `ToolCallElement(threadId, span, turn, callId, startedSeq)` in the tool's coroutine context; a fact appended from
inside the call (to its own thread or to another log) gets `about.callId` and `about.thread`, and on its own thread
`cause = [startedSeq]`.

### 6.2 `PluginContext`

`AS/server/AppServerPlugin.kt` (plugin level, `@AppServerPluginApi`), all additive:

```kotlin
interface PluginContext {
    // ...existing members...
    /** This plugin's facts, namespaced by its id. */
    val facts: PluginFacts
    /** This plugin's model calls' usage (side calls), kept in the log. */
    val usage: PluginUsage
    /** Values the plugin holds that are credentials; redacted wherever they would be stored (1.6). */
    fun redact(values: () -> Collection<String>)
    /** The thread on screen, or null; for facts that happen outside any run. */
    fun currentThread(): String?
}

@AppServerPluginApi
interface PluginFacts {
    val namespace: String
    /** Appends to [threadId]'s log, or the host stream when null; durable when it returns; a repeated key returns the first seq. */
    suspend fun append(threadId: String?, kind: String, body: JsonObject, key: String? = null): Long
    /** Appends without waiting (voice); subject to the rate limit (6.7). */
    fun post(threadId: String?, kind: String, body: JsonObject)
    suspend fun putBlob(threadId: String?, bytes: ByteArray, mediaType: String): String      // sha256
    suspend fun readBlob(threadId: String?, sha256: String): ByteArray?
    /** [projection] over this plugin's facts and the runtime records it reads, per thread and in the host stream. */
    fun <S : Any> projection(projection: LogProjection<S>): FactView<S>
    /** How this plugin's facts of [kind] show in the transcript; pure; null shows nothing. */
    fun renders(kind: String, row: (Fact) -> TranscriptRow?)
}

@AppServerPluginApi interface FactView<S : Any> {
    suspend fun thread(threadId: String): S
    suspend fun host(): S
    fun follow(listener: (threadId: String?, state: S) -> Unit): AutoCloseable
}
@AppServerPluginApi data class Fact(val threadId: String?, val seq: Long, val at: Long, val kind: String, val key: String?,
    val body: JsonObject, val callId: String?, val turnId: String?)
@AppServerPluginApi data class TranscriptRow(val role: TranscriptRole, val text: String, val title: String? = null)
@AppServerPluginApi interface PluginUsage { fun record(threadId: String?, purpose: String, model: String, usage: ModelUsage) }
```

Also: `RunFacts.threadId: String` (a plugin knows the run's thread; `AS/runtime/AppHost.kt:166-189` has none today);
`Delivery(..., key: String? = null, threadId: String? = null)` (6.4); `Turns.record(words, reply, label, speak,
forModel: String? = null, usage: List<SideUsage> = emptyList())`; `Turns.note(text)` appends `app.note` to the thread
on screen. `PluginUsage.record` sums per `(log, purpose, model)` in memory and appends one `usage.recorded` every 30 s,
at the end of a run on that thread and when the server stops. `UsageStore.record` goes; `UsageStore` keeps prices and
reads P6's index.

### 6.3 Ordering and idempotency

- **Before the result.** A fact a plugin appends while its tool runs is in the log before that call's `tool.result`
  and durable before the tool returns. If the process dies first, repair closes the call as `TOOL_OUTCOME_UNKNOWN` and
  the fact still says what the plugin did; the plugin's projection agrees with its effect.
- **Files are caches.** A plugin that keeps a file writes it only after the fact it reflects is durable, stores the
  seq it reflects inside it, and rebuilds it from its projection at start when it is behind or unreadable.
- **Keys.** Within one log, `(namespace, kind, key)` is unique (P10): a second append with the same key writes nothing
  and returns the first seq. Retries, re-deliveries and restarts use keys so a fact is never doubled.
- **Across logs** there is no order. A plugin that writes to a thread and the host stream appends first the one that
  must survive.

### 6.4 Deliveries

`RuntimeSession.deliver` (`AS/runtime/RuntimeSession.kt:534-560`) keeps its in-memory queue and its idle wait, and
logs each step in the host stream:

1. `delivery.queued{key, plugin, thread?, shown, title, forModel, said?, showCard}` (F7) when `deliver` is called. A
   `Delivery` without a key gets `"<plugin>:<uuid>"`.
2. When its turn comes: the target is `Delivery.threadId` if that thread exists, else the thread on screen.
   `delivery.claimed{key, thread, turn | requestId}` (F7), with `turn = requestId = "dlv-" + sha256(key).take(16)`.
3. The delivery as today: a new turn with that turn id (`turnOrSteer`/`submitText`), steering with that request id,
   or `record` with that turn id for a said line. Its `run.started` (F10), `input.received` (F6) or recorded span (F8)
   is durable.
4. `delivery.done{key, thread, turn, outcome}`, then the plugin's `delivered()` callback.

At server start P12 lists deliveries not done. A claimed one whose turn or request id is in the claimed thread (P3/P4)
is marked done; any other is queued again, to its claimed thread if any. Sub-agent reports (key
`subagent:<parent>:<child ids>`, thread = parent) and A2A reports (thread = the delegation's) go to their own thread
instead of the one on screen.

### 6.5 Bothy's plugins

**Plans** (`PL/plans`). The plan book becomes a projection of host-stream facts (`ns = "plans"`); `plans.json`
(`PlanRunner.kt:355`) becomes its cache (6.3).

| Fact (host stream) | When | Key |
|---|---|---|
| `book{op: CREATE/UPDATE/PAUSE/RESUME/DELETE/END, plan}` with `about.thread`/`callId` when from `plan_manage` | `PlanRunner.write` from the tool (before its result) and from page routes (before the route answers) | none |
| `fired{planId, dueAt, firing, title}` | first step of `fire` (`PlanRunner.kt:193-222`); the book projection marks it fired | `fired:<planId>:<dueAt>` |
| `missed{planId, dueAt}`, `runaway{planId}` | `miss`, `pauseRunaway` | `missed:<planId>:<dueAt>` |
| `action{planId, dueAt, index, server, tool, arguments}` then `action.done{..., ok, code, preview}` | around each direct MCP call (`PlanActions.kt:162-184`) | `action:<planId>:<dueAt>:<i>`, `action-done:...` |
| `imported{plans, history}` | import of `plans.json` (8.4) | `import-0.5` |

`fire` order: `fired` (durable) -> chime and notice -> actions with their facts -> `ctx.deliver(Delivery(...,
key = "plans:<planId>:<dueAt>"))`. The history lines (`reminded`, `handed`, `late`) come from `delivery.done`, no
longer claimed at enqueue. At start: a `fired` without its delivery queued is delivered (marked late past the
catch-up window); an `action` without `action.done` gets `action.done{ok = false, code = "OUTCOME_UNKNOWN"}` and is
not run again. Routes `plans/setEnabled` and `plans/delete` answer after their fact is durable. The watcher's
trackers (`PlanWatcher.kt`) stay in memory: sensor state, not conversation.

**Cards and offers** (`PL/cards`, package `a2ui`). Thread facts (`ns = "cards"`): `offer{options, controls,
cards}` appended inside `offer_*`/`show_card` before the result, with the cards as `CardHooks.finish` enriched them
(`B/bothy-server/.../cards/BothyCards.kt:28-45`); `pick{offer, ordinal?, label, calls, input}` appended by
`prepareInput` before the run (`OfferOptions.kt:79-102`). `OfferStore` (`OfferOptions.kt:24-73`) becomes a projection:
the thread's latest offer with its turn and time, and its latest pick; the 30-minute and same-turn rules are applied
when read; `endTurn` goes (a later turn makes the offer stale). `ServerState.offers` is published from it. Offers,
enriched cards and picks survive a restart.

**Drawn pages** (`PL/pages`). No fact: the page is the `show_html` call's arguments in the log. A projection maps each
page file name (from the call's result) to its `tool.call`. `show_html` writes the cache file atomically (temp,
fsync, rename) instead of `writeText`; `pages/read` serves the cache file, else the call's `html` from the log
(writing the cache again). Pruning the cache to 30 no longer breaks older references.

**Pictures** (`PL/images`). `image_generate` puts the bytes in the thread's blobs (`facts.putBlob`), appends
`generated{sha256, mediaType, width, height, prompt}` and returns the reference
`generated-images/<b64 thread>/<sha256>.<ext>`; the image routes resolve such a reference from the thread's blobs and
older ones from their files. `files/generated-images/<session>/` is no longer written; existing folders stay and are
deleted with their conversation as today. `PictureBoard` (live progress) and test generations are unchanged.

**Memory** (`PL/memory`). The book `memories.json` stays: it is the person's memory, not conversation. The
`user_memory` tool appends a thread fact `changed{op, id, category}` before its result (the content is in the call's
arguments). Page edits append nothing.

**A2A** (`PL/a2a`). Host-stream facts (`ns = "a2a"`), each with `about.thread` = the thread of the call:
`delegated{taskId, profileId, agentLabel, request, contextId?}` (key `task:<taskId>`) inside `a2a_message_send`
before its result, replacing `track()`'s write of `a2a-delegations.json` (`A2aDelegations.kt:247-260`);
`state{taskId, state}` on state changes only, not every poll; `report{taskId, kind: DONE/ASKING/FAILED/LOST, display,
model, pictures: [sha256]}` (key `report:<taskId>:<n>`) with the pictures in host blobs, appended before the task
leaves the open set, then `ctx.deliver(Delivery(..., key = "a2a:<taskId>:<n>", threadId = about.thread))`;
`answered{taskId}`. The open delegations, which the poller resumes at start, and the pending questions (an `ASKING`
report not answered within 30 minutes) are a projection; the file goes. Inbound tasks are threads already
(`AppA2aRequestHandler.kt:36-78`); client mode (`A2aClientSession`) mirrors a remote task live and keeps nothing.

### 6.6 Bothy's voice layer

Facts (`ns = "voice"`) are posted (not awaited) to the thread the speech belongs to: a run's spoken lines to that run's
thread with its turn, heard speech and judgments to the thread on screen (`currentThread()`), else the host stream.

| Fact | When | Fields |
|---|---|---|
| `heard` | a final segment the judge hands over (new turn, append, replace, companion) | `text` (at most 2000 characters), `startMs`, `endMs`, `engine` |
| `judged` | each decision that acted (not each re-ask) | `decision`, `scores{turnComplete, holdingFloor, addressed, takingFloor?, replacesTask?, endsSession?, smallTalk?}`, `model`, `latencyMs` |
| `spoken` | a line starts playing (`TtsAnnouncer`, `RunSpeech`, `RealtimeVoice.tellLate`) | `text` (at most 400), `kind`: `PROGRESS/FINAL/QUESTION/ACK/LATE/A2A/FAILURE` |
| `cut` | barge-in, wake, the card's X, "退下吧" | `spoken` (seq), `atMs`, `by` |
| `silenced` | the card's X silences a run (`RunSpeech.silence`) | `turn` |
| `dropped` | the rate limit dropped facts (6.7) | `count`, `sinceMs` |

- Companion replies (`B/bothy-server/.../companion/SmallTalkCompanion.kt:63-96`) and 豆包 replies without
  `ask_xiaobo` (`RealtimeVoice.kt:427-437`) are recorded turns (`turns.record(..., forModel, usage)`).
- Shown and model text: the voice append (`VoiceController.kt:693-698`) steers with `shown = text` and the framed text
  for the model; `ask_xiaobo` starts turns with `shown` = the request without "用户原话" and the model text with it.
  The bubble survives a reload.
- Side calls' usage goes through `ctx.usage.record`: the Jev judge (`voice.judge`), the ack (`voice.ack`), 豆包
  Seeduplex (`voice.realtime`), the companion (`companion`, not recorded today), Jev tool search (`tool-search`, not
  recorded today), the health check (`check`).
- Not logged: audio, partial transcripts, levels, `VoiceLog` lines, wake events, speech the judge did not hand over
  (side talk), `ask_xiaobo` call bookkeeping (it lives with the realtime connection).
- A setting in Bothy's settings page, "Keep voice history" (owner decision 13.1 sets its default), turns off `heard`,
  `judged`, `spoken`, `cut` and `silenced`; recorded turns and usage are kept regardless.

### 6.7 Limits and privacy

- `post` is limited per log and namespace by a token bucket of 30 with one token a second; a post without a token is
  dropped and counted, and the count is posted as `dropped` at most once a minute. `append` is not limited (it is for
  facts a plugin must keep).
- A fact's body is at most 64 KiB; voice texts are cut as in 6.6.
- Logs are in no-backup storage and go with their conversation (5.6) or its pruning; the host stream keeps only facts
  that belong to no conversation. Known credentials are redacted (1.6). Speech not addressed to the agent is never
  written.
- Volume, for scale: a 10-minute voice session with 40 handed-over utterances and 60 spoken lines writes about 200
  voice facts, about 60 KB.

## 7. Sub-agents as threads

- **The child is a thread** in the same root, with the id the engine gives it today (`spec.sessionId`) and record 0
  `thread.created{origin = SUBAGENT, parent = {thread, seq of subagent.spawned, callId, depth}, options}`; the
  options carry its approval policy, which `Subagents.approvalPolicyFor` (`R/runtime/Subagents.kt:112-114`) reads from
  P3 instead of memory. `isSubagentSession` is `origin == SUBAGENT`. The app server's child runtimes
  (`AS/skills/AppSubagents.kt:203-236`) use the common root instead of their own event-log roots.
- **The parent's log is the catalogue**: `subagent.spawned{child, agent, label, task, callId}` before `spawn_agent`'s
  result; `subagent.settled{child, state, result, error, childSeq, usage}` when the child's run ends (inside the
  parent's span if one is open, else outside); `subagent.delivered{children, via, callId | turn}` when the model gets
  the result: before `wait_agent`'s result, or when the report turn's `run.started` is durable (with key
  `subagent:<parent>:<children>`, 6.4).
- **Progress** (`SubagentStatus`: tool and model calls, stalls) is read live from the child's P7 and P4; the watchdog's
  timers stay in memory.
- **Restart.** `Subagents` gains `attach(runtime: AgentRuntime)`, called by the builder of a runtime that has both
  `.subagents(s)` and storage. On its first attach in a process it reads P5 for threads with `pendingChildReports > 0`
  and their P9: a child settled and not delivered is reported again through `onReport`; a child still `RUNNING` with
  no live job is settled from its own log: finished (its P3 has a `run.finished`) gives `FINISHED` with its final
  response, anything else (repair interrupted it) `INTERRUPTED`; then it is reported. An undelivered report survives
  any number of restarts.
- **The board.** `AppSubagents`' board, deliveries map and usage (`AS/skills/AppSubagents.kt:54-201`) are P9 and P6;
  `subagents.jsonl` goes. `drainSession` and `cancelSession` keep stopping live jobs; deleting a parent deletes its
  children's threads (5.6).

## 8. Migration of existing data

### 8.1 How

- **Import once, then archive.** Nothing reads an old format after the import; old readers live only in the importer
  (`R/runtime/log/legacy/` and `AS/session/legacy/`). Where this section says a thread's or the app server's legacy
  files are deleted after a successful import, in this release they are moved instead, unchanged, to
  `<root>/legacy-0.5/` under their old relative paths (one rename per file or folder, same volume): a wrong import can
  then be repaired from them by hand. Nothing reads that folder; "clear runtime data" and deleting a conversation
  remove its part of it. Files this section calls diagnostics (health, event-id lists, the reliability tree,
  `loop-history.json`, the SQLite store) are deleted as written.
- `LegacyImport.run(root)` runs once per root per process, before the first `startThread`, `resumeThread`,
  `listThreads` or app server `initialize` completes (they await it), off the main thread. It does nothing once
  `<root>/imported-0.5` exists; it writes that marker when no legacy file is left.
- **Per thread**: build the v3 log in `.staging/<uuid>/` (records below, then `thread.imported`), flush, verify,
  rename to `threads/<sha>/`, fsync `threads/`, then delete the thread's legacy files. Verification: P4's turn count
  equals the old turn count, P1's message count equals the old history's, the options equal; for app server sessions,
  P11's row count equals what `SessionStore.transcriptItems` would give. A crash before the rename leaves the old files
  and imports again; after it, the start deletes the old files of a thread that has `thread.imported`.
- **Failure**: the staging is removed, the old files are kept, `import.failed{source, path, error}` goes in the host
  stream, and the next start tries again. Meanwhile the thread is listed with its old title (read from `meta.json` or
  `thread.json` only) and status `UPGRADE_FAILED`; opening it shows one error row. Nothing is lost.
- The first request of an imported thread writes `request.header` `INITIAL`: the provider's prompt cache misses once.

### 8.2 SDK runtime data (`storage(dir)`, and the Android builder's `event-log`)

| Old | Imported as |
|---|---|
| `<sha>.thread.json` (v2; v1 with embedded turns, `ThreadStore.kt:168-178`) | `thread.created{origin = IMPORTED, createdAt, options}`; remembered domains as `approval.decided{state = APPROVED, by = USER, remember = domain, reason = "imported"}` (no `callId`); a pending question as `question.asked` after the last turn |
| `<sha>.data/turns/*.json`, in `turnIds` order | `import.turn{source = SDK, turn}`; items keep their old ids |
| `<sha>.history.json` and `.data/blobs` | one `message{origin = IMPORTED}` per message, each run's messages with span `i-<n>` (no `run.*` records), images as blobs |
| `<sha>.attempts.json`, `*.corrupt` | dropped: an attempt without a snapshot was abandoned and shows nothing |
| v2 streams (`events/session-events/v2/streams/<session>/<run>/`, or `<event-log root>/...`) | moved to `threads/<sha>/legacy/v2/<run>/`; `thread.imported.legacy` names it |
| `events/` health, event-id lists; `runtime/` reliability tree; `runs/*/loop-history.json` | deleted (diagnostics no person sees) |
| The SQLite session store of `androidSqliteSessions()` | deleted (written, never read by any API) |

### 8.3 App server data

| Old | Imported as |
|---|---|
| `filesDir/runtime-debug-workspace/sessions/<b64>/turns.jsonl` | one `import.turn{source = APP_SERVER, turn = row}` per line, in file order |
| `.../sessions/<b64>/meta.json` | `thread.title{title, source = AUTO}`; `createdAt` = the first row's start, else `updatedAtEpochMillis` |
| `noBackup/debug-session-event-log/<b64>/` | its thread store files as 8.2 (history, options, remembered domains, pending question); its SDK turn snapshots are skipped, the rows above being what the app showed; its v2 streams archived |
| `<b64 root>/subagents.jsonl` | per line, in the parent: `subagent.spawned`, `subagent.settled{state, result, error, usage}`, `subagent.delivered{via = REPORT}`; the child's own root (`<b64(child)>`) imported as a thread with `origin = SUBAGENT` and its parent |
| A debug-session-event-log root with no session folder | imported when it holds model history (titled from its first input); deleted when it holds only v2 streams |
| `sessions/index.json`, `<b64>/summary.json` | deleted (now rendered, 5.5) |
| `filesDir/model-usage/usage-ledger.json` | host stream `usage.imported{day, model, usage, requests}` per day and model; deleted. `model-prices.json` stays |
| `debug-run-evidence/`, `runtime-debug-logs/`, `agent-reliability/`, `artifacts/` | left as they are; still removed by "clear runtime data" (`AS/runtime/RuntimeDataCleanup.kt:56-81`) and with their conversation |

### 8.4 Bothy plugin data

| Old | Imported as |
|---|---|
| `files/plans/plans.json` | host fact `plans/imported{plans, history}` (key `import-0.5`); the file is then the book's cache |
| `noBackupFilesDir/a2a-delegations.json` | host fact `a2a/delegated{..., imported = true}` per open delegation, with no thread: its report goes to the thread on screen, as before; the file is deleted |
| `files/user-memory/memories.json`, `files/drawn-pages/`, `files/generated-images/`, preferences | unchanged |

## 9. Public API, protocol and behaviour changes

Every change, by module. **Additive** compiles and runs unchanged for existing callers; **Binary** is
source-compatible but changes a data class's constructor or `copy` (callers recompile; `apiCheck` shows it);
**Breaking** removes or changes something callers use; **Behaviour** keeps the API and changes what happens. Every
non-additive row goes in `CHANGELOG.md` under 0.6.0.

**`agent-api`**

| Change | Kind |
|---|---|
| `SessionEvent` becomes `(schemaVersion = 3, threadId, seq, recordedAtEpochMillis, type, spanId?, turnId?, causes, ignorable, payload)`; `eventId`, `sessionId`, `runId`, `requestId`, `sequence`, `sourceEventSequences` go | Breaking (experimental use) |
| `SessionEventType`: one constant per record type of 1.12, with `wireName`, plus `UNKNOWN` for ignorable records of unknown type; the v2 constants go | Breaking |
| `SessionEventPayload`: one subclass per record type (`ThreadCreated`, `RunStarted`, `UserMessage`, ..., `Fact`, `Diagnostic`, `Unknown(json)`); the v2 subclasses go | Breaking |
| `SessionEventAppend`, `SessionEventLog`, `SessionEventLogHealth`, `SessionEventLogHealthStatus`, `SessionEventLogDurability` removed | Breaking |
| `SessionEventQuery(threadId, spanId?, turnId?, types, afterSeq?, limit?)` | Breaking |
| `RuntimeDiagnostics`: `getSessionEventLogHealth` replaced by `logStatus(threadId): ThreadLogStatus`; `replaySessionEvents(threadId, turnId)` returns every span of the turn; `subscribeSessionEvents` is thread-wide; new `legacyEvents(threadId): List<LegacySessionEvent>` | Breaking (experimental) |
| `ModelClient.prepare`, `ModelClient.wireProfile` (experimental, with defaults) | Additive |
| `AgentThread.record`, `appendFact`, `recordUsage`, `putBlob`, `read(LogProjection)`; `AgentRuntime.hostLog`, `AgentRuntime.usage()` (experimental, defaults) | Additive |
| New types: `RecordedTurn`, `SideUsage`, `FactInput`, `LogProjection`, `HostLog`, `UsageBucket`, `ThreadLogStatus`, `LegacySessionEvent` | Additive |
| `TurnInput.shown`, `SteerOptions.shown`, `TurnOptions.tag`, `ThreadItem.UserMessage.shown`, `ThreadInfo.title`, `ThreadInfo.parentThreadId`, `ThreadInfo.lastOutcome` (the last turn's completion outcome) | Binary |
| `TurnErrorCodes`: `PROCESS_ENDED`, `LOG_CORRUPT`, `LOG_LOCKED`, `LOG_NEWER_FORMAT`, `LOG_FULL`, `MODEL_CLIENT_CONTRACT` | Additive |
| `ThreadItem` ids are log-derived (`"<seq>"`, `"<seq>.<part>"`), not `"$turnId:item:N"`; `Approval`/`Question` ids likewise | Behaviour |
| `TurnResult.usage` counts side calls recorded on the turn | Behaviour |

**`agent-runtime`**

| Change | Kind |
|---|---|
| `AgentRuntimeBuilder.sessionEventLog(SessionEventLog)` and `sessionEventLogRoot(File)` removed; `storage(dir)` is the only location | Breaking |
| `AgentRuntimeBuilder.sessionHistory(SessionHistory)` and `SessionHistory` removed (P1 replaces them; one storage root shares history across runtimes) | Breaking |
| `AgentRuntimeBuilder.sessionToolActivations` and `SessionToolActivations` removed (P2); `sessionActivationInheritance()` stays | Breaking (experimental) |
| `AgentRuntimeBuilder.sessionStore(factory)` and the kernel `SessionStore` removed | Breaking (internal API) |
| `SessionEventLogs.openFileBackedSessionEventLog` removed | Breaking |
| `AgentRuntimeBuilder.redact(values)`, `projection(LogProjection)`, `historyLimit(maxBytes)` (default 4 MiB, today's `SessionHistory` default) | Additive |
| `OpenAiModelClient`, `AnthropicModelClient`, the Responses client implement `wireProfile` | Additive |
| `Subagents.attach(runtime)`; reports and children survive restarts, `onReport` may deliver reports from before one | Additive, Behaviour |
| The storage layout (1.2); old files imported and deleted (section 8) | Behaviour |
| A log write failure fails the turn with `STORAGE_FAILED` instead of being counted | Behaviour |
| Known credentials are redacted everywhere, model-visible text included; credential-like keys no longer drop records | Behaviour |
| After a process death the next request includes the interrupted run and a notice; a pending approval is cancelled | Behaviour |
| ThreadEvents come from the log's projection: same events, stable ids, a few ms later (after the append) | Behaviour |

**`agent-runtime-android`**

| Change | Kind |
|---|---|
| `appPrivateSessionEventLog(context)` and `androidSqliteSessions()` removed | Breaking |
| `AndroidAgentRuntime.builder` no longer uses `agent-runtime/event-log` or SQLite; their data is imported or deleted | Behaviour |

**`agent-app-protocol`**

| Change | Kind |
|---|---|
| `PROTOCOL_VERSION = 2` | Behaviour (wire-compatible) |
| `ConversationItem.turnId`, `.at` on every item; `ThreadSnapshot.hasOlder`; `thread/readOlder` with `ThreadReadOlderParams`; `turn/steer` takes `TurnSteerParams` | Additive on the wire, Binary for Kotlin clients |
| Item ids stable, sparse, negative for ephemeral rows; `item/removed` not sent for the 300-row window | Behaviour |
| `SessionSummary.lifecycleStatus`/`completionOutcome` survive restarts; board turns from the log | Behaviour |

**`agent-app-server`** (experimental)

| Change | Kind |
|---|---|
| `PluginContext.facts`, `usage`, `redact`, `currentThread`; `PluginFacts`, `FactView`, `Fact`, `TranscriptRow`, `PluginUsage` | Additive |
| `RunFacts.threadId`; `Delivery.key`, `Delivery.threadId`; `Turns.record(..., forModel, usage)` | Binary |
| `UsageStore.record` removed; `UsageStore` reads the usage index | Breaking |
| Storage at `noBackupFilesDir/app-server`; no session store, evidence or debug-log files written | Behaviour |
| Deliveries are durable and go to their own thread (sub-agent and A2A reports to theirs) | Behaviour |
| Companion, 豆包 and plan line exchanges enter the model's history; the run context's external exchange block goes | Behaviour |
| A restored transcript equals the live one (thinking, steering, approvals, notes, times) | Behaviour |

**Bothy** (not published): the plugin and voice changes of 6.5 and 6.6, and the "Keep voice history" setting.

## 10. Enforcement

### 10.1 Tests in the SDK

| Test | Module | Proves |
|---|---|---|
| `RequestRebuildTest` (2.5) | `agent-runtime` | Every request of 15 scenarios rebuilds byte-equal from the log prefix, from memory and from disk |
| `AppRequestRebuildTest` | `agent-app-server` | The same through `AppModelClient` and each `agent-app-models` adapter; the transport got `serialize(rebuilt, profile)` |
| `ProviderSerializerTest` | `agent-runtime` | Each provider serialiser gives the same bytes for the same request and configuration |
| `ProjectionScratchTest` | `agent-runtime` | After every scenario, for every projection, registered `LogProjection`s included: `encode(live)` = `encode(fold from 0)` = `encode(snapshot at 3 random seqs + fold of the rest)` (`ProjectionTestHarness`) |
| `KillPointTest` | `agent-runtime`, `agent-app-server` | Below |
| `LogStoreTest`, `RedactionTest`, `SnapshotTrustTest` | `agent-runtime` | 1.3 to 1.10, 1.6, 3.2; including a second JVM process holding the lock |
| `LegacyImportTest` | `agent-runtime`, `agent-app-server` | Section 8 on the L0 fixtures: golden lists, items, history, titles, usage; re-import is a no-op; crash before and after rename; a corrupt file gives `UPGRADE_FAILED` and keeps the files |
| `TranscriptRestoreTest` | `agent-app-server` | A turn with tools, thinking, an approval, steering and a recorded turn: `thread/read` after a server restart equals the live snapshot, ids included, ephemeral rows aside |
| `DeliveryCrashTest`, `SubagentRestartTest` | `agent-app-server` | Kill at each step of 6.4 and 7: exactly one delivered turn |
| `ItemsLiveEqualsFoldTest` | `agent-runtime` | The ThreadEvents of a turn equal P4 folded from scratch |

**`KillPointTest`.** The log store reaches files only through `FileOps` (write, force, truncate, rename, list,
delete). A dry run of each scenario counts the flusher's batches; then, for every batch k, the scenario runs again with
a crash at batch k in three modes: after the write and before the force (once keeping the bytes, once losing them), a
torn last line, and after the force. A crash makes every file operation throw; the runtime is abandoned and a new
`ThreadLogs` opens the root. After reopening:

- the log opens `OPEN` (never `CORRUPT`) and no span is open;
- `SurfaceInvariant` holds and every `tool.started` has a `tool.result` or a closer;
- the thread is `IDLE` or `WAITING_FOR_ANSWER`; the projections pass `ProjectionTestHarness`;
- every scripted tool ran at most once across the crash and the next turn;
- the next turn's first request rebuilds byte-equal.

**Bothy's tests**: `PlanFiringCrashTest` (kill at each step of `fire`: delivered once, an action never run twice),
`A2aReportCrashTest` (kill between a report and its delivery: delivered once, to the delegation's thread),
`OfferRestartTest` (an offer and a pick survive a restart), `VoiceFactLimitsTest` (100 posts give 30 facts and a
`dropped`), and `ProjectionTestHarness` run over each plugin's projection after its tests.

### 10.2 Gates

- **`logCatalogCheck`** (a `buildSrc` task in `ciCheck`, beside `apiCheck`): each record codec declares its fields
  (name, JSON type, optional or not) and encodes with that list; the task writes `agent-runtime/log/log-catalog.txt`
  (type, `ign`, fields) and fails when it differs from the committed file; `./gradlew logCatalogDump` updates it. Within
  v3 a change is additive only: a new type, or a new optional field; renaming, retyping or making a field required
  needs v4 and its migration (1.10). `AGENTS.md` gets the rule.
- **`checkNoConversationFiles`** (a rule of `SourceRulesTask`): outside `runtime/log/legacy/` and
  `appserver/session/legacy/`, no source names `turns.jsonl`, `meta.json`, `.history.json`, `.thread.json`,
  `loop-history.json`, `usage-ledger.json`, `subagents.jsonl` or `a2a-delegations.json`; in `agent-app-server`'s
  `session`, `runtime` and `usage` packages no call to `writeText`, `appendText` or `writeBytes`. Bothy adds the same
  rule for its plugin modules (only the plans cache and the importers may name their files).
- `apiCheck` (existing) covers section 9.

## 11. Performance

**fsync on the turn's path.** A typical turn waits for F10, one F2 per round, one F3 per acting tool and F4: five to
eight flushes. Concurrent threads share batches. An f2fs fsync of a small append costs a few ms, so the expected cost
is tens of ms against seconds of model time. Records between flush points cost no I/O wait.

**Memory.** P1 holds the history as `SessionHistoryStore` does today, bounded by `historyLimit` (4 MiB), image bytes
loaded from blobs only when a request is built. The record tail kept in memory is the open span and the one before it.
P4 keeps every turn's index (turn id to seq range) and the full items of the newest 50 turns; an older turn's items are
folded from its seq range when asked (`items(afterTurnId)`), since item state is per turn.

**Long threads.** Snapshots every 512 records and at each turn's end bound the fold on open to the tail; segments let
the opener skip everything before the lowest `from`. The list reads P5's index, never log bodies.

**What to measure on the tablet** (unit L22), with Bothy built from the SDK checkout:

| # | Measure | Changes the design when |
|---|---|---|
| M1 | Flush wait (`model.response.flushMs`, F3 waits): p50, p95, p99 over a day of use; a device benchmark of 1 KB and 8 KB appends | p95 > 30 ms, or flush waits > 5% of a turn's wall time: F10 merges into F2, and a round's acting tools share one F3 before the first of them |
| M2 | CPU per append (encode, redact, projections), p95 | > 2 ms: projections that need no live state (P6, P7's trace) fold lazily on read |
| M3 | Opening a thread of 1k, 10k, 50k records, with and without snapshots | > 300 ms with snapshots: snapshot every 128 records, P4's window down to 20 turns |
| M4 | `listThreads` with 200 threads, index warm and cold | > 200 ms: P5 held in memory for the process |
| M5 | Memory of an open 10k-record thread | > 30 MB: lower `historyLimit`, P4's window down to 20 turns |
| M6 | Log bytes per turn over a week (median, p95) | median > 100 KB: blob threshold 8 KiB, and closed segments stored zstd-compressed (`.jsonl.zst`, read transparently, still v3) |
| M7 | Import time of the tablet's real data | > 5 s: import per thread on first open, listing the rest from their old titles meanwhile |

## 12. Work breakdown

Each unit is one coherent change for one agent. After each, the SDK passes `./gradlew ciCheck` and Bothy, which
builds against the SDK checkout beside it by default (`B/settings.gradle.kts:28-35`), passes
`python scripts/validate.py`; a unit that changes an API Bothy compiles against carries its Bothy commit. Until L23
the old stores keep working wherever a unit has not replaced them: new records are written beside them, then readers
move, then the old store goes. Nothing is released before L23.

```
L0 ─────────────────────────────┐
L1 ─┬─ L2 ─┬─ L4 ─ L5 ─ L6 ─┐   │
    └─ L3 ─┘                 ├─ L8 ─ L9 ─ L10 ─┬─ L11
          L2 ─ L7 (needs L0) ┘                  └─ L12 ─ L13 ─ L14 ─ L15 ─┬─ L16 ─┐
                                                                          └─ L17 ─┼─ L18, L19, L20 (parallel)
                                                                                  └─ L21 (after L16 and L17)
L22 measures after L15 and again after L21; L23 last.
```

**L0. Legacy fixtures.** SDK repo; a device for the real capture.
- Scope: before any other change, with the code as it is, a throwaway test-only generator runs scripted turns (backend
  calls, an approval approved and one denied, images, a blocking question, a cancelled run, a sub-agent, a companion
  turn) through the runtime and the app server, and copies the files they leave into
  `tools/fixtures/legacy-0.5/{sdk,app-server}/`; the same for Bothy's plugin files (`plans.json`,
  `a2a-delegations.json`) into Bothy's test resources. The generator is deleted in the same unit. A copy of a
  tablet's real Bothy 0.4.0 data (`adb run-as`: `no_backup/debug-session-event-log`,
  `files/runtime-debug-workspace/sessions`, `files/model-usage`, `files/plans`, `no_backup/a2a-delegations.json`) is
  kept outside both repositories, which are public; `LegacyImportTest` also runs on it when given
  `-PlegacyData=<dir>`.
- Depends: nothing. Parallel with L1 to L4.
- Accept: the synthetic fixtures and a `README.md` saying how they were made; the real copy taken.

**L1. Log store.** SDK, `R/runtime/log/`.
- Scope: `LogRecord`, the envelope codec (payloads as `JsonObject`), `ThreadLog`, `ThreadLogs`, `LogFlusher`,
  `FileOps`, segments and header lines, torn tail, `CORRUPT`/`NEWER_FORMAT`/`READ_ONLY`, `BlobStore`, staging and
  trash, the lock, `SecretValues` redaction, the host stream, an in-memory variant for runtimes without storage.
  Sections 1.2 to 1.11.
- Depends: nothing. Nothing calls it yet.
- Accept: `LogStoreTest`, `RedactionTest`, the two-process lock test.

**L2. Record catalogue.** SDK, `R/runtime/log/records/`, `buildSrc`.
- Scope: a payload class and codec for every type of 1.12, `$ref` and `$blob` encoding, `ModelRequestCodec` with the
  digest, `log-catalog.txt`, `logCatalogCheck` in `ciCheck`, `logCatalogDump`.
- Depends: L1. Parallel with L3.
- Accept: every type round-trips to identical bytes; the gate fails on an unrecorded change.

**L3. Projection framework.** SDK, `R/runtime/log/`.
- Scope: `Projection`, `FoldContext`, `ProjectionHost`, snapshots and their trust rules (3.2), `ProjectionTestHarness`.
- Depends: L1. Parallel with L2.
- Accept: `SnapshotTrustTest`; the harness on a toy projection.

**L4. History and rebuild, pure.** SDK.
- Scope: P1, `RequestHeader` fold and diff (2.2), `RequestRebuild`, `CompactionRequests`, `RoundSurface`,
  `SurfaceInvariant`, `BlockAssembler`.
- Depends: L2, L3.
- Accept: synthetic-log tests of header diff and fold (random tool changes), edits (nested replacements, inserts,
  image stripping), references, the image view; `RoundSurface` equals `CancellationLegalHistoryTracker` on random
  streams.

**L5. The loop logs requests and responses.** SDK (`R/kernel/core/loop/`, `R/model/`, `AS/model/AppModelClient.kt`).
- Scope: `ModelClient.prepare`/`wireProfile` and their forwarding in every wrapper; `AppModelClient`'s note moves to
  `prepare`; `ModelCalls.send`, still sending the request the old code builds, appends the header records,
  `model.request` (digest of what is sent), blocks, calls, response, retry; every `messages +=` also appends its
  `message` record; `thread.created`, `run.started`/`status`/`finished`, `user.message`; F1, F2, F3, F4, F10. The v3
  log is written beside every old store, which stay the source.
- Depends: L4.
- Accept: `RequestRebuildTest` scenarios 1 to 7, 10 and 14 rebuild byte-equal what the old path sent.

**L6. The loop logs interactions and edits.** SDK.
- Scope: `approval.*` with placeholders and their `REPLACE`, `question.*`, `input.*` and `STEER` messages,
  `loop.notice`, `obligation`, `todo.set`, `tools.activation`, compaction requests and edits, trim edits, cancellation
  closers through `RoundSurface`, the full `run.finished`; F5, F6.
- Depends: L5.
- Accept: `RequestRebuildTest` scenarios 1 to 10 and 14.

**L7. Importer, unwired.** SDK, `R/runtime/log/legacy/`.
- Scope: readers for `thread.json` v1/v2, `history.json` with blobs, turn snapshots; the staged log they produce
  (8.2); verification; the v2 archive move.
- Depends: L0, L2. Parallel with L3 to L6.
- Accept: `LegacyImportTest` on the SDK fixtures.

**L8. The model's input comes from the log.** SDK, and `AS/runtime/RuntimeBuilder.kt`.
- Scope: `ModelCalls.send` builds from P1 (2.4); the loop's `messages` list goes; resumption after approval and after
  an answer reads P1 and P7's budget and ledger (`obligation` records, `ledgerSalt`); repair (4.1); the importer runs
  at storage open; `historyLimit`. Removed: `SessionHistoryStore`, `SessionHistoryPersistence`, `SessionHistory`,
  `ThreadStore.modelHistoryPersistence`, `ModelHistoryCodec`, `LoopHistoryStore` and its trimmer, the app server's
  `RuntimeHistories` and `.sessionHistory(...)`.
- Depends: L6, L7.
- Accept: `RequestRebuildTest` scenarios 1 to 12 and 14; `KillPointTest`; Bothy's gate.

**L9. Runs, activation and todo from the log.** SDK.
- Scope: P2 (`SessionToolActivations` removed), P7 with `LogBackedReliabilityStorage` (the `runtime/` tree goes),
  P8 (`TodoStore` files go).
- Depends: L8.
- Accept: the diagnostics, activation-inheritance and todo tests on log-backed state; `ProjectionScratchTest`.

**L10. Items, thread state and the list from the log.** SDK.
- Scope: P3, P4 with log-derived ids and live events (`ThreadEventProjection`'s `AgentEvent` projection goes), P5;
  `ThreadStore` and `ThreadAttemptStore` go; `items`, `status`, `pendingRequests`, `steer(answering)`, `listThreads`
  and remembered domains from projections; the `shown`/`tag`/`title`/`parentThreadId`/`lastOutcome` fields.
- Depends: L9.
- Accept: the thread API tests (ids adjusted), `ItemsLiveEqualsFoldTest`, `apiDump`, Bothy's gate.

**L11. Public log API v3; the v2 log goes.** SDK and Bothy.
- Scope: `SessionEvent` v3 and the rest of section 9's `agent-api` log rows, `legacyEvents`; removal of the v2 writer
  and codec (but its reader, kept for `legacyEvents`), `RuntimeSessionRecorder`, the kernel `SessionStore`,
  `androidSqliteSessions`, `appPrivateSessionEventLog`, `sessionEventLog`, `sessionEventLogRoot`, `SessionEventLogs`;
  the trajectory on v3 with `projectLegacy`; `tools/session_cache.py`, `tools/session_latency.py`; Bothy's
  `TrajectoryInspector`, strings and androidTest.
- Depends: L10. Parallel with L12.
- Accept: trajectory tests, the tools' tests on fixture segments, `apiDump`, Bothy's gate.

**L12. Extension API.** SDK.
- Scope: `LogProjection`, `AgentRuntimeBuilder.projection`, `AgentThread.read`, `record`, `appendFact`,
  `recordUsage`, `putBlob`, `AgentRuntime.hostLog`, `AgentRuntime.usage`, P10, `ToolCallElement`,
  `AgentRuntimeBuilder.redact`.
- Depends: L10. Parallel with L11.
- Accept: API tests; fact keys and ordering (6.3); `RequestRebuildTest` scenario 13; `apiDump`.

**L13. Sub-agents as threads.** SDK (runtime and app server).
- Scope: section 7: child threads with their parent, `subagent.*`, P9, `Subagents.attach`, policy and origin from P3;
  `AppSubagents` from P9 and P6, `subagents.jsonl` goes.
- Depends: L12.
- Accept: `SubagentRestartTest`; `RequestRebuildTest` scenario 15; the board tests.

**L14. App server storage, store runtime and import.** SDK (`agent-app-server`).
- Scope: 5.1: one root, `AppConversations`, the runtime assembly, per-session roots and reader runtimes go; the app
  server importer (8.3) on top of L7, including the usage ledger; companion turns and startup failures through
  `record`. The app server's session files are imported but stay, and are still written and read, until L15.
- Depends: L13.
- Accept: `LegacyImportTest` on the app server fixtures; the app server's tests; Bothy's gate; on the tablet, a 0.4.0
  install upgraded keeps every conversation.

**L15. Transcript, protocol 2 and prompt inputs.** SDK and Bothy.
- Scope: 5.2 to 5.7 but usage: P11, ids, ephemeral rows, `thread/read`/`readOlder`, protocol 2; `SessionStore`,
  `SessionTurnRecorder`, `SessionQueryFiles`, `modelContext`, `externalContext` go; `SessionsVfsSource`;
  `PreviousExchange` from P11; recorded turns with `forModel`; titles; deletion; `ArtifactStore`, evidence and replay
  from the log; transport facts; the importer deletes the session files. Bothy: `ServerClient` paging in the console.
- Depends: L14.
- Accept: `TranscriptRestoreTest`; a version-1 client decoding version-2 messages; Bothy's gate.

**L16. Usage from the log.** SDK and Bothy.
- Scope: P6 and its index, `AgentRuntime.usage()`, `usage/read`, `PluginContext.usage`, `UsageStore.record` and
  `applyTokenUsage` go; Bothy's
  side calls (Jev judge, ack, Seeduplex, companion, tool search, check) through `ctx.usage.record`.
- Depends: L15. Parallel with L17.
- Accept: the scenario suite's usage equals the sum of its responses and side calls; imported days show; Bothy's gate.

**L17. Plugin facts and durable deliveries.** SDK.
- Scope: 6.2 to 6.4: `PluginFacts`, `FactView`, transcript rows, `RunFacts.threadId`, `currentThread`, `redact`,
  keyed and targeted `Delivery`, delivery records, P12 and re-delivery, `Turns.record(forModel, usage)`,
  `Turns.note`; sub-agent reports through keyed deliveries.
- Depends: L15. Parallel with L16.
- Accept: `DeliveryCrashTest`; the plugin API tests; the sample apps' tests.

**L18. Plans on facts.** Bothy, `PL/plans`.
- Scope: 6.5 Plans; the import of `plans.json`.
- Depends: L17. Parallel with L19, L20.
- Accept: `PlanFiringCrashTest` (kill at each step of `fire`: delivered once, actions never run twice); the plans
  tests; Bothy's gate.

**L19. Cards, pages and pictures on facts.** Bothy, `PL/cards`, `PL/pages`, `PL/images`.
- Depends: L17. Parallel with L18, L20.
- Accept: an offer and a pick survive a restart; a pruned page and a new picture are served from the log; Bothy's gate.

**L20. A2A and memory on facts.** Bothy, `PL/a2a`, `PL/memory`.
- Scope: 6.5 A2A and Memory; the import of `a2a-delegations.json`.
- Depends: L17. Parallel with L18, L19.
- Accept: `A2aReportCrashTest` (kill between the report and its delivery: delivered once, to the delegation's
  thread); Bothy's gate.

**L21. Voice on facts.** Bothy, `bothy-server` voice and companion, `bothy-voice`.
- Scope: 6.6 and 6.7: voice facts, recorded companion and 豆包 turns, `shown` for voice steering and `ask_xiaobo`,
  the setting, the rate limit.
- Depends: L16, L17.
- Accept: `VoiceFactLimitsTest` (100 posts give 30 facts and a `dropped`); a voice turn's bubble survives a reload;
  Bothy's gate.

**L22. Measure on the device.** SDK and Bothy on the tablet.
- Scope: M1 to M7 (section 11) after L15 and again after L21; apply the threshold rules that trigger; write the
  numbers into section 11.
- Depends: L15 (first pass), L21 (second). Parallel with L16 to L21.

**L23. Cut-over.** SDK and Bothy.
- Scope: remove every remaining old store and every write beside the log; enable `checkNoConversationFiles` in both
  repositories; `CHANGELOG.md` with every non-additive row of section 9; `agent-app-server/README.md`, the guides that
  mention the event log, history or storage, `AGENTS.md` (this design and the catalogue rule); `log-as-source.md`
  marked implemented; SDK version 0.6.0; Bothy's `agentRuntimeVersion` after publishing; final `apiDump` and
  `logCatalogDump`.
- Depends: all.
- Accept: both gates from a clean build (`--rerun-tasks`); on the tablet, an install of SDK 0.5.0 / Bothy 0.4.0 with
  real data upgrades with nothing a person sees lost; the release checks of `CONTRIBUTING.md`.

## 13. Open decisions for the owner

1. **Voice history by default.** "Keep voice history" keeps, per conversation, the speech handed to the assistant,
   the judge's decisions, the lines spoken and where they were cut (6.6); side talk is never kept. On or off by
   default? Recommendation: on; it is what lets the transcript show what was said aloud and makes voice problems
   traceable.
2. **Recorded exchanges in the model's history.** Companion, 豆包 and plan-line exchanges enter the model's history
   in order (5.4). Today only the latest reaches the next run, as per-run context. Recommendation: in the history; the
   model then knows every exchange, and the per-run block that changed every run goes.

Decided here, with the reason:

- Import once, not read old formats: one reader per format is the point of the change. The old files are kept aside
  (`legacy-0.5/`) for this release, unread, in case an import has to be repaired.
- A pending approval is cancelled by a process death, not asked again: the call's context may be gone.
- `ConversationItem.id` stays a `Long`: stable ids need no wire type change, and clients already key by it.
- No custom log backends (`SessionEventLog` goes): a host backend cannot give the flush and repair guarantees.
- Deliveries go to their own thread: a report recorded in another conversation's log would be wrong there.
- 0.5 debug trajectories are archived, not imported: they hold whole requests per round and are read only by the
  debug view.
- The person's memory book, plan watcher state and settings stay outside the log: they are not conversation.
- Usage days are computed in the device's current zone when read: no stored zone, and the ledger did the same at
  write time.
