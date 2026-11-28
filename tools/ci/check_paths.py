"""Check whether committed PR changes meet the appendix A.4 low-risk rules."""
import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter
from urllib.parse import urlsplit


READMES = {"README.md", "frontend/README.md", "tools/text-prep/README.md"}
RASTER_EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp")
TEST_PATH = re.compile(
    r"(?:tools/text-prep/tests/[^/]+\.rs|"
    r"billing-api/src/test/java/jp/smartbrief/billing/catalog/(?:[^/]+/)*[^/]*Test\.java)\Z"
)
# Character classes express literal tokens without adding them to this file.
SUPPRESSION = re.compile(
    rb"[@]SuppressWarnings|[@]Disabled|eslint[-]disable|[@]ts-ignore|[@]ts-expect-error|"
    rb"dangerouslySetInner[H]TML|\.skip\(|\.only\(|[/]/nolint|t\.Skip|"
    rb"#\[allow\(|#\[ignore\]|nosem[g]rep|gitleaks[:]allow"
)
EXTERNAL_URL = re.compile(
    rb"(?i)(?<![\w/])(?:(?:[a-z][a-z0-9+.-]*:)?//[^\s<>'\"()\[\]]+|"
    rb"www\.[^\s<>'\"()\[\]]+|mailto:[^\s<>'\"()\[\]]+)"
)
ENVIRONMENT_HOST = re.compile(rb"(?i)(?<![\w.-])(?:[\w-]+\.)*smartbrief\.jp(?![\w.-])")


def git(*args):
    return subprocess.check_output(["git", *args])


def changed_entries(base, head):
    fields = iter(git(
        "diff", "--raw", "-z", "--no-relative", "--find-renames",
        "--no-ext-diff", base, head, "--",
    ).split(b"\0"))
    for metadata in fields:
        if not metadata:
            break
        old_mode, new_mode, _, _, status = metadata[1:].decode("ascii").split()
        path = os.fsdecode(next(fields))
        paths = [path]
        if status.startswith(("R", "C")):
            paths.append(os.fsdecode(next(fields)))
        yield old_mode, new_mode, status, paths


def patch_lines(base, head, *paths):
    patch = git(
        "--literal-pathspecs", "diff", "--no-relative", "--no-color",
        "--no-ext-diff", "--no-textconv", "--text", "--unified=0", "--find-renames",
        base, head, "--", *paths,
    )
    in_hunk = False
    for line in patch.split(b"\n"):
        if line.startswith(b"diff --git "):
            in_hunk = False
        elif line.startswith(b"@@ "):
            in_hunk = True
        elif in_hunk and line.startswith((b"+", b"-")):
            yield line[:1], line[1:]


def external_urls(line):
    for match in EXTERNAL_URL.finditer(line):
        url = match.group().rstrip(b".,;")
        if url.lower().startswith(b"mailto:"):
            yield url
            continue
        candidate = url if b"://" in url or url.startswith(b"//") else b"//" + url
        try:
            host = urlsplit(candidate.decode("utf-8", "replace")).hostname
        except ValueError:
            yield url
            continue
        if url.startswith(b"//") and host and "." not in host and ":" not in host:
            if match.start() == 0 or line[match.start() - 1] not in b"(<\"\x27":
                continue
        if host and host.lower().rstrip(".") not in {"localhost", "127.0.0.1", "::1"}:
            yield url


def added_doc_reference(base, head, paths):
    added = Counter()
    removed = Counter()
    for sign, line in patch_lines(base, head, *paths):
        if sign == b"+" and ENVIRONMENT_HOST.search(line):
            return True
        (added if sign == b"+" else removed).update(external_urls(line))
    return bool(added - removed)


def classify_entry(base, head, old_mode, new_mode, status, paths):
    if any(mode not in {"000000", "100644"} for mode in (old_mode, new_mode)):
        return "non-low", "symbolic link, executable, or other nonstandard file mode"
    if all(path in READMES for path in paths):
        if added_doc_reference(base, head, paths):
            return "non-low", "new external URL or environment hostname"
        return "low", "allowed documentation"
    if all(path.startswith("docs/images/") and path.lower().endswith(RASTER_EXTENSIONS)
           for path in paths):
        return "low", "allowed raster image"
    if status == "A" and len(paths) == 1 and TEST_PATH.fullmatch(paths[0]):
        return "low", "new test in an allowed path"
    return "non-low", "change outside the low-risk rules"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", help="pull request base commit")
    parser.add_argument("head", help="pull request head commit")
    args = parser.parse_args()

    os.chdir(os.fsdecode(git("rev-parse", "--show-toplevel").rstrip(b"\r\n")))
    head = git("rev-parse", "--verify", "--end-of-options", args.head + "^{commit}").strip().decode("ascii")
    base_tip = git("rev-parse", "--verify", "--end-of-options", args.base + "^{commit}").strip().decode("ascii")
    base = git("merge-base", base_tip, head).strip().decode("ascii")

    is_low = True
    for old_mode, new_mode, status, paths in changed_entries(base, head):
        entry_risk, reason = classify_entry(base, head, old_mode, new_mode, status, paths)
        is_low = is_low and entry_risk == "low"
        print(json.dumps({
            "status": status, "paths": paths, "risk": entry_risk, "reason": reason,
        }))

    matches = Counter(
        match.group().decode("ascii")
        for sign, line in patch_lines(base, head)
        if sign == b"+"
        for match in SUPPRESSION.finditer(line)
    )
    if matches:
        is_low = False
        print("Added suppressions or risky constructs: " + json.dumps(matches, sort_keys=True))

    print("PR risk: " + ("low" if is_low else "non-low"))
    return 1 if matches else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"Unable to classify pull request: {error}", file=sys.stderr)
        sys.exit(2)
