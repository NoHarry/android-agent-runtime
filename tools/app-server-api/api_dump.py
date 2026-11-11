"""The app server's public API by level, checked against agent-app-server/api/agent-app-server-levels.txt.

Every public declaration of :agent-app-server, with the public members of its public classes, objects and interfaces
(overrides left out), one line each: where it is, its level, then its header with comments and line breaks taken out.
The binary API is api/agent-app-server.api, which the build's apiCheck compares as for every module; this listing says
what may use each declaration, which the binary listing cannot (its markers do not reach nested classes, default
arguments' methods or interfaces' DefaultImpls). The level is what may use it:

- host: a host app (plain public): what the host guide names and the interfaces a host may implement;
- plugin: a plugin module, which opts in (@AppServerPluginApi);
- test: another module's tests only (@InternalAppServerApi; never a module's main code).

A member has its container's level unless marked itself. A change to that surface, or to a declaration's level, fails
the check until the file is written again on purpose, so a change to what hosts see shows in review.

Usage: python3 api_dump.py [--write] [--counts] [--sources DIR]   (from anywhere; paths are relative to this file)
`./gradlew apiCheck` (and ciCheck) runs the check, `./gradlew apiDump` writes the listing again.
"""
import argparse
import difflib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "agent-app-server" / "src" / "main" / "java"
DUMP = ROOT / "agent-app-server" / "api" / "agent-app-server-levels.txt"
# An annotation as it is written on a declaration (not inside @OptIn(...)), short or with its package.
TEST_MARK = re.compile(r"(?<![\w.(])@(?:cc\.noharry\.agent\.appserver\.)?InternalAppServerApi\b(?!::)")
PLUGIN_MARK = re.compile(r"(?<![\w.(])@(?:cc\.noharry\.agent\.appserver\.)?AppServerPluginApi\b(?!::)")
LEVELS = ("host", "plugin", "test")


def level_of(annotation_text, inherited):
    """The level a declaration's own [annotation_text] gives it, else its container's ([inherited])."""
    if TEST_MARK.search(annotation_text):
        return "test"
    if PLUGIN_MARK.search(annotation_text) and inherited == "host":
        return "plugin"
    return inherited
# Visibility and modifiers, in any order; an annotation may take arguments with one level of parentheses inside.
MODIFIERS = (r"(?:(?:public|internal|private|protected|override|abstract|open|final|const|lateinit|inline|suspend|operator|"
             r"infix|data|enum|sealed|inner|annotation|tailrec|value|companion|expect|actual|external|"
             r"fun(?=\s+interface))\s+)*")
ANNOTATIONS = r"((?:@[\w.:]+(?:\((?:[^()]|\([^()]*\))*\))?\s+)*)"
DECL = re.compile(r"^(\s*)" + ANNOTATIONS + "(" + MODIFIERS + r")(class|interface|object|fun|val|var|typealias|constructor)\b(.*)$")
VISIBILITY = re.compile(r"\b(public|internal|private|protected)\b")
# An enum entry on its own line: its name, then arguments, a body, a comma or the end of the entries.
ENUM_ENTRY = re.compile(r"^\s*([A-Z][A-Za-z0-9_]*)\s*(?:\(.*)?[,;]?\s*\{?\s*$")


class Scan:
    """Kotlin source with comments blanked ([code]) and, per character, the brace depth outside strings (None inside one
    or in a comment)."""

    def __init__(self, text):
        self.text, self.out, self.depths, self.depth, self.stack, self.i = text, [], [], 0, [], 0
        while self.i < len(text):
            top = self.stack[-1] if self.stack else None
            if top in ("line", "block"):
                self.comment(top)
            elif top in ("str", "raw"):
                self.string(top)
            else:
                self.code_char(top)
        self.code = "".join(self.out)

    def emit(self, written, depth=None, length=None):
        self.out.append(written)
        self.depths += [depth] * len(written)
        self.i += len(written) if length is None else length

    def comment(self, top):
        c = self.text[self.i]
        if top == "line" and c == "\n":
            self.stack.pop(); self.emit(c, self.depth)
        elif top == "block" and self.text.startswith("*/", self.i):
            self.stack.pop(); self.emit("  ")
        else:
            self.emit("\n" if c == "\n" else " ")

    def string(self, top):
        end = '"' if top == "str" else '"""'
        if top == "str" and self.text[self.i] == "\\":
            self.emit(self.text[self.i:self.i + 2])
        elif self.text.startswith(end, self.i):
            self.stack.pop(); self.emit(end)
        elif self.text.startswith("${", self.i):
            self.stack.append(["tpl", 0]); self.emit("${")
        else:
            self.emit(self.text[self.i])

    def code_char(self, top):
        text, i = self.text, self.i
        opener = next((o for o in ("//", "/*", '"""', '"') if text.startswith(o, i)), None)
        if opener:
            self.stack.append({"//": "line", "/*": "block", '"""': "raw", '"': "str"}[opener])
            self.emit(opener if opener.startswith('"') else "  ")
        elif text[i] == "'":
            m = re.match(r"'(\\.|[^\\'])+'", text[i:])
            self.emit(text[i:i + (m.end() if m else 1)])
        elif isinstance(top, list):  # inside a string template: its own braces
            if text[i] == "}" and top[1] == 0:
                self.stack.pop()
            elif text[i] in "{}":
                top[1] += 1 if text[i] == "{" else -1
            self.emit(text[i])
        else:
            self.emit(text[i], self.depth)
            self.depth += {"{": 1, "}": -1}.get(text[i], 0)


def header(code, depths, start, delegates=False):
    """The declaration starting at [start], up to its body or initializer, on one line; a secondary constructor's
    ([delegates]) without what it delegates to."""
    nest, i = 0, start
    while i < len(code):
        c = code[i]
        if depths[i] is None:  # inside a string or a comment
            pass
        elif c in "(<[":
            nest += 1
        elif c in ")>]" and not (c == ">" and code[i - 1] == "-"):
            nest = max(0, nest - 1)
        elif nest == 0 and (c == "{" or (c == "=" and code[i - 1] not in "!<>=" and code[i + 1:i + 2] != "=")):
            break
        elif nest == 0 and delegates and c == ":":
            break
        elif nest == 0 and c == "\n":
            rest = code[i + 1:].lstrip(" ")
            if not (rest.startswith((":", ".", "where ")) or code[:i].rstrip().endswith((",", ":", "("))):
                break
        i += 1
    return re.sub(r"\s+", " ", code[start:i]).strip().rstrip(",")


def entries(sources=SOURCES):
    rows = []
    for path in sorted(sources.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        scan = Scan(text)
        code, depths = scan.code, scan.depths
        pkg = re.search(r"^package ([\w.]+)", code, re.M).group(1)
        offset, annotations, owners = 0, [], {}
        # Each owner: (its path, whether it has members, whether it is an enum, its level).
        for line in code.split("\n"):
            depth = depths[offset] if offset < len(depths) else None
            m = DECL.match(line)
            stripped = line.strip()
            level = (len(line) - len(line.lstrip(" "))) // 4
            parent = owners.get(level - 1) if level else ("", True, False, "host")
            if m and depth is not None:
                indent, annos, mods, kind, rest = m.groups()
                vis = (VISIBILITY.search(mods) or [None, None])[1]
                own_level = level_of(annos + " " + " ".join(annotations), parent[3] if parent else "host")
                name = re.match(r"\s*(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)", rest) if kind in ("fun", "val", "var") \
                    else re.match(r"\s*(\w+)", rest)
                name = "constructor" if kind == "constructor" else name.group(1) if name else "?"
                if kind == "object" and "companion" in mods:
                    name = "Companion"
                visible = vis in (None, "public", "protected") and "override" not in mods
                if level == depth and parent is not None and parent[1] and visible:
                    path = f"{parent[0] + '.' if parent[0] else ''}{name}"
                    start = offset + len(indent) + len(annos)
                    rows.append(f"{pkg}.{path} | {own_level} | {header(code, depths, start, delegates=kind == 'constructor')}")
                    owners[level] = (path, kind in ("class", "interface", "object"), "enum" in mods, own_level)
                elif level == depth:
                    owners[level] = (name, False, False, "host")
                for deeper in [k for k in owners if k > level]:
                    del owners[deeper]
            elif depth is not None and level == depth and stripped[:1].isalpha() and parent is not None:
                entry = ENUM_ENTRY.match(line) if parent[2] else None
                if entry:
                    # A public enum's entries are its API too.
                    rows.append(f"{pkg}.{parent[0]}.{entry.group(1)} | {parent[3]} | {entry.group(1)}")
                if "{" in line:
                    # A body nothing above names (an `init` block, an enum entry's): what is declared in it is not the
                    # previous declaration's.
                    owners[level] = (entry.group(1) if entry else "?", False, False, "host")
                    for deeper in [k for k in owners if k > level]:
                        del owners[deeper]
            annotations = annotations + [stripped] if stripped.startswith("@") else []
            offset += len(line) + 1
    return sorted(set(rows))


def counts(rows):
    """How many declarations each level has, and how many of them are top-level (a type, a function, a constant)."""
    found = {level: [0, 0] for level in LEVELS}
    for row in rows:
        path, level = row.split(" | ")[:2]
        found[level][0] += 1
        rest = path.removeprefix("cc.noharry.agent.appserver.").split(".")
        found[level][1] += 1 if len(rest) == 1 or (len(rest) == 2 and rest[0][:1].islower()) else 0
    return ", ".join(f"{level} {total} ({top} top-level)" for level, (total, top) in found.items())


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--write", action="store_true", help="write the dump instead of checking it")
    parser.add_argument("--counts", action="store_true", help="print how many declarations each level has")
    parser.add_argument("--sources", type=Path, default=SOURCES, help="another copy of the module's main sources")
    args = parser.parse_args()
    rows = entries(args.sources)
    if args.counts:
        print(counts(rows))
        return 0
    current = "\n".join(rows) + "\n"
    if args.write:
        DUMP.parent.mkdir(parents=True, exist_ok=True)
        DUMP.write_text(current, encoding="utf-8")
        print(f"[OK] Wrote {DUMP.relative_to(ROOT)}: {counts(rows)}.")
        return 0
    saved = DUMP.read_text(encoding="utf-8") if DUMP.exists() else ""
    if saved == current:
        print(f"[OK] agent-app-server's public API matches {DUMP.relative_to(ROOT)}.")
        return 0
    diff = difflib.unified_diff(saved.splitlines(), current.splitlines(), "saved", "current", lineterm="", n=0)
    print("[FAIL] agent-app-server's public API changed.\n"
          "What: declarations a host may use were added, removed or changed (below).\n"
          "Why: what hosts and plugins may use only grows on purpose.\n"
          "How: make new things `internal` (@AppServerPluginApi for what plugin modules need, @InternalAppServerApi "
          "only for what another module's tests cannot do without), or, when hosts do need it, run "
          "`./gradlew apiDump` (or `python3 tools/app-server-api/api_dump.py --write`) and commit the listing.")
    print("\n".join(list(diff)[2:200]))
    return 1


if __name__ == "__main__":
    sys.exit(main())
