# Debug Console Runtime Workspace

This workspace is provided by the app server through `workspace.json`.

## Filesystem
- `/AGENTS.md` is a real host-provided workspace file.
- `/.agents/skills/` is mounted from the host app's skill assets.
- `/memory/host/` is mounted from a host-managed in-memory source.
- `/context/debug-session-index/` is mounted from a host database-style source.
- `/sessions/` holds the past sessions' records: `index.json` lists them; read one only when the user refers to it.
- `/memory/` is writable runtime memory when the host maps it as read-write.
- `/scratch/` is run-scoped scratch space managed by the runtime tools.
- `/mcp/` and `/context/` may be backed by runtime-provided virtual sources or host mounts.

## Rules
- Read the smallest file or directory needed for the current task.
- Use backend tools when they are available in the current tool list.
- Call `finish(response)` exactly once when the useful result is ready.
