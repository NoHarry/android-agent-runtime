package cc.noharry.agent.runtime.android

internal const val SQLITE_SESSION_SCHEMA_VERSION = 5
internal const val SQLITE_SESSION_MAX_QUERY_LIMIT = 500

internal const val TABLE_SESSIONS = "sessions"
internal const val TABLE_RUNS = "runs"
internal const val TABLE_MESSAGES = "messages"
internal const val TABLE_TOOL_EVENTS = "tool_events"
internal const val TABLE_USAGE = "usage"
internal const val TABLE_VFS_OPS = "vfs_ops"
internal const val TABLE_MODEL_CALLS = "model_calls"
internal const val TABLE_MEMORY_INDEX = "memory_index"

internal const val COL_ID = "id"
internal const val COL_SESSION_ID = "session_id"
internal const val COL_TITLE = "title"
internal const val COL_MODEL_ID = "model_id"
internal const val COL_CREATED_AT = "created_at"
internal const val COL_UPDATED_AT = "updated_at"
internal const val COL_RUN_ID = "run_id"
internal const val COL_REQUEST_ID = "request_id"
internal const val COL_STATUS = "status"
internal const val COL_SCENE = "scene"
internal const val COL_PROVIDER_ID = "provider_id"
internal const val COL_STARTED_AT = "started_at"
internal const val COL_FINISHED_AT = "finished_at"
internal const val COL_MODEL_REF = "model_ref"
internal const val COL_VARIANT = "variant"
internal const val COL_RESPONSE = "response"
internal const val COL_ERROR_CODE = "error_code"
internal const val COL_MESSAGE_ID = "message_id"
internal const val COL_SEQUENCE = "sequence"
internal const val COL_ROLE = "role"
internal const val COL_CONTENT = "content"
internal const val COL_TOOL_CALL_ID = "tool_call_id"
internal const val COL_TOOL_NAME = "tool_name"
internal const val COL_TOOL_ARGUMENTS_JSON = "tool_arguments_json"
internal const val COL_CALL_ID = "call_id"
internal const val COL_SOURCE = "source"
internal const val COL_ARGUMENTS_JSON = "arguments_json"
internal const val COL_INPUT_HASH = "input_hash"
internal const val COL_RESULT_JSON = "result_json"
internal const val COL_RESULT_HASH = "result_hash"
internal const val COL_OK = "ok"
internal const val COL_CODE = "code"
internal const val COL_INPUT_TOKENS = "input_tokens"
internal const val COL_OUTPUT_TOKENS = "output_tokens"
internal const val COL_REASONING_TOKENS = "reasoning_tokens"
internal const val COL_CACHE_READ_TOKENS = "cache_read_tokens"
internal const val COL_CACHE_WRITE_TOKENS = "cache_write_tokens"
internal const val COL_OP = "op"
internal const val COL_PATH = "path"
internal const val COL_MOUNT_ID = "mount_id"
internal const val COL_CONTENT_HASH = "content_hash"
internal const val COL_RESOLVE_MS = "resolve_ms"
internal const val COL_MATERIALIZE_MS = "materialize_ms"
internal const val COL_SCAN_MS = "scan_ms"
internal const val COL_SERIALIZE_MS = "serialize_ms"
internal const val COL_PREPARE_MS = "prepare_ms"
internal const val COL_BATCH_WAIT_MS = "batch_wait_ms"
internal const val COL_EXECUTE_MS = "execute_ms"
internal const val COL_BATCH_RESULT_WAIT_MS = "batch_result_wait_ms"
internal const val COL_SUMMARY_MS = "summary_ms"
internal const val COL_PUBLIC_FINISH_MS = "public_finish_ms"
internal const val COL_SCHEMA_CACHE_HIT = "schema_cache_hit"
internal const val COL_FIRST_EVENT_AT = "first_event_at"
internal const val COL_FIRST_TOOL_CALL_AT = "first_tool_call_at"
internal const val COL_MODEL_CALL_SOURCE = "call_source"
internal const val COL_PROVIDER_MODEL = "provider_model"
internal const val COL_SYSTEM_FINGERPRINT = "system_fingerprint"
internal const val COL_DURATION_MS = "duration_ms"
internal const val COL_MESSAGE_COUNT = "message_count"
internal const val COL_TOOL_COUNT = "tool_count"
internal const val COL_EVENT_COUNT = "event_count"
internal const val COL_THINKING_EVENT_COUNT = "thinking_event_count"
internal const val COL_TEXT_EVENT_COUNT = "text_event_count"
internal const val COL_TOOL_CALL_EVENT_COUNT = "tool_call_event_count"
internal const val COL_USAGE_EVENT_COUNT = "usage_event_count"
internal const val COL_MEMORY_ID = "memory_id"
internal const val COL_DOMAIN = "domain"
internal const val COL_ACTION = "action"
internal const val COL_SUMMARY = "summary"
internal const val COL_EVIDENCE_RUN_ID = "evidence_run_id"

internal val MESSAGE_COLUMNS = arrayOf(
    COL_SESSION_ID,
    COL_RUN_ID,
    COL_ROLE,
    COL_CONTENT,
    COL_TOOL_CALL_ID,
    COL_TOOL_NAME,
    COL_TOOL_ARGUMENTS_JSON,
    COL_MODEL_REF,
    COL_CREATED_AT,
)

internal val TOOL_COLUMNS = arrayOf(
    COL_CALL_ID,
    COL_RUN_ID,
    COL_SESSION_ID,
    COL_TOOL_NAME,
    COL_SOURCE,
    COL_ARGUMENTS_JSON,
    COL_INPUT_HASH,
    COL_STATUS,
    COL_STARTED_AT,
    COL_FINISHED_AT,
    COL_RESULT_JSON,
    COL_RESULT_HASH,
    COL_OK,
    COL_CODE,
)

internal val SESSIONS_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
      $COL_SESSION_ID TEXT PRIMARY KEY,
      $COL_TITLE TEXT NOT NULL,
      $COL_MODEL_ID TEXT NOT NULL DEFAULT '',
      $COL_CREATED_AT INTEGER NOT NULL,
      $COL_UPDATED_AT INTEGER NOT NULL
    )
""".trimIndent()

internal val RUNS_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_RUNS (
      $COL_RUN_ID TEXT PRIMARY KEY,
      $COL_REQUEST_ID TEXT NOT NULL,
      $COL_SESSION_ID TEXT NOT NULL,
      $COL_STATUS TEXT NOT NULL,
      $COL_SCENE TEXT NOT NULL DEFAULT '',
      $COL_PROVIDER_ID TEXT NOT NULL DEFAULT '',
      $COL_MODEL_ID TEXT NOT NULL DEFAULT '',
      $COL_STARTED_AT INTEGER NOT NULL,
      $COL_UPDATED_AT INTEGER NOT NULL,
      $COL_FINISHED_AT INTEGER,
      $COL_MODEL_REF TEXT NOT NULL DEFAULT '',
      $COL_VARIANT TEXT NOT NULL DEFAULT '',
      $COL_RESPONSE TEXT NOT NULL DEFAULT '',
      $COL_ERROR_CODE TEXT NOT NULL DEFAULT ''
    )
""".trimIndent()

internal val MESSAGES_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_MESSAGES (
      $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
      $COL_MESSAGE_ID TEXT NOT NULL UNIQUE,
      $COL_SESSION_ID TEXT NOT NULL,
      $COL_RUN_ID TEXT NOT NULL,
      $COL_SEQUENCE INTEGER NOT NULL,
      $COL_ROLE TEXT NOT NULL,
      $COL_CONTENT TEXT NOT NULL,
      $COL_TOOL_CALL_ID TEXT NOT NULL DEFAULT '',
      $COL_TOOL_NAME TEXT NOT NULL DEFAULT '',
      $COL_TOOL_ARGUMENTS_JSON TEXT NOT NULL DEFAULT '',
      $COL_MODEL_REF TEXT NOT NULL DEFAULT '',
      $COL_CREATED_AT INTEGER NOT NULL
    )
""".trimIndent()

internal val TOOL_EVENTS_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_TOOL_EVENTS (
      $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
      $COL_CALL_ID TEXT NOT NULL UNIQUE,
      $COL_RUN_ID TEXT NOT NULL,
      $COL_SESSION_ID TEXT NOT NULL,
      $COL_TOOL_NAME TEXT NOT NULL,
      $COL_SOURCE TEXT NOT NULL,
      $COL_ARGUMENTS_JSON TEXT NOT NULL,
      $COL_INPUT_HASH TEXT NOT NULL,
      $COL_STATUS TEXT NOT NULL,
      $COL_STARTED_AT INTEGER NOT NULL,
      $COL_FINISHED_AT INTEGER,
      $COL_RESULT_JSON TEXT NOT NULL DEFAULT '',
      $COL_RESULT_HASH TEXT NOT NULL DEFAULT '',
      $COL_OK INTEGER,
      $COL_CODE TEXT NOT NULL DEFAULT ''
    )
""".trimIndent()

internal val USAGE_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_USAGE (
      $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
      $COL_RUN_ID TEXT NOT NULL,
      $COL_SESSION_ID TEXT NOT NULL,
      $COL_INPUT_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_OUTPUT_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_REASONING_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_CACHE_READ_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_CACHE_WRITE_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_CREATED_AT INTEGER NOT NULL
    )
""".trimIndent()

internal val VFS_OPS_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_VFS_OPS (
      $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
      $COL_RUN_ID TEXT NOT NULL,
      $COL_SESSION_ID TEXT NOT NULL,
      $COL_OP TEXT NOT NULL,
      $COL_PATH TEXT NOT NULL,
      $COL_MOUNT_ID TEXT NOT NULL DEFAULT '',
      $COL_STATUS TEXT NOT NULL,
      $COL_CONTENT_HASH TEXT NOT NULL DEFAULT '',
      $COL_RESOLVE_MS INTEGER NOT NULL DEFAULT 0,
      $COL_MATERIALIZE_MS INTEGER NOT NULL DEFAULT 0,
      $COL_SCAN_MS INTEGER NOT NULL DEFAULT 0,
      $COL_SERIALIZE_MS INTEGER NOT NULL DEFAULT 0,
      $COL_PREPARE_MS INTEGER NOT NULL DEFAULT 0,
      $COL_BATCH_WAIT_MS INTEGER NOT NULL DEFAULT 0,
      $COL_EXECUTE_MS INTEGER NOT NULL DEFAULT 0,
      $COL_BATCH_RESULT_WAIT_MS INTEGER NOT NULL DEFAULT 0,
      $COL_SUMMARY_MS INTEGER NOT NULL DEFAULT 0,
      $COL_PUBLIC_FINISH_MS INTEGER NOT NULL DEFAULT 0,
      $COL_SCHEMA_CACHE_HIT INTEGER,
      $COL_CREATED_AT INTEGER NOT NULL
    )
""".trimIndent()

internal val MODEL_CALLS_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_MODEL_CALLS (
      $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
      $COL_RUN_ID TEXT NOT NULL,
      $COL_SESSION_ID TEXT NOT NULL,
      $COL_CALL_ID TEXT NOT NULL UNIQUE,
      $COL_STATUS TEXT NOT NULL,
      $COL_MODEL_CALL_SOURCE TEXT NOT NULL DEFAULT 'round',
      $COL_MODEL_REF TEXT NOT NULL DEFAULT '',
      $COL_PROVIDER_MODEL TEXT NOT NULL DEFAULT '',
      $COL_SYSTEM_FINGERPRINT TEXT NOT NULL DEFAULT '',
      $COL_STARTED_AT INTEGER NOT NULL,
      $COL_FIRST_EVENT_AT INTEGER,
      $COL_FIRST_TOOL_CALL_AT INTEGER,
      $COL_FINISHED_AT INTEGER NOT NULL,
      $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
      $COL_MESSAGE_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_TOOL_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_EVENT_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_THINKING_EVENT_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_TEXT_EVENT_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_TOOL_CALL_EVENT_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_USAGE_EVENT_COUNT INTEGER NOT NULL DEFAULT 0,
      $COL_INPUT_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_OUTPUT_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_REASONING_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_CACHE_READ_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_CACHE_WRITE_TOKENS INTEGER NOT NULL DEFAULT 0,
      $COL_ERROR_CODE TEXT NOT NULL DEFAULT ''
    )
""".trimIndent()

internal val MEMORY_INDEX_SQL = """
    CREATE TABLE IF NOT EXISTS $TABLE_MEMORY_INDEX (
      $COL_MEMORY_ID TEXT PRIMARY KEY,
      $COL_DOMAIN TEXT NOT NULL DEFAULT '',
      $COL_ACTION TEXT NOT NULL DEFAULT '',
      $COL_SUMMARY TEXT NOT NULL,
      $COL_EVIDENCE_RUN_ID TEXT NOT NULL DEFAULT '',
      $COL_UPDATED_AT INTEGER NOT NULL
    )
""".trimIndent()

internal val INDICES_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS idx_runs_session_updated ON $TABLE_RUNS($COL_SESSION_ID, $COL_UPDATED_AT)",
    "CREATE INDEX IF NOT EXISTS idx_messages_session_id ON $TABLE_MESSAGES($COL_SESSION_ID, $COL_ID)",
    "CREATE INDEX IF NOT EXISTS idx_tool_events_run_id ON $TABLE_TOOL_EVENTS($COL_RUN_ID, $COL_STARTED_AT)",
    "CREATE INDEX IF NOT EXISTS idx_usage_session_id ON $TABLE_USAGE($COL_SESSION_ID)",
    "CREATE INDEX IF NOT EXISTS idx_vfs_ops_run_id ON $TABLE_VFS_OPS($COL_RUN_ID, $COL_ID)",
    "CREATE INDEX IF NOT EXISTS idx_model_calls_run_id ON $TABLE_MODEL_CALLS($COL_RUN_ID, $COL_ID)",
    "CREATE INDEX IF NOT EXISTS idx_memory_domain_action ON $TABLE_MEMORY_INDEX($COL_DOMAIN, $COL_ACTION)",
)
