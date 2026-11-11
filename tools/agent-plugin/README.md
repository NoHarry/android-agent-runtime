# agent-plugin

Makes plugin packages (`.agentplugin`) for the app server: a publisher's key pair, and a package built and signed from
a directory, in the format `agent-app-server` checks (`packages/`). Python 3 and the `openssl` command, nothing else.

```bash
python3 agentplugin.py keygen publisher.key                 # a new ECDSA P-256 key, readable by you only
python3 agentplugin.py pubkey publisher.key                 # its public key (hex of its DER encoding) and fingerprint
python3 agentplugin.py build example --key publisher.key    # com.example.weather-1.0.0.agentplugin, signed
python3 agentplugin.py build example-scripts --unsigned     # com.example.scripted-1.0.0.agentplugin: tools as scripts
python3 agentplugin.py build example --unsigned             # digests only, for a host that allows unsigned packages
python3 agentplugin.py verify com.example.weather-1.0.0.agentplugin --key <public key hex>
```

The public key is what a host lists in `AppServerConfig.trustedPublishers`; a package from any other key installs only
after a second warning. The first
install of an id pins its key: every later version must be signed with the same key file, so keep it, and keep it out
of any repository (this directory ignores `*.key` and built packages).

## A package directory

`example/` is one: `plugin.json` (format 1: a reverse-domain `id`, a semantic `version`, `name`, `publisher`, the
`permissions` it asks for — `network` hosts and `secrets` the person types at install — its `mcpServers`, `skills` and
`rules`, and optionally an `icon` and a `minServer`), one `skills/<name>/SKILL.md` per skill, and `rules.md` (at most 20
lines and 2,000 characters). Every member but `format`, `id`, `name`, `version` and `publisher` may be left out:
`permissions` too, or `{}`, for a package that reaches nothing (the timer sample's, `samples/agent-app-sample-timer/package/`,
is a `plugin.json` and one script that only computes). A package holds only markdown, json, png and its tools' scripts, at most 200 files and
5 MB; hidden files are left out. An MCP server's host must be among the `network` hosts and its address HTTPS (HTTP only
on `127.0.0.1`/`localhost`); a secret goes into a header value or a whole query value as `${name}`. An MCP server's
tools that act ask the person every time; for a publisher the host does not trust, every one of its tools does,
whatever the server's `readOnlyHint` says. A skill may ask to be preloaded (`{ "path": "skills/x", "preload": true }`);
only a trusted publisher's is. `code/`, `code` and `nativeCode` are
reserved for compiled code: this server refuses a package that has them.

## Names

The model, and the person's switches, know what a package brings by names the server makes from its id:

- its tools written as scripts: `mcp__<last segment of the id>__<tool>` (`com.example.pantry.recipes`'s `cups_to_ml`
  is `mcp__recipes__cups_to_ml`), the segment written as a tool server's name is (lower-case letters, digits and
  single `_`, 32 characters at most);
- its MCP servers' tools: `mcp__<last segment>_<server id>__<tool>` (`mcp__weather_weather__<tool>` for this
  directory's `example/`);
- its skills: `<id>:<skill>`, the skill being the directory under `skills/` (`com.example.weather:forecast`); the
  `name` in the SKILL.md header is replaced with it.

No two installed packages share one of those names, and none takes a name the host's other tool servers go by when
it is installed (the app's own tools, the MCP servers the person configured, the app's plugins): `plugins/install`
refuses such a package with `TOOL_SERVER_TAKEN`, naming the name (and the package that has it). Give the last segment
something of your own: `recipes` is likelier taken than `pantryrecipes`. A host's test addresses a script tool by
that segment, `ScriptedModel`'s `call("recipes", "cups_to_ml", …)`, and the conversation shows its calls as
`ToolCallItem`s by the full name, beside the `search_tools` that found it and the `finish` that answered.

## Tools written as scripts

`example-scripts/` has three. They run in the host app's JavaScript sandbox, `agent-exec-js`, which an app adds when
it takes such packages: one without it refuses them (`SCRIPTS_UNAVAILABLE`). A tool in `tools` has a `name` (lower-case letters, digits and `_`, 24 at most), a
`description` for the model, an `inputSchema` (an object schema in the part of JSON Schema the server checks: `type`,
`properties`, `required`, `additionalProperties`, `items`, `enum`, `const`, `minimum`/`maximum` and their exclusive
forms, `minLength`/`maxLength`, `minItems`/`maxItems`, and the annotations `description`, `title`, `default`,
`examples`, `format`; `pattern`, `$ref`, `oneOf` and the rest are refused), its `script` (`scripts/<name>.js`, 128 KiB at
most; every file under `scripts/` must be a tool's), `readOnly` and, if it needs other than 10 s, `timeoutSeconds`. At
most 20 tools. `permissions.hostTools` names the host's tools the scripts call, as `mcp__<server>__<tool>` or, when only
one server has it, by the tool's own name.

A script defines `function run(input)`, where `input` is the call's arguments already checked against `inputSchema`,
and returns a value JSON can write (64 KiB at most); a thrown error is the tool's failure, its message what the model
reads. It runs in strict mode in QuickJS with 16 MiB and 20 host calls; the names `host` and those starting with
`__pkg` are the host's. A call has one time limit, host calls included: the tool's `timeoutSeconds` (an integer from 1
to 120; 10 when left out; `current_weather` asks for 20), held to the host's most (`AppServerConfig.maxScriptToolSeconds`,
30 unless the host sets another). A tool that asks for more than the host's most is not refused: it runs with the
host's. A script that runs over is stopped, and the model reads `the script ran over its time (N s)` with the seconds in
effect. `plugins/install` and `plugins/list` give both numbers for each tool (`timeoutSeconds` as asked,
`effectiveTimeoutSeconds` as it runs), and an update whose tool asks for more time than before lists that tool among
what it changes. Memory, host calls and sizes are fixed. Outside pure computation it has three functions, each
answering at once (no promises):

- `host.http({method, url, headers, body})` → `{status, headers, body}` (headers by lower-case name, body as text). Only
  to hosts in `network`, over HTTPS (HTTP only to this device when named); no redirect is followed; 64 KiB sent, 256 KiB
  read, within what is left of the call's time (no limit of its own). `${name}` is filled in by the host in a header
  value or as a whole query value; the script only ever has the placeholder, and an answer that repeats a secret has the
  placeholder put back. A name on the internet that resolves into the device or the local network is refused.
- `host.tool(name, arguments)` → the tool's result. Only a tool named in `hostTools` that says it only reads (an MCP
  server's `readOnlyHint`), among the run's configured MCP servers and the plugins' tool servers the person switched
  on; it is called without asking the person. The host's own tools (`AppServerConfig.tools`), other packages' and
  plugins' MCP servers, and the runtime's own tools (`web_search`, `web_fetch`, files) are not reachable; use
  `host.http`.
- `host.log(message)` → a line in the app's log (`adb logcat -s AppServerPackages`), marked with the package and tool,
  written when the call ends (20 lines of 500 characters).

A refused or failed host call throws an error the script can catch. The script runs again from the start after each
host call, with the earlier answers given back in order, so it must make the same calls each time: no time or random
numbers in a call's arguments (a script that differs stops with an error, no request repeated). A tool asks the person
before each run unless it says `readOnly` and its publisher is one the host trusts.

`build` writes `signature.json`: the SHA-256 of every other file and, when signed, the public key and a signature over
`agentplugin-signature-1\n` followed by `<sha256>  <path>\n` for each file in path order.

## Installing one on a device

From any client of the protocol (a host's screen, or its own debug door), with the package's bytes in base64:

```json
{"method": "plugins/install", "params": {"data": "<base64 of com.example.weather-1.0.0.agentplugin>"}}
{"method": "plugins/confirm", "params": {"installId": "<from install>", "secrets": {"apiKey": "<key>"}, "acknowledgeUntrusted": true}}
{"method": "plugins/list", "params": {}}
{"method": "plugins/set", "params": {"id": "com.example.weather", "enabled": false}}
{"method": "plugins/remove", "params": {"id": "com.example.weather"}}
```

`plugins/install` also takes `{"url": "https://…"}` or `{"file": "content://…"}` (or a `file://` path in the app's
external files, where `adb push` can put one). `python3 agentplugin.py build example-scripts --unsigned --out
scripted.agentplugin` builds the scripted example for a host that takes unsigned packages; once installed, a turn that
asks for one of its tools runs it (an unknown publisher's tools ask first), and its log lines are in
`adb logcat -s AppServerPackages`. `notes_digest` calls a host tool, `mcp__notes__list_notes`: a read-only tool of an MCP
server the host has configured as `notes`. Without one, the call fails with the error that the run has no such tool.
