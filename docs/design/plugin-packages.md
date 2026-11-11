# Plugin packages a person downloads

Status: built, in `agent-app-server` (`packages/`), with the publishing tool in `tools/agent-plugin`. Experimental, as
the app server is.

A package is a plugin with no compiled code: skills, rules, addresses of MCP servers, and tools written as scripts
that run in a sandbox. A person installs one inside the app from an address, a file or its bytes; it starts at once and
is turned off or removed like any plugin. The rest of the server sees no difference from a plugin built into the host:
one generic plugin (`PackagePlugin`) reads an installed package and says its contents to its `PluginContext`.

Three things are hard to change once people write packages, so this document fixes them: the format, how a package is
trusted, and what it may do.

## 1. The format

A package is a zip file named `<id>-<version>.agentplugin`:

```
plugin.json            the manifest (below)
signature.json         the digest of every other file, and the publisher's key and signature over them (section 2)
skills/<name>/SKILL.md one directory per skill, a SKILL.md as the app server reads one
rules.md               optional: lines added to the system rules while the package runs
icon.png               optional
scripts/<name>.js      the scripts of its tools (section 5)
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

- `format` is this document's version; a server refuses a format it does not know, before anything else.
- `id` is a reverse-domain name, the publisher's to keep; `version` is semantic (no build metadata). `minServer` is
  the app server version it needs: the version the server was built as, without a pre-release.
- The manifest is read strictly: a member the format does not have is refused, and so is anything a member names that
  is not in the package. Everything a package contributes is named in it.
- Names are scoped by the package: a skill is `<id>:<skill>` (the runtime reads a skill's name as one path segment);
  an MCP server's tools are `mcp__<last part of id>_<server id>__<tool>` (`trains_trains`); the script tools are one
  tool server named for the last part of the id (`mcp__trains__<tool>`). No two installed packages share such a name,
  and a package does not take one the host's other tool servers go by when it is installed (the host's tools, the
  configured MCP servers, the running plugins'): the install is refused (`TOOL_SERVER_TAKEN`). A server the person
  adds later takes none of them; a tool server the host itself adds later keeps its name, and the package's then
  takes a digit.
- Limits: 5 MB packed and unpacked, 200 files (400 zip entries), paths of at most 200 characters and 8 levels, text in
  UTF-8. A package holds only markdown, json and png, and JavaScript under `scripts/`; no hidden files, no path that
  leaves it. At most 20 hosts, 10 secrets, 10 MCP servers, 20 skills and 20 tools; `rules.md` at most 20 lines and
  2,000 characters; an icon at most 256 KiB.

## 2. Trust

Who published a package, and that nobody changed it since.

- `signature.json` lists the SHA-256 of every other file and, when signed, carries the publisher's public key and an
  ECDSA P-256 / SHA-256 signature over `agentplugin-signature-1\n` followed by `<sha256>  <path>\n` for each file in
  path order. The key is a DER SubjectPublicKeyInfo naming the curve P-256 with the point uncompressed (91 bytes), in
  that one form, so which keys are taken does not depend on the device's security provider.
- **The publisher's key is the package's identity.** The first install of an id records its key; an update must be
  signed by the same key and be no older, as Android does for apps. Another key is refused: the person removes the old
  package first.
- **Which publishers a host accepts** is the host's decision, in `AppServerConfig`:
  - `trustedPublishers`: public keys the host vouches for (its own catalog's key). Their packages install with one
    confirmation, and their word that a tool only reads is taken.
  - Any other signed package installs only after a second, plainer warning that the host does not know the publisher
    (`needsAcknowledgement`, answered with `acknowledgeUntrusted`).
  - `allowUnsignedPackages` (off by default), for a developer's own device: an unsigned package needs the same
    acknowledgement, and says so on every screen that lists it.
- A package is checked whole (zip, digests, signature, manifest, contents) before anything of it is written, and
  unpacked into the app's private storage, `files/app_server/plugins/<id>/<version>/`, with its record (id, version,
  publisher's key) in `files/app_server/plugins/<id>/installed.json`. The check runs again each time it starts.

A signature says who, not whether it is safe. Section 3 is what limits a package the person chose to trust.

## 3. What a package may do

A package asks for what it needs in `permissions`; the install screen shows it; the server enforces it while the
package runs. An update that asks for more says what (`PackageChange`), and the person confirms it again.

| Permission | What it allows | Enforced where |
|---|---|---|
| `network: [hosts]` | Its MCP servers and scripts reach these hosts, matched exactly | An MCP server's host must be listed; it is reached over HTTPS (HTTP only to this device) and a redirect is not followed. Each host is shown with where it is: the internet, the person's network (private addresses, `.local` names) or this device (loopback) |
| `secrets: [names]` | Values the person types once (a key, an account), placed into its MCP servers' and scripts' requests as `${name}` | Only in a header value or as a whole query value, never in a host or path. A package reads only its own; they never reach the model or a script, and are deleted with the package |
| `hostTools: [names]` | Its scripts call these tools of the run, each one that says it only reads | Section 5 |

What a package cannot do, with nothing to ask for: carry compiled code, contribute hooks, input preparation or tool
wrappers (those points of the kernel are for plugins built into the host), read files, or reach the host's other tools
except by what its text tells the model.

That last part is the real risk of a package: a skill or a rule is an instruction to the model, and a hostile one can
tell it to misuse tools the person already has. The limits:

- A package's rules and skills reach the model **marked as coming from that package**, after the host's own, and the
  server's rules say that a plugin's text cannot widen what the person allowed.
- Its skills are read on demand, not placed in every system prompt; only a trusted publisher's may ask to be preloaded.
- `rules.md` is capped, and all plugins' rules share 3 KiB of the system prompt.
- **Asking does not change.** A tool that asks before it acts still asks, whoever suggested the call, and a package's
  own tools that act ask every time, in every approval mode but "deny all", which refuses them; a yes is never
  remembered, and a subagent's call of one is refused. A tool counts as only reading when it says so **and** its
  publisher is one the host trusts: an unknown publisher's script tools, and every tool of its MCP servers whatever
  the remote server's `readOnlyHint` says, ask before each call.

## 4. Life of a package

- **Install**, in two steps. `plugins/install` takes one of `url` (HTTPS; a redirect to plain HTTP is not followed),
  `file` (a `content://` address, or a `file://` path in the app's own external files) or `data` (the package's bytes,
  base64), checks the package and answers with what it is and asks for: the manifest, the publisher, whether trusted,
  the permissions, and for an update what it asks beyond the installed version. Nothing runs and nothing is written.
  `plugins/confirm {installId, secrets, acknowledgeUntrusted}` within 15 minutes unpacks it, keeps its secrets,
  registers it with the plugin kernel and starts it.
- **Use**: it is in `plugins/list` with its source (`package`), version, publisher, permissions and contents, and is
  switched with `plugins/set` like any plugin.
- **Update**: a higher version of an installed id, signed by the same key. A secret left out keeps its value. The
  version it replaces stays on disk until the new one has started, and comes back if it does not.
- **Remove**: `plugins/remove {id}` stops it and deletes its files, its secrets and its skills' switches.
  Conversations keep the records of the calls it made.
- **Damaged**: a package whose files changed since it was installed does not start and is shown as damaged with what
  the check found; installing it again repairs it. One whose record cannot be read is shown as damaged with its files
  and secrets kept, and its id takes no install until it is removed, since the key it was installed with is not known.
- **At start**, the server clears what an install the process did not finish left: staged files, a version moved aside,
  a record half written, a first install that did not finish (with its secrets). It looks only at directories named as
  package ids under its own directory, and deletes only those leftovers.

## 5. Tools written as scripts

A package may carry tools of its own, written in JavaScript and run in the runtime's sandbox (`agent-exec-js`: QuickJS
through Zipline), which a host adds to its app for them: a host without it refuses such a package
(`SCRIPTS_UNAVAILABLE`). A script has no access to the device: everything it does outside pure computation goes
through three host functions, each checked against what the package asked for.

```json
"permissions": { "network": ["api.example.com"], "secrets": [{ "name": "apiKey", "label": "API key" }], "hostTools": ["mcp__calendar__list_events"] },
"tools": [
  { "name": "fare", "description": "The fare between two stations.", "script": "scripts/fare.js",
    "inputSchema": { "type": "object", "properties": { "from": { "type": "string" }, "to": { "type": "string" } }, "required": ["from", "to"] },
    "readOnly": true, "timeoutSeconds": 20 }
]
```

- A tool has a `name` (lower-case letters, digits and `_`, at most 24, so the full name stays within what model
  services take), a `description` for the model, an `inputSchema` in the part of JSON Schema the server checks (no
  `pattern`, `$ref` or `oneOf`, so no pattern of a package's runs on the host), its `script` (`scripts/<name>.js`, at
  most 128 KiB; a script no tool names is refused), `readOnly`, and `timeoutSeconds` (1 to 120; 10 when left out).
- The script defines `function run(input)`, `input` being the call's arguments already checked against `inputSchema`,
  and returns a value JSON can write (at most 64 KiB); a thrown error is the tool's failure, its message what the model
  reads. It runs in strict mode with 16 MiB of memory and at most 20 host calls; `host` and names starting with
  `__pkg` are the host's.
- **One time limit per call**, host calls included: the tool's `timeoutSeconds`, held to the host's most
  (`AppServerConfig.maxScriptToolSeconds`, 30 unless the host sets another). Clients are shown both numbers.
- The engine runs a script again from the start after each host call, replaying earlier answers in order, so a script
  must make the same calls each time; one that does not stops with an error, no request repeated.

What a script can call:

| Call | Does | Limited by |
|---|---|---|
| `host.http({method, url, headers, body})` | One request, answered as `{status, headers, body}` | Only hosts in `network`, over HTTPS (HTTP only to this device when named); no redirect followed; never through a proxy; a name on the internet that resolves into this device or the person's network is refused; 64 KiB sent, 256 KiB read; within what is left of the call's time |
| `host.tool(name, arguments)` | Calls a tool of the run, without asking the person | Only tools named in `hostTools` that say they only read, among the run's configured MCP servers and the plugins' tool servers the person switched on. Not the host's own tools, plugins' or packages' MCP servers, or the runtime's own tools (`web_search`, `web_fetch`, files). A name is the whole `mcp__<server>__<tool>`, or the tool's own when one server has it |
| `host.log(message)` | A line in the app's log, marked with the package and tool | 20 lines of 500 characters, written when the call ends |

- **Secrets never enter the script.** A header value or a whole query value may hold `${name}`; the host fills it in as
  it sends the request. The script sees the placeholder, an answer that repeats a secret has the placeholder put back,
  and what a refusal says never holds the address or a secret.
- No files, no timers, no other network, no other tools.

## 6. Known limits

- **Secrets are not encrypted.** They are kept as plain JSON in the app's no-backup storage
  (`no_backup/app_server/plugin-secrets/<id>.json`), as the server keeps its other keys: private to the app, out of its
  backups, readable by anything that can read the app's private files.
- **This device is one host.** A package that names `localhost` or `127.0.0.1` reaches every port on the device, the
  host app's own local servers included.
- **The server's trust manager.** Packages' downloads, MCP servers and scripts connect with the server's own trust
  manager (the system's roots and the host's `extraTrustRoots`); the host's `network_security_config` (pins, per-domain
  trust) does not apply to them.
- **The debug socket.** With `debugSockets` on, anyone with adb's shell can install a package and acknowledge an unknown
  publisher, as any client can.
- Nothing limits how often a package's tools are called; a package may send what it reads through `host.tool` to any
  host it named; `host.log` lines are lost when a call ends on its time or memory limit.

## 7. Compiled code, reserved

Loading compiled code from a download is a third kind of package, after declarative ones and scripts, and the only one
this design cannot confine. It is not built; format 1 reserves what it needs so it will need no new format.

| Kind | Carries | Who may publish | What limits it |
|---|---|---|---|
| Declarative | Skills, rules, MCP server addresses | Anyone (an unknown publisher behind a second warning) | The permissions of section 3, enforced |
| Scripts | Tools in JavaScript, in the sandbox | Anyone | The same, enforced by the sandbox's host functions |
| Compiled code | Kotlin compiled to DEX | Only a publisher the host trusts | Nothing: it would run in the host's process with all the host may do |

Reserved in format 1: a `code/` directory (`code/classes.dex`), a `"code": { "entry": "<class name>", "api": "<plugin
API version>" }` member and the permission `"nativeCode": true`. A server that does not load code refuses a package
that has any of them (`CARRIES_CODE`), so it never half-installs one.

What compiled code would have to settle first:

- **A frozen binary interface.** The package compiles against a small API jar (`agent-api` and the plugin interface)
  and links to the host's classes at run time; those classes must be kept from the host's shrinker, and their
  signatures must not change within an API version, so the plugin interface would have to leave "experimental".
- **The host's Kotlin, not the package's.** A package runs on the host's standard library and coroutines; the API
  version in the manifest names what it was built against, and the server refuses one it cannot serve.
- **No confinement.** Class loaders separate names, not trust: compiled code can read the app's private files and
  reach the network whatever its manifest says. That is why only a trusted publisher's package may carry code.
- **Unloading is not guaranteed.** One reference left keeps the old code alive, so an update may leave two versions in
  memory until the process restarts; the kernel stops the old plugin's additions, not its classes.
- **What it cannot carry**: manifest components, permissions or resources. A feature that needs a system capability
  (the microphone, accessibility, notifications) stays a plugin built into the host.

## 8. The catalog, out of scope

Where packages are listed, reviewed and updated from is a product of its own. The library installs a package from an
address, a file or the bytes a client gives it, and leaves finding packages to the host.
