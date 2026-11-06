# Legacy dual-read fixtures

## Provenance

- **Shape** derived from the older bench runs' `vfs-ops.jsonl` and the runtime's
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

- `vfs-ops` soft schemaVersion `1` or omitted (older bench files omit it).
- Unknown `schemaVersion` → analyzer INCOMPLETE / parse error (fail-closed).
