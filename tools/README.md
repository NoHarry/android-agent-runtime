# Tools

Python 3 helpers for looking at what a runtime did, for trying `agent-a2a` against real servers, and for the app
server's API listing and plugin packages. Only `app-server-api` is needed to build (its check is part of `apiCheck`). `python3 -m unittest discover -s tests` (from this directory) runs their tests, as CI does.

| Tool | What it does |
|---|---|
| `session_cache.py` | Per run of one session, from its durable event log: model calls, input tokens, prompt-cache reads, `search_tools` and MCP tool calls, and how each run's first request differs from the previous run's last. `--pull` fetches the log from a debuggable app over adb (`--package`). Every MCP tool call counts as real work unless `--display-tool-prefix` names tools that only show things (an app's cards). |
| `session_latency.py` | Timings from a session's event log (time to first content, model calls, time spent in tools), without judging whether the task succeeded. |
| `bench_analyze.py` | Reads the probe evidence a runtime writes with `runtimeEvidence(…)` across bench runs, and checks it against a Case Pack's gates. |
| `a2a/` | Runners that exercise `agent-a2a` against real A2A servers (the official Python HelloWorld, a fault server); see [a2a/README.md](a2a/README.md). |
| `agent-plugin/` | Makes plugin packages for the app server: a publisher's key pair, and a package built and signed from a directory, with two examples; Python 3 and `openssl`. `agent-app-server`'s tests check what it builds. See [agent-plugin/README.md](agent-plugin/README.md). |
| `app-server-api/api_dump.py` | `agent-app-server`'s public API by level (`host`, `plugin`, `test`), checked against `agent-app-server/api/agent-app-server-levels.txt`; `apiCheck` runs it and `apiDump` writes the listing, so it is needed to build. `--counts` prints how many each level has. |

`fixtures/` holds synthetic inputs for these tests. `fixtures/kotlin-e2e` is what the runtime's probes write for one
scripted run: `agent-runtime`'s `ProbeE2eGoldenTest` checks the runtime still writes exactly that, and the Python tests
analyze it, with the Case Pack in `fixtures/bench-cases`.
