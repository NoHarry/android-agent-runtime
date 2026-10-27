# A2A tools

## Reference integration runners

These runners exercise the real `:agent-a2a` JVM test artifact
against independently running HTTP servers. They inject the integration test
source with a Gradle init script, so no Android Gradle module is modified.

## Official Python HelloWorld

```bash
python tools/a2a/run_helloworld_smoke.py
```

The runner clones the pinned upstream `a2aproject/a2a-samples` commit into a
temporary directory, installs the sample's locked Python requirements in a
temporary virtual environment, starts its official Python HelloWorld server,
then verifies Agent Card discovery plus synchronous and streaming messaging.

## Deterministic fault server

```bash
python tools/a2a/run_fault_smoke.py
```

The fault runner starts the project-owned deterministic server and proves the
cancel-race outcome: a blocked message call is cancelled locally, the server
then attempts a late business response, and the cancelled client accepts no
result afterward. Server-side disconnect/write observations remain diagnostic.

Both commands print exactly one final JSON result object. A failed assertion,
server startup failure, or Gradle failure produces `"status":"failed"` and a
non-zero process exit code. Use `--result-file PATH` to persist the same JSON
for CI artifact collection.

Run both required integrations with:

```bash
python tools/a2a/run_reference_integrations.py
```

