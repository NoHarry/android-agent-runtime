# The session log as the source

Status: proposal. Nothing here is built except where a step says so.

The rule this document works towards: **everything the system knows about a conversation is derived from one
append-only log of that conversation.** The next request to the model, the transcript a front end shows, the list of
conversations, usage, approvals, what was spoken: each is a function of the log. Any other file is a cache or an index
that can be deleted and rebuilt. Settings, credentials, model files, installed plugins and the person's memory are not
conversation information and stay where they are.

The reference is dsh (`deepseek-harness`), which is built this way. The first section says what it does; the second
where this runtime departs from it; the rest is the target and the order of work.

## What dsh does

- **One log per session**, append-only, contiguous sequence numbers, one JSON row per event. A write is fsynced and
  rolled back if it fails. The log is flushed before every model request and before a tool body runs; when the flush
  fails, that work does not start.
- **A request is rebuilt from the log**: the logged request header (configuration and tool schemas, with the reason
  it was written: initial, resume, change), plus the messages derived from the events by one pure function per event,
  plus the tool additions and removals. Tool calls are kept as the raw string the model wrote; provider-specific state
  (reasoning signatures) sits beside the message in a replay envelope. A test asserts that every request sent
  rebuilds byte-equal from the log alone.
- **Everything else is a projection**: compaction (a logged replace of a range, hidden from the model, still shown to
  the person), todo, plan mode, approval policy and decisions, tool activation, token usage, titles, the sub-agent
  catalog. Each is a fold over events. In memory the folds are incremental; none is a second source.
- **What breaks the prompt cache is in the log**: a change of tools or model writes a record saying so. Nothing else
  changes the prefix, because the log only grows.
- **Outside the log is disposable**: a projection cache is trusted only when its version, identity and sequence
  watermark match the log, and the log is flushed before a cache row is written; a search index is rebuilt at will.
  Attachments are stored by content hash before the event that names them.
- **Recovery reads the log**: on reopen, calls with no result get one (`not started`, `outcome unknown`), the open turn
  is closed, and those closers are appended.
- **Sub-agents are sessions**: each child has its own log, linked from the parent.
- **Unknown events are refused** unless marked ignorable; each format version has its own upgrade step and old
  generation files are never rewritten.

## Where this runtime stands

There is a session event log (`agent-runtime`, `runtime/eventlog/`), and it records most of a run: the request, the
model's chunks and calls, tool starts and results, usage, the run's status. But nothing that decides behaviour reads
it. It is written beside the stores that do, by a recorder that swallows its own failures.

| What | Source today | Relation to the log |
|---|---|---|
| The next request's history | `*.history.json` (`SessionHistoryStore`), in-run `loop-history.json` | A second store. It holds compacted and rewritten runs no event describes, and is written before the run's last events are flushed |
| Transcript, conversation list, titles, previous exchange in the prompt | `sessions/{id}/turns.jsonl`, `meta.json` (app server) | A second store, written after the run. Holds things the log lacks: the text the person saw, the completion outcome, turns with no run (companion, spoken lines) |
| Items a front end shows | `TranscriptStore` in memory, restored from `turns.jsonl` with other ids | Not derivable: thinking, steering, approvals are lost on restore |
| Completion outcome, obligation ledger, run evidence | reliability files, `debug-run-evidence` | Only outside the log |
| Approvals: the decision, its scope, remembered domains, the mode in force | memory, `*.thread.json`, preferences | Only the outcome reaches a tool result |
| The model's note on a call (`say`) | process memory | Only outside the log (step 1 changes this) |
| Tool activation | memory, partly re-derived from history | Only outside the log |
| Usage ledger | `model-usage/usage-ledger.json` | Duplicated for runs; side calls (voice judge, companion) exist only there |
| Sub-agents: board, pending reports | memory, `subagents.jsonl` | Only outside the log; an undelivered report is lost on restart |
| Plugin facts: plans firing, reminders, cards and picks, A2A delegations, generated images | each plugin's own file or memory | Outside the log; several are written before the tool result, with no way to tell after a crash which side is ahead |
| Voice: what was heard, judged, spoken, cut off | memory | Not recorded anywhere |

The log itself falls short of a source in five ways:

1. **It can lose events silently.** Every append is wrapped to swallow failure. An event containing a string that
   looks like a credential, or a JSON key such as `password`, is rejected whole; a request that carries such a tool
   schema would drop every later request event of the session.
2. **It is not what the provider got.** The request is logged before the host's model client changes it; the call is
   logged before ids are made unique and before `call_tool` is unwrapped; captured messages drop reasoning content and
   re-serialise arguments.
3. **There is no order across a session.** Sequence numbers are per run; runs are ordered by wall clock.
4. **Events the public stream has are never logged**: confirmation required, blocked, retry, compaction applied,
   budget warning, search and network activity, the failure message.
5. **Writes trail the work.** A writer thread batches every 20 ms; the flush is at the run's end, not before a request
   or a tool.

The crash cases follow from the duplication: a run in the history that the log shows unfinished (or the reverse), a
run in the log with no turn row, a turn row with no log, a deleted conversation whose log remains.

## Target

**One log per thread**, in session order, that is complete and exact:

- *Complete*: an append that fails stops the work that depends on it. Secrets are redacted in place, not a reason to
  drop the event. Every public event has a logged cause. Facts a host or plugin adds (a plan fired, a line was spoken,
  a card was picked, a side model call and its usage) are appended through one API as namespaced events.
- *Exact*: the model's output is logged as it arrived (raw arguments, reasoning, provider replay state) and the
  request as it was sent (after the host's changes), so the next request is a function of the log.
- *Ordered*: one sequence per thread across runs; a run is a span of it.
- *Durable at the right moments*: flushed before a model request and before a tool that acts.

**Readers are projections.** One reducer interface (initial state, apply event, version); the runtime and the app
server register theirs:

| Projection | Replaces |
|---|---|
| Model history (messages, compaction as a logged replace, closers for unanswered calls) | `history.json`, `loop-history.json` |
| Tool activation | `ToolActivationStore` |
| Thread state: status, pending question, approvals and remembered domains | `*.thread.json`, pending-confirmation memory |
| Items and turns, with ids derived from the event's place in the log | turn snapshots, `turns.jsonl`, `TranscriptStore` restore |
| Conversation list and titles | `meta.json` |
| Usage | the usage ledger's run rows |
| Run outcome and evidence | reliability result, `debug-run-evidence` |
| Sub-agent board and deliveries | `AppSubagents` memory, `subagents.jsonl` |

A projection may keep a snapshot, stamped with its version and the log sequence it reflects; a snapshot that does not
match is discarded and rebuilt. Deleting every file but the logs loses nothing but time.

**Enforced by tests**, as in dsh: every request rebuilds byte-equal from the log prefix; a projection folded from
scratch equals the live one; a run killed at each write point reopens to a consistent thread.

## Order of work

Each step leaves the system working and is released on its own. Steps 1–3 are inside the runtime and the app server;
4 changes the protocol; 5 reaches into plugins and Bothy.

1. **The model's call, as written.** The note a host adds to tool calls stays in the logged call and in the history;
   it is taken off where the tool runs. No side store. *(In progress.)*
2. **Make the log trustworthy.** Appends that fail are errors, not counts. Redact instead of reject. A thread-wide
   sequence. Log the request as sent and the output as received (raw arguments, reasoning, replay state). Log the
   events that have no record today: confirmation asked and decided (with scope and the mode in force), blocked, retry,
   compaction (as a replace with the summary), the run's outcome and failure message. Flush before a request and
   before an acting tool. Additive to the event schema; no reader changes yet.
3. **History from the log.** The next request is built by the history projection; `history.json` and
   `loop-history.json` become snapshots of it. The byte-equal test lands here. Resume and recovery read the log.
   Existing threads keep their `history.json` as a seed: a thread whose log does not cover its history starts its log
   with one imported-history event.
4. **The app server's view from the log.** Items, turns, the list and titles are projections; `turns.jsonl` goes.
   Item ids become stable (a protocol change: `ConversationItem.id` is a number counted in memory today). Turns with no
   run (companion, spoken lines, a startup failure) become logged spans of their own. Old conversations are imported
   once from `turns.jsonl`, marked as imported, without thinking or approvals, as they are shown today.
5. **Host and plugin facts.** One `PluginContext` call appends a namespaced fact to the thread's log, and one host
   stream takes what belongs to no thread (a plan firing with the app closed). Plans, cards, A2A delegations, voice and
   usage of side calls move to it; their files become caches. Generated images go to the attachment store.
6. **Sub-agents as threads.** A child run is its own log linked from the parent's; the board and pending reports are
   projections, so a report survives a restart.

## Costs and open questions

- **Public surface.** New `SessionEventType` constants and payload fields are additive but break exhaustive `when`
  over the (experimental) enum. Step 4 changes the protocol's item ids. Step 5 adds plugin API.
- **Size.** Logging requests as sent repeats the history in every request event, as today. dsh avoids it by logging
  the header once and deriving the messages; step 3 makes the same possible here (log the header and what was added,
  not the whole request). Until then the log grows as it does now.
- **Privacy.** A complete log holds everything said and heard. Redaction must be exact enough to keep events, and the
  log stays in no-backup storage. Voice facts need limits: what was judged and spoken, not audio and not every partial.
- **Latency.** A flush before each request and acting tool is an fsync on the turn's path (dsh pays it). Measure on
  the tablet before fixing the policy; a group commit shared by concurrent threads may be needed.
- **Two releases or one.** The Maven quota favours few releases. Steps 2 and 3 can ship together; 4 needs Bothy's
  front ends changed with it.
- **How much of step 5 is worth it.** Plans and A2A delegations have real crash windows today; voice history is new
  information nobody reads yet.
