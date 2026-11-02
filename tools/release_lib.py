"""Versioning and publishing for DobarBoiler releases (rules: docs/release.md, CLAUDE.md "Releases").

Modelled on GitGud's release flow: every PR description carries a `Release:` block, a release takes the
biggest step among the PRs merged since the previous `vX.Y.Z` tag, and the published history
(versions.json) is what the app shows under Podešavanja → Verzije.

Pure functions plus a small CLI used by tools/release-apk.sh:

    python3 tools/release_lib.py plan  --base-version 0.1.0 --prs prs.json --commits commits.txt
    python3 tools/release_lib.py publish --out-dir DIR --entry entry.json
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

BUMPS = ("none", "patch", "minor", "major")
MAX_BULLETS = 3


@dataclass
class ReleaseBlock:
    bump: str
    changelog: list[str] = field(default_factory=list)


def parse_release_block(body: str | None) -> ReleaseBlock | None:
    """`Release: minor` + up to three `- bullet` lines after it (an optional `Changelog:` label in between).

    Returns None when the PR has no valid block; the bullets end at the first line that is not a bullet.
    """
    lines = (body or "").splitlines()
    for i, line in enumerate(lines):
        m = re.fullmatch(r"\s*Release:\s*(\w+)\s*", line)
        if not m:
            continue
        bump = m.group(1).lower()
        if bump not in BUMPS:
            return None
        bullets: list[str] = []
        for nxt in lines[i + 1:]:
            s = nxt.strip()
            if not s or re.fullmatch(r"Changelog:?", s, re.IGNORECASE):
                if bullets:
                    break
                continue
            b = re.fullmatch(r"[-*]\s+(.+)", s)
            if not b:
                break
            bullets.append(b.group(1).strip())
        return ReleaseBlock(bump, bullets[:MAX_BULLETS])
    return None


def parse_version(v: str) -> tuple[int, int, int]:
    m = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", v.strip())
    if not m:
        raise ValueError(f"not a version: {v!r}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def bump_version(base: str, bump: str) -> str:
    major, minor, patch = parse_version(base)
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    if bump == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise ValueError(f"no version for bump {bump!r}")


def plan(base_version: str, prs: list[dict], commits: set[str]) -> dict:
    """Next release from the PRs whose merge (or head) commit lies in the release range.

    A PR without a valid block counts as `patch` with its title as the changelog (and a warning), so a
    forgotten block never hides a change. `none` PRs ship nothing and stay out of the changelog.
    """
    included, warnings = [], []
    for pr in sorted(prs, key=lambda p: p.get("mergedAt") or ""):
        oids = {(pr.get("mergeCommit") or {}).get("oid"), pr.get("headRefOid")}
        if not oids & commits:
            continue
        block = parse_release_block(pr.get("body"))
        if block is None:
            warnings.append(f"PR #{pr['number']} nema Release blok, računam ga kao patch")
            block = ReleaseBlock("patch", [pr.get("title", "").strip()])
        elif block.bump != "none" and not block.changelog:
            block = ReleaseBlock(block.bump, [pr.get("title", "").strip()])
        included.append((pr, block))

    biggest = max((b.bump for _, b in included), key=BUMPS.index, default="none")
    changelog = [
        {"pr": pr["number"], "text": text}
        for pr, block in included if block.bump != "none"
        for text in block.changelog
    ]
    return {
        "base": base_version,
        "bump": biggest,
        "version": None if biggest == "none" else bump_version(base_version, biggest),
        "prs": [{"number": pr["number"], "title": pr.get("title", ""), "bump": b.bump} for pr, b in included],
        "changelog": changelog,
        "warnings": warnings,
    }


def add_version(history: list[dict], entry: dict) -> list[dict]:
    """versions.json: newest first, one row per version (a re-published version replaces its row)."""
    rest = [h for h in history if h.get("version") != entry["version"]]
    return [entry] + rest


def render_index(latest: dict, history: list[dict]) -> str:
    def changes(e: dict) -> str:
        items = "".join(f"<li>{html.escape(c['text'])}</li>" for c in e.get("changelog", []))
        return f"<ul>{items}</ul>" if items else ""

    rows = "".join(
        f"<h3>{html.escape(e['version'])} <small>{html.escape(e.get('date', ''))}"
        f"{' · probna' if e.get('test') else ''}</small></h3>{changes(e)}"
        + (f"<p><small><a href=\"{html.escape(e['file'])}\">{html.escape(e['file'])}</a></small></p>"
           if e.get("file_kept") else "")
        for e in history
    )
    return f"""<!doctype html>
<html lang="sr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DobarBoiler {html.escape(latest['version'])}</title>
<style>
body{{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;color:#1b1b1f}}
a.btn{{display:block;text-align:center;background:#1e5aa8;color:#fff;padding:1rem;border-radius:.75rem;
text-decoration:none;font-size:1.2rem;margin:1rem 0}}
li{{margin:.3rem 0}} small{{color:#666}} h3{{margin-bottom:.2rem}}
</style></head><body>
<h1>DobarBoiler</h1>
<a class="btn" href="{html.escape(latest['file'])}">Instaliraj {html.escape(latest['version'])}</a>
<p><small>Posle prve instalacije aplikacija sama nudi nove verzije (Podešavanja → Verzije).</small></p>
<h2>Istorija verzija</h2>
{rows}
<p><small>Starija verzija se instalira samo posle deinstalacije, a to briše podatke aplikacije.</small></p>
</body></html>
"""


def publish(out_dir: Path, entry: dict) -> None:
    """Writes latest.json, versions.json and index.html for a freshly copied APK (entry['file'])."""
    versions_path = out_dir / "versions.json"
    history = json.loads(versions_path.read_text()) if versions_path.exists() else []
    history = add_version(history, entry)
    kept = {p.name for p in out_dir.glob("dobarboiler-*.apk")}
    for e in history:
        e["file_kept"] = e.get("file") in kept
    _write_json(out_dir / "versions.json", history)
    _write_json(out_dir / "latest.json", entry)
    tmp = out_dir / "index.html.tmp"
    tmp.write_text(render_index(entry, history))
    tmp.replace(out_dir / "index.html")


def _write_json(path: Path, data) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    tmp.replace(path)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--base-version", required=True)
    p.add_argument("--prs", required=True, help="gh pr list --json number,title,body,mergedAt,mergeCommit,headRefOid")
    p.add_argument("--commits", required=True, help="one commit oid per line (git rev-list base..ref)")
    q = sub.add_parser("publish")
    q.add_argument("--out-dir", required=True)
    q.add_argument("--entry", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "plan":
        prs = json.loads(Path(a.prs).read_text())
        commits = {c.strip() for c in Path(a.commits).read_text().split() if c.strip()}
        print(json.dumps(plan(a.base_version, prs, commits), ensure_ascii=False, indent=2))
    else:
        publish(Path(a.out_dir), json.loads(Path(a.entry).read_text()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
