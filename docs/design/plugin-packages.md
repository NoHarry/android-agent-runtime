# Plugin packages a person downloads

Status: approved 2026-11-09 (section 7); being built in the Bothy repository, branch `codex/api-redesign`. It follows `app-server-split.md` section 6: the plugin
kernel exists; this is step A (declarative packages), with the places step B (sandboxed scripts) will use left open.

A package is a plugin that carries no native code: skills, rules, addresses of MCP servers, templates. A person
installs one inside the app from an address or a file; it starts at once and can be turned off or removed like any
plugin. The core sees no difference from a plugin built into the host: one generic plugin (`PackagePlugin`) reads an
installed package and says its contents to the `PluginContext`.

Three things are hard to change once people write packages, so they are decided here: the format, how a package is
trusted, and what it may do.

## 1. The format

A package is a zip file named `<id>-<version>.agentplugin`:

```
plugin.json            the manifest (below)
signature.json         digests of every other file, and the publisher's signature over them (section 2)
skills/<name>/SKILL.md one directory per skill, the same SKILL.md the app server reads today
rules.md               optional: lines added to the system rules while the plugin runs
templates/…            optional: card and page templates (html, json)
icon.png               optional
scripts/…              reserved for step B
code/…                 reserved for step C (section 6)
```

`plugin.json`:

```json
{
  "format": 1,
  "id": "com.example.trains",
  "name": "Trains",
  "version": "1.2.0",
  "publisher": "Example Co.",
  "description": "Search trains and seats.",
  "minServer": "0.5.0",
  "permissions": {
    "network": ["api.example.com"],
    "secrets": [{ "name": "apiKey", "label": "Example API key" }]
  },
  "mcpServers": [
    { "id": "trains", "label": "Trains", "url": "https://api.example.com/mcp", "headers": { "Authorization": "Bearer ${apiKey}" } }
  ],
  "skills": ["skills/booking"],
  "rules": "rules.md"
}
```

- `format` is this document's version. A server refuses a format it does not know.
- `id` is a reverse-domain name: unique, and the publisher's to keep. `version` is semantic.
- Everything a package contributes is named in the manifest; a file the manifest does not name is ignored.
- Names are scoped by the package so two packages cannot collide: a skill is `<id>/<skill>`, an MCP server's
  model-visible name is `<last part of id>_<server id>` (`trains_trains`), made unique with a digit when taken.
- Limits: 5 MB a package, 200 files, text files UTF-8. In format 1 a package holds only markdown, json, html and png.

## 2. Trust

Who published a package, and that nobody changed it since.

- `signature.json` lists the SHA-256 of every other file and carries a signature over that list: ECDSA P-256 with
  SHA-256, which every Android version the library supports can verify. It also carries the publisher's public key.
- The **publisher's key is the package's identity**. The first install of an id records its key; an update must be
  signed by the same key, as Android does for apps. A different key is refused: the person removes the old one first.
- **Which publishers a host accepts** is the host's decision, in `AppServerConfig`:
  - `trustedPublishers`: public keys the host vouches for (its own catalog's key). Their packages install with one
    confirmation.
  - Any other signed package installs only after a second, plainer warning that the host does not know the publisher.
  - `allowUnsignedPackages` (off by default): for a developer's own device. An unsigned package says so on every
    screen that lists it.
- A package is downloaded over HTTPS, checked against its digests and signature before anything is unpacked, and
  unpacked into the app's private storage (`plugins/<id>/<version>/`), where no other app can change it. The check
  runs again each time the plugin starts.

A signature says who, not whether it is safe. Section 3 is what limits a package the person chose to trust.

## 3. What a package may do

A package asks for what it needs in `permissions`; the install screen shows it in plain words; the server enforces it
while the plugin runs. Asking for more in an update asks the person again.

| Permission | What it allows | Enforced where |
|---|---|---|
| `network: [hosts]` | Its MCP servers (and, in step B, its scripts) reach these hosts, over HTTPS | An MCP server whose address is not on the list is not mounted. Loopback and private addresses need the host named explicitly and are shown as "this device" or "your network" |
| `secrets: [names]` | Values the person types once (a key, an account), kept encrypted with the package, placed into its headers or addresses as `${name}` | A package reads only its own secrets; they are deleted with it and never reach the model |
| `hostTools: [names]` (step B) | Its scripts call these tools of the host | Reserved |
| `nativeCode: true` (step C) | It carries compiled code that runs as the host's own | Reserved; cannot be limited, see section 6 |

What a declarative package cannot do, with nothing to ask for: run code, read files, reach the host's other tools
except by what its text tells the model.

That last part is the real risk of a text-only package: a skill or a rule is an instruction to the model, and a hostile
one can tell it to misuse tools the person already has. The limits:

- A package's rules and skills are given to the model **marked as coming from that package**, after the host's own, and
  the host's rules say that a plugin's text cannot widen what the person allowed.
- A package's skills are loaded on demand, not placed in every system prompt. Only a trusted publisher's package may
  ask to preload one.
- `rules.md` is capped (20 lines, 2,000 characters).
- Tool approvals do not change: a tool that asks before it acts still asks, whoever suggested the call. A package's own
  MCP tools start in "ask every time" for anything that is not a read.
- A package cannot contribute hooks, input preparation or tool wrappers: those points of the kernel are for built-in
  plugins.

## 4. Life of a package

- **Install**: `plugins/install {url | file}` fetches and checks it and answers with what it is and what it asks
  (manifest, publisher, whether trusted, permissions, what changed since the installed version). Nothing runs yet.
  `plugins/confirm {installId, secrets}` unpacks it, registers it with the plugin kernel and starts it.
- **Use**: it appears in `plugins/list` with its source (`built-in` or `package`), version, publisher and permissions,
  and is switched with `plugins/set` like any plugin.
- **Update**: installing a higher version of an installed id, signed by the same key. The old version stays on disk
  until the new one has started, and comes back if it does not.
- **Remove**: `plugins/remove {id}` stops it, deletes its files and its secrets. Conversations keep the records of the
  calls it made.
- A package whose check fails at start (a changed file) does not start and is shown as damaged.

## 5. What the kernel needs for this

- Adding and removing a plugin while the server runs (`PluginHost.add`, `remove`); today the list is fixed when the
  process starts.
- Three more additions on `PluginContext`, which built-in plugins get too and which replace the matching parts of
  `RunAddOns`: `skills`, `rules`, `mcpServers`.
- A store for a plugin's secrets.
- `PackagePlugin`, the reader described above, and the install requests.

## 6. Not in this step

- Scripts (step B): `scripts/` and `hostTools` are reserved so format 1 packages stay valid.
- The catalog: where packages are listed, reviewed and updated from is a product of its own. The library installs a
  package from an address a client gives it.
- Compiled code (step C), reserved as below.

### Compiled code in a package, later

Bothy is distributed by its own channel, so loading compiled code from a download is allowed; it is the third kind of
package, after declarative ones and scripts, and the only one this design cannot confine.

| Kind | Carries | Who may publish | What limits it |
|---|---|---|---|
| A, declarative | Skills, rules, MCP server addresses, templates | Anyone (an unknown publisher behind a second warning) | The permissions of section 3, enforced |
| B, scripts | Tools in JavaScript, in the sandbox | Anyone | The same, enforced by the sandbox's bridge |
| C, compiled code | Kotlin compiled to DEX | Only a publisher the host trusts (`trustedPublishers`) | Nothing: it runs in the host's process with all the host may do |

What is reserved in format 1 so that C needs no new format:

- `code/` in the package: `code/classes.dex`, and `"code": { "entry": "<class name>", "api": "<plugin API version>" }`
  in the manifest. The entry class implements `AppServerPlugin`; the kernel treats it as any other plugin.
- The permission `"nativeCode": true`. A server that does not load code refuses a package that asks for it, so a
  format 1 server never half-installs one.

What C will have to settle before it is built, none of it decided here:

- **A frozen binary interface.** The package compiles against a small API jar (`agent-api` and the plugin interface)
  and links to the host's classes at run time. Those classes must be kept from the host's shrinker and renamer, and
  their signatures must not change within an API version. `agent-api` already has an API comparison in CI; the plugin
  interface would need the same, and to leave "experimental" first.
- **The host's Kotlin, not the package's.** A package runs on the host's standard library and coroutines. The API
  version in the manifest names what the package was built against, and the server refuses one it cannot serve.
- **No confinement.** Class loaders separate names, not trust: compiled code can read the app's private files and
  reach the network whatever its manifest says. That is why only a trusted publisher's package may carry code, and why
  the install screen says so in those words.
- **Unloading is not guaranteed.** One reference left (a thread, a listener, a cache) keeps the old code alive, so an
  update may leave two versions in memory until the process restarts. The kernel stops the old plugin's additions;
  it does not promise its classes are gone.
- **What it cannot carry.** No manifest components, permissions or resources: a feature that needs a system
  capability (the microphone, accessibility, notifications) stays a plugin built into the host. Loading from memory
  needs Android 8; on Android 7 the code is loaded from a read-only file in private storage.

Good first candidates are plugins of pure logic: a model service's adapter, tools that call web interfaces.

## 7. Decided by the owner (2026-11-09)

1. A package signed by a publisher the host does not know may be installed, behind a second warning.
2. A package's skill may be preloaded into the system prompt only when its publisher is trusted.
3. The file extension is `.agentplugin` and ids are reverse-domain names.
4. A package may point at MCP servers on the device or the local network, named explicitly and shown as such.

## 8. As built, and what a run on the tablet found (2026-11-10)

Built in the Bothy repository (`codex/api-redesign`, commits ce5f0297 to 7cf56931, in `agent-app-server` under
`packages/`, with the tool in `tools/agent-plugin/`). Where it differs from the text above:

- A package's skill is named `<id>:<skill>`: the runtime reads a skill's name as one path segment.
- Secrets are kept as the app's other keys are, as plain text in its private no-backup storage, not encrypted.
- A secret goes into a header value, or stands for a whole query value; never into the host or path of an address.
- HTTP is allowed to the device itself only; hosts match exactly; rules over the cap are refused, and all plugins'
  rules share 3 KiB of the system prompt.
- A package with `code/`, `code`, `nativeCode`, `scripts/` or `hostTools` is refused when it is checked.
- An install may also give the package's bytes directly, and an unsigned package needs the same acknowledgement as an
  unknown publisher's.

Checked on the tablet with DeepSeek and a test MCP service on the device: an unknown publisher needs the
acknowledgement; a missing secret, a changed file, code in the package, an older version, another key and an unsigned
update are each refused with their reason; the secret reaches the service in its header; a tool marked read-only runs
unasked and one that acts asks first, in ask-every-time and in allow-all; an update keeps the secret; the package is
there after a restart; a file changed on the device shows it damaged and not running, and installing the same version
repairs it; removing it leaves no files and no secrets; switching it off takes its tool from the next turn.

Found there:

- The manifest's placeholder pattern did not compile on Android and crashed the app at the first install (fixed and
  checked on the device; the JVM unit tests could not see it).
- Through the debug ACP socket in its approve-all mode a package's acting tool ran unasked: that mode sets the turn's
  policy itself and passes the server's rule by. The app's own protocol asks. Open.
- In the runtime, not in packages: a tool that was approved and then failed ended the whole turn with an error. It
  was a rule of the loop, with two tests keeping it ("the user approved this call, not further attempts"). Changed
  2026-11-10 (6c40996): the model reads the failure and answers for it; an approval still covers one call, and another
  attempt that needs one asks again. Checked on the tablet.

Not yet tried: installing from an address (only bytes were given), a trusted publisher's package and preloading,
templates, what a subagent or a plan run does with a tool that asks.

## 9. Step B: tools written as scripts

Status: built 2026-11-10 (below, "As built"). A package may carry tools of its own, written in JavaScript and run in
the sandbox the runtime already ships (`agent-exec-js`: QuickJS through Zipline, `ZiplineExecEngine`). A script has no
access to the device: everything it does outside pure computation goes through a few host functions, each checked
against what the package asked for.

### In the package

```json
"permissions": { "network": ["api.example.com"], "secrets": [{ "name": "apiKey", "label": "API key" }], "hostTools": ["web_search"] },
"tools": [
  { "name": "fare", "description": "The fare between two stations.", "script": "scripts/fare.js",
    "inputSchema": { "type": "object", "properties": { "from": { "type": "string" }, "to": { "type": "string" } }, "required": ["from", "to"] },
    "readOnly": true }
]
```

- `scripts/<name>.js` is allowed once the manifest names it as a tool's `script`; a script no tool names is refused.
- The script defines `function run(input)` and returns a value that can be written as JSON; a thrown error is the
  tool's failure. `input` is the call's arguments, already checked against `inputSchema`.
- The model sees the package's script tools as one tool server named for the package, like its MCP servers.

### What a script can call

Through the engine's bridge (`tools.call(name, arguments)`), under names kept for the host:

| Call | Does | Limited by |
|---|---|---|
| `host.http` `{method, url, headers, body}` | One HTTPS request, answer as `{status, headers, body}` | Only hosts in `network` (the device itself over HTTP when named, as for MCP servers); no redirects followed; request and answer capped (64 KiB sent, 256 KiB read); what is left of the call's time |
| `host.tool` `{name, arguments}` | Calls one of the host's tools | Only tools in `hostTools`, and only tools that say they read (`effectKind` read-only): a script cannot act through the host's tools in this step |
| `host.log` `{message}` | A line in the app's log, marked as the package's | Length capped |

- **Secrets never enter the script.** A header value or a whole query value may hold `${name}`; the host fills it in
  when it sends the request, as it does for MCP servers. The script sees the placeholder.
- No files, no timers, no other network, no other tools.
- The engine runs a script again from the start after each host call, replaying earlier answers in order, so a script
  must be deterministic between calls (no branching on the time or on random numbers). One that is not stops with an
  error, as `exec_js` does today.
- Limits per call of a tool: its time in all, host calls included; 16 MiB of memory; 20 host calls; 64 KiB returned.
  The time is the tool's to ask for (`"timeoutSeconds"` in the manifest, 1 to 120, 10 when left out) up to the host's
  ceiling (`AppServerConfig.maxScriptToolSeconds`, 30 by default): a tool that asks for more runs with the ceiling. The
  other limits protect the host and are not a package's to set.

### Asking first

- A script tool is a package's own tool: it asks before it runs unless the manifest says `readOnly: true` **and** the
  publisher is trusted. An unknown publisher's word that a tool only reads is not taken: its script tools always ask
  (once per conversation in the mode that remembers).
- What a script calls through `host.tool` is read-only by the rule above, so it does not ask again.

### What it needs

- Format 1 gains `tools` and lets `scripts/` and `hostTools` through for a package that uses them this way; `code/`
  and `nativeCode` stay refused.
- `PackagePlugin` adds a tool provider for the package's script tools, through the kernel's `tools` addition, with the
  gate packages' MCP tools already use.
- No change to the runtime is expected: the app server already depends on `agent-exec-js` and gives the engine its
  own bridge.

### Proposed, open to the owner

1. Host tools a script may call are read-only ones only, in this step.
2. A script tool's `readOnly` is honoured only for a trusted publisher.
3. The limits above.

### As built, and what a run on the tablet found (2026-11-10)

In the Bothy repository (`codex/api-redesign`, commits 414fa136 to 62b5785a; `packages/Script*.kt`). Where it differs
from the text above:

- **`host.tool` reaches less than the example suggests.** It reaches the tools of the run's backend tool providers
  that say they only read, in practice a configured MCP server's tools marked read-only. The runtime's own tools
  (`web_search`, `web_fetch`, files) are not tool providers and cannot be called from a script, so
  `"hostTools": ["web_search"]` does not work; that would take a change to the runtime's interfaces. A name in
  `hostTools` is the whole name (`mcp__home__get_home_status`), or the tool's own when one server has it.
- **One time limit, and the tool asks for it** (939ca43f). A call's time includes its host calls, so a request gets
  what is left of it; a separate limit per request could never apply and is gone. On the tablet a tool asking 3 s
  whose script never ends stopped 3.0 s after its approval, and one asking 120 s stopped at the host's 30.0 s, each
  failure naming the seconds in effect; a client is shown both numbers (`PackageTool`).
- `host.log` keeps its lines in the sandbox and writes them when the call ends; they are lost when the call ends on
  time or memory.
- Added: a name that reads as on the internet must resolve to public addresses; where an answer repeats a secret's
  value the placeholder is put back; `inputSchema` is a checked subset of JSON Schema (no `pattern`, `$ref`, `oneOf`),
  so no pattern of a package's runs on the host; at most 20 tools a package, 128 KiB a script, tool names of at most 24
  characters; `run` returns at once (no promise).
- The kernel's `tools` addition gained `askBeforeActions`, and the gate packages' MCP tools use covers script tools.

The real engine runs in the unit tests on the JVM. On the tablet, with DeepSeek: the three example tools ran (the
weather over HTTPS with decimal numbers intact, the home's state through `host.tool` from the Playground's MCP server,
the echo with the secret shown as its placeholder in header and query); each asked first, the publisher being unknown;
the log lines appeared and the secret did not. A package written to misbehave was refused each thing it tried: a tool
that acts, a tool it had not named, a secret in a body (sent as the placeholder) or in a path, plain HTTP, an address on
the device it had not named, a `file:` address, the engine's bridge called directly; a loop without end stopped at
10 s and an allocation without end at the memory limit, the app staying up.

Not yet tried: a trusted publisher's script tools running unasked, a subagent or a plan run calling one, a turn
cancelled while a script runs, two packages' scripts at once.

Open: the debug ACP socket's approve-all mode passes the asking rule by (as for packages' MCP tools); nothing limits
how often a package's tools are called; a package may send what it reads through `host.tool` to any host it named.
