# Legacy dual-read fixtures (SPEC-RPB-008)

## Provenance

- **Shape** derived from phase3 `vfs-ops.jsonl` consumption in
  `tools/analyze_phase3_bench.py` (`summarize_vfs`) and runtime
  `StoredVfsOperation` / `VfsOperationTimings` fields used by session store tests
  (`AndroidSqliteSessionStoreTest.sampleStoredVfsOperation`).
- **Not** copied from live device runs or sample-host Case Packs.
- **Synthetic**: operation ids, virtual paths under `/workspace/...` only.
- **Redaction**: no credentials, absolute host paths, device ids, or user home dirs.

## Layout

| Path | Role |
|---|---|
| `legacy-run/` | Historical evidence only: model-calls, session-tool-events, vfs-ops (no probes) |
| `new-run/` | Dual-write style: probes + completeness + run-meta **and** same vfs-ops + tool facts |

## Version window

- `vfs-ops` soft schemaVersion `1` or omitted (legacy phase3 files omit version).
- Unknown `schemaVersion` → analyzer INCOMPLETE / parse error (fail-closed).
