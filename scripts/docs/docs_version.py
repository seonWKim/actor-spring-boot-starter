"""The released version the docs show, kept in one place: gradle.properties.

    python3 scripts/docs/docs_version.py check      # every artifact version in the docs is gradle.properties'
    python3 scripts/docs/docs_version.py set 1.2.0  # at release: rewrite them, and turn "new in the next
                                                     # release" markers into "since 1.2.0"

Docs describe main. API that is on main but not in the last release is marked "*new in the next release*";
the release turns the marker into "*since <version>*". Python 3 stdlib only.
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# io.github.seonwkim:actor-x:1.2.3 (Gradle) and <artifactId>actor-x</artifactId> ... <version>1.2.3</version> (Maven)
GRADLE = re.compile(r"(io\.github\.seonwkim:actor-[\w-]+:)(\d+\.\d+\.\d+)")
MAVEN = re.compile(r"(<artifactId>actor-[\w-]+</artifactId>\s*<version>)(\d+\.\d+\.\d+)(</version>)")
MARKER = re.compile(r"\*new in the next release")


def doc_files(root=ROOT):
    files = [os.path.join(root, "README.md")]
    files += glob.glob(os.path.join(root, "mkdocs", "docs", "**", "*.md"), recursive=True)
    return [f for f in files if os.path.exists(f)]


def released_version(root=ROOT):
    with open(os.path.join(root, "gradle.properties"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("version="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("no version= in gradle.properties")


def versions_in(text):
    return [m.group(2) for m in GRADLE.finditer(text)] + [m.group(2) for m in MAVEN.finditer(text)]


def check(root=ROOT):
    expected = released_version(root)
    wrong = []
    for path in doc_files(root):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for version in versions_in(text):
            if version != expected:
                wrong.append("%s: %s" % (os.path.relpath(path, root), version))
    for line in wrong:
        print("%s, expected %s (gradle.properties)" % (line, expected), file=sys.stderr)
    return 1 if wrong else 0


def set_version(new, root=ROOT):
    for path in doc_files(root):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        updated = GRADLE.sub(lambda m: m.group(1) + new, text)
        updated = MAVEN.sub(lambda m: m.group(1) + new + m.group(3), updated)
        updated = MARKER.sub("*since " + new, updated)
        if updated != text:
            with open(path, "w", encoding="utf-8") as f:
                f.write(updated)


if __name__ == "__main__":
    if sys.argv[1:2] == ["check"]:
        sys.exit(check())
    if sys.argv[1:2] == ["set"] and len(sys.argv) == 3:
        set_version(sys.argv[2])
        sys.exit(0)
    raise SystemExit(__doc__)
