#!/usr/bin/env python3
"""Makes plugin packages (.agentplugin) for the app server: a publisher's key pair, and a package built and signed
from a directory, the way the server checks one (agent-app-server, packages/).

  python3 agentplugin.py keygen publisher.key          a new ECDSA P-256 key; prints its public key for trustedPublishers
  python3 agentplugin.py pubkey publisher.key          the public key (hex of its DER encoding) and its fingerprint
  python3 agentplugin.py build DIR --key publisher.key [--out FILE]   DIR as a signed <id>-<version>.agentplugin
  python3 agentplugin.py build DIR --unsigned [--out FILE]            the same with digests only (a developer's device)
  python3 agentplugin.py verify FILE [--key HEX]       checks a package's digests and signature (and that HEX signed it)

Needs Python 3 and the openssl command; the private key never leaves the file openssl writes.
"""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

MANIFEST = "plugin.json"
SIGNATURE = "signature.json"
ALGORITHM = "ecdsa-p256-sha256"
PAYLOAD_HEADER = "agentplugin-signature-1\n"
EXTENSIONS = {".md", ".json", ".png"}
SCRIPTS_DIR = "scripts/"
SCRIPT_SUFFIX = ".js"
PATH_PART = re.compile(r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")
TOOL_NAME = re.compile(r"[a-z][a-z0-9_]{0,23}")
TOOL_KEYS = {"name", "description", "inputSchema", "script", "readOnly", "timeoutSeconds"}
MAX_BYTES = 5 * 1024 * 1024
MAX_FILES = 200
MAX_SCRIPT_BYTES = 128 * 1024
MAX_TOOLS = 20
MAX_TOOL_SECONDS = 120
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def openssl(*args, data=None):
    """Runs openssl with [args], [data] on its input; its output, or the reason it failed."""
    try:
        done = subprocess.run(["openssl", *args], input=data, capture_output=True, check=False)
    except FileNotFoundError:
        sys.exit("the openssl command is needed")
    if done.returncode != 0:
        sys.exit(f"openssl {args[0]} failed: {done.stderr.decode(errors='replace').strip()}")
    return done.stdout


def public_key(key_file):
    """The public key of [key_file] as DER SubjectPublicKeyInfo."""
    return openssl("pkey", "-in", str(key_file), "-pubout", "-outform", "DER")


def fingerprint(key_der):
    digest = hashlib.sha256(key_der).hexdigest()[:32]
    return ":".join(digest[i:i + 4] for i in range(0, 32, 4))


def keygen(key_file):
    path = Path(key_file)
    if path.exists():
        sys.exit(f"{path} exists; a key is not overwritten")
    pem = openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as out:
        out.write(pem)
    show_key(path)
    print("Keep the key file private and out of any repository; every later version must be signed with it.")


def show_key(key_file):
    key = public_key(key_file)
    print(f"public key:  {key.hex()}")
    print(f"fingerprint: {fingerprint(key)}")


def package_files(directory):
    """The files a package of [directory] holds, by path: hidden files and signature.json left out."""
    root = Path(directory)
    files = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if any(part.startswith(".") for part in relative.split("/")) or relative == SIGNATURE or not path.is_file():
            continue
        if not all(PATH_PART.fullmatch(part) for part in relative.split("/")):
            sys.exit(f"{relative}: a path takes letters, digits, '.', '_' and '-' only")
        if relative.startswith(SCRIPTS_DIR) != (path.suffix == SCRIPT_SUFFIX):
            sys.exit(f"{relative}: JavaScript goes under {SCRIPTS_DIR}, and only JavaScript")
        if path.suffix not in EXTENSIONS and path.suffix != SCRIPT_SUFFIX:
            sys.exit(f"{relative}: a package holds only markdown, json, png and scripts")
        files[relative] = path.read_bytes()
    if MANIFEST not in files:
        sys.exit(f"no {MANIFEST} in {root}")
    if len(files) >= MAX_FILES or sum(len(b) for b in files.values()) > MAX_BYTES:
        sys.exit("a package holds at most 200 files and 5 MB")
    return files


def check_tools(files):
    """The manifest's tools as the server checks them first: each named once, with its script; no script left over."""
    manifest = json.loads(files[MANIFEST].decode("utf-8"))
    tools = manifest.get("tools", [])
    if manifest.get("permissions", {}).get("hostTools") and not tools:
        sys.exit("hostTools without tools")
    if len(tools) > MAX_TOOLS:
        sys.exit(f"at most {MAX_TOOLS} tools")
    names = [tool.get("name", "") for tool in tools]
    for tool, name in zip(tools, names):
        if set(tool) - TOOL_KEYS or not TOOL_NAME.fullmatch(name) or names.count(name) > 1:
            sys.exit(f"tool {name!r}: a unique name of lower-case letters, digits and _ (24 at most), and only {sorted(TOOL_KEYS)}")
        if not tool.get("description") or tool.get("inputSchema", {}).get("type") != "object":
            sys.exit(f"tool {name}: a description, and an inputSchema of type object")
        if not isinstance(tool.get("readOnly", False), bool):
            sys.exit(f"tool {name}: readOnly is true or false")
        if "timeoutSeconds" in tool and not is_tool_seconds(tool["timeoutSeconds"]):
            sys.exit(f"tool {name}: timeoutSeconds is a whole number of seconds from 1 to {MAX_TOOL_SECONDS}")
        script = tool.get("script", "")
        if script not in files or not script.startswith(SCRIPTS_DIR) or not script.endswith(SCRIPT_SUFFIX):
            sys.exit(f"tool {name}: its script {script!r} is not a file under {SCRIPTS_DIR}")
        if len(files[script]) > MAX_SCRIPT_BYTES:
            sys.exit(f"{script}: over {MAX_SCRIPT_BYTES // 1024} KiB")
    named = {tool.get("script") for tool in tools}
    for path in files:
        if path.startswith(SCRIPTS_DIR) and path not in named:
            sys.exit(f"{path}: no tool names it")


def is_tool_seconds(value):
    """Whether [value] is a tool's time as the server takes it: an integer (not true or false, not 3.0), 1 to 120."""
    return isinstance(value, int) and not isinstance(value, bool) and 1 <= value <= MAX_TOOL_SECONDS


def signed_payload(digests):
    """What a publisher signs: a fixed first line, then '<sha256>  <path>' per file in path order."""
    return (PAYLOAD_HEADER + "".join(f"{digests[p]}  {p}\n" for p in sorted(digests))).encode("utf-8")


def signature_file(files, key_file):
    """signature.json for [files]: their digests and, with [key_file], the publisher's key and signature."""
    digests = {path: hashlib.sha256(data).hexdigest() for path, data in files.items()}
    content = {"format": 1}
    if key_file:
        content["algorithm"] = ALGORITHM
        content["publicKey"] = public_key(key_file).hex()
    content["digests"] = dict(sorted(digests.items()))
    if key_file:
        content["signature"] = openssl("dgst", "-sha256", "-sign", str(key_file), data=signed_payload(digests)).hex()
    return json.dumps(content, indent=2).encode("utf-8") + b"\n"


def zipped(files):
    """[files] as a zip whose bytes depend on their content alone (sorted, one fixed time)."""
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(files):
            info = zipfile.ZipInfo(path, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, files[path])
    return out.getvalue()


def build(directory, key_file, out):
    files = package_files(directory)
    check_tools(files)
    manifest = json.loads(files[MANIFEST].decode("utf-8"))
    files[SIGNATURE] = signature_file(files, key_file)
    target = Path(out or f"{manifest['id']}-{manifest['version']}.agentplugin")
    target.write_bytes(zipped(files))
    print(f"wrote {target} ({'signed' if key_file else 'unsigned'})")
    if key_file:
        print(f"fingerprint: {fingerprint(public_key(key_file))}")
    return target


def verify(package, expected_key=None):
    """Checks [package]'s digests and signature as the server does; with [expected_key], that this key signed it."""
    with zipfile.ZipFile(package) as archive:
        files = {name: archive.read(name) for name in archive.namelist() if not name.endswith("/")}
    signature = json.loads(files.pop(SIGNATURE).decode("utf-8"))
    digests = signature["digests"]
    if set(digests) != set(files):
        sys.exit("the files are not those signature.json lists")
    for path, data in files.items():
        if hashlib.sha256(data).hexdigest() != digests[path]:
            sys.exit(f"{path} is not what its digest says")
    if "signature" not in signature:
        print("digests match; unsigned")
        return
    if expected_key and signature["publicKey"] != expected_key.lower():
        sys.exit("signed by another key")
    with tempfile.TemporaryDirectory() as work:
        der, sig = Path(work, "key.der"), Path(work, "sig.der")
        der.write_bytes(bytes.fromhex(signature["publicKey"]))
        sig.write_bytes(bytes.fromhex(signature["signature"]))
        pem = Path(work, "key.pem")
        openssl("pkey", "-pubin", "-inform", "DER", "-in", str(der), "-out", str(pem))
        openssl("dgst", "-sha256", "-verify", str(pem), "-signature", str(sig), data=signed_payload(digests))
    print(f"digests and signature match; fingerprint {fingerprint(bytes.fromhex(signature['publicKey']))}")


def main():
    parser = argparse.ArgumentParser(description="Makes plugin packages for the app server.")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("keygen").add_argument("key")
    commands.add_parser("pubkey").add_argument("key")
    builder = commands.add_parser("build")
    builder.add_argument("directory")
    signing = builder.add_mutually_exclusive_group(required=True)
    signing.add_argument("--key")
    signing.add_argument("--unsigned", action="store_true")
    builder.add_argument("--out")
    checker = commands.add_parser("verify")
    checker.add_argument("package")
    checker.add_argument("--key")
    args = parser.parse_args()
    if args.command == "keygen":
        keygen(args.key)
    elif args.command == "pubkey":
        show_key(args.key)
    elif args.command == "build":
        build(args.directory, None if args.unsigned else args.key, args.out)
    else:
        verify(args.package, args.key)


if __name__ == "__main__":
    main()
