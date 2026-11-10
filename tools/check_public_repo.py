#!/usr/bin/env python3
"""Check tracked public files and local Markdown links without network access."""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT, text=True).strip("\0").split("\0")
names = [name for name in names if name]
errors = []
secret_patterns = [
    re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})"),
    re.compile(r"-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----\s+[A-Za-z0-9+/=\s]{40,}-----END"),
    re.compile(r"\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}"),
]
for name in names:
    path = ROOT / name
    if path.suffix.lower() in {".keystore", ".jks", ".p8", ".pem", ".key", ".cookies"} or path.name == ".env" or path.name.startswith(".env."):
        errors.append(f"Private credential file must not be tracked: {name}")
    if name.startswith(("private/", "work/", "dist/", "build-manual/", "tools/dependencies/repository/", "tools/native-qa/lib/")):
        errors.append(f"Local output/cache must not be tracked: {name}")
    if name.startswith("tools/dependencies/lib/") and name != "tools/dependencies/lib/dependencies.json":
        errors.append(f"Dependency binary/cache must not be tracked: {name}")
    if not path.is_file():
        errors.append(f"Tracked file missing: {name}")
        continue
    if path.suffix.lower() in {".py", ".java", ".md", ".json", ".xml", ".yml", ".yaml", ".txt"}:
        content = path.read_text(encoding="utf-8", errors="replace")
        for pattern in secret_patterns:
            if pattern.search(content):
                errors.append(f"Possible credential in {name}; inspect locally (value suppressed)")
    if path.suffix.lower() != ".md":
        continue
    content = path.read_text(encoding="utf-8")
    # Ignore code blocks and inline example syntax, then inspect actual links/images.
    content = re.sub(r"```.*?```", "", content, flags=re.S)
    links = re.findall(r"!?\[[^]\n]*\]\(([^)]+)\)", content)
    links += re.findall(r'(?:src|href)="([^"<>]+)"', content)
    for raw in links:
        target = raw.strip().split(' "', 1)[0].strip("<>")
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc or not parsed.path or target.startswith("#"):
            continue
        resolved = (path.parent / unquote(parsed.path)).resolve()
        try:
            resolved.relative_to(ROOT)
        except ValueError:
            errors.append(f"Link leaves repository: {name}: {target}")
            continue
        if not resolved.exists():
            errors.append(f"Missing local link: {name}: {target}")

if not names:
    errors.append("No tracked files. Stage the intended public files before checking.")
if errors:
    print("\n".join(sorted(set(errors))))
    sys.exit(1)
print(f"Public-file and local-link checks passed: {len(names)} tracked files")
