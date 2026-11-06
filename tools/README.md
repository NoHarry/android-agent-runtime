# Tools

Python 3 helpers for looking at what a runtime did, and for trying `agent-a2a` against real servers. None of them is
needed to build or use the library. `python3 -m unittest discover -s tests` (from this directory) runs their tests, as CI does.

| Tool | What it does |
|---|---|
| `session_cache.py` | Per run of one session, from its durable event log: model calls, input tokens, prompt-cache reads, `search_tools` and MCP tool calls, and how each run's first request differs from the previous run's last. `--pull` fetches the log from a debuggable app over adb (`--package`). Every MCP tool call counts as real work unless `--display-tool-prefix` names tools that only show things (an app's cards). |
| `session_latency.py` | Timings from a session's event log (time to first content, model calls, time spent in tools), without judging whether the task succeeded. |
| `bench_analyze.py` | Reads the probe evidence a runtime writes with `runtimeEvidence(…)` across bench runs, and checks it against a Case Pack's gates. |
| `a2a/` | Runners that exercise `agent-a2a` against real A2A servers (the official Python HelloWorld, a fault server); see [a2a/README.md](a2a/README.md). |

`fixtures/` holds synthetic inputs for these tests. `fixtures/kotlin-e2e` is what the runtime's probes write for one
scripted run: `agent-runtime`'s `ProbeE2eGoldenTest` checks the runtime still writes exactly that, and the Python tests
analyze it, with the Case Pack in `fixtures/bench-cases`.
