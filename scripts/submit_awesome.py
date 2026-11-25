#!/usr/bin/env python3
"""Open the awesome-list pull requests for ShutChat.

Each target edits the file its list actually reads (some generate the README
from a data file, so editing the README would be thrown away), then opens one
pull request per repository.

    python3 scripts/submit_awesome.py --dry-run   # show the diffs, change nothing
    GITHUB_TOKEN=<pat with public_repo> python3 scripts/submit_awesome.py

The token must be able to fork third-party repositories. The session token
OpenHands injects cannot: it is scoped to the user's own repositories and the
fork call answers 403.
"""

import argparse
import base64
import difflib
import json
import os
import sys
import time
import urllib.error
import urllib.request

REPO_URL = "https://github.com/ciao22king/ShutChat"
BRANCH = "add-shutchat"
DISCLOSURE = "This pull request was created by an AI agent (OpenHands) on behalf of the project's author."


def api(token, method, path, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=data,
        method=method,
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "User-Agent": "shutchat-submit",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        raise RuntimeError(f"{method} {path} -> {e.code}: {body[:300]}") from None


def raw(repo, ref, path):
    url = f"https://raw.githubusercontent.com/{repo}/{ref}/{path}"
    with urllib.request.urlopen(url) as r:
        return r.read().decode()


def edit_json_projects(text, group_key, project):
    data = json.loads(text)
    for group in data:
        if group.get("key") == group_key:
            if project["github_url"] in {p.get("github_url") for p in group["projects"]}:
                return None
            group["projects"].append(project)
            out = json.dumps(data, indent=2, ensure_ascii=False)
            return out + ("\n" if text.endswith("\n") else "")
    raise SystemExit(f"group {group_key} not found")


def edit_markdown_table(text, heading, row):
    lines = text.split("\n")
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == heading)
    except StopIteration:
        raise SystemExit(f"heading {heading} not found") from None
    end = next(i for i in range(start + 1, len(lines)) if lines[i].startswith("## "))
    table_end = None
    for i in range(start, end):
        if lines[i].startswith("|"):
            table_end = i
    if table_end is None:
        raise SystemExit(f"no table under {heading}")
    if row in lines[start:end]:
        return None
    lines.insert(table_end + 1, row)
    return "\n".join(lines)


def edit_yaml_append(text, entry):
    if "repo: ciao22king/ShutChat" in text:
        return None
    return text.rstrip("\n") + "\n\n" + entry.rstrip("\n") + "\n"


TARGETS = [
    {
        "repo": "androiddevnotes/awesome-android-kotlin-apps",
        "path": "input_data.json",
        "apply": lambda t: edit_json_projects(
            t,
            "$JETPACK_COMPOSE_REPOS",
            {
                "github_url": REPO_URL,
                "stack": "Jetpack Compose, Coroutines, Room, DataStore, Kotlin Serialization",
            },
        ),
        "title": "Add ShutChat, a Jetpack Compose P2P messenger",
        "body": (
            f"{DISCLOSURE}\n\n"
            "ShutChat is an Android and Linux messenger with no server and no account. "
            "Two devices agree on a key and talk directly over the local network, "
            "Bluetooth or an encrypted relay; messages, files and calls are end to end.\n\n"
            f"- Source: {REPO_URL}\n"
            "- License: Apache-2.0\n"
            "- UI: Jetpack Compose, Material 3\n"
            "- Stack: Coroutines, Room, DataStore, Kotlin Serialization, WebRTC\n\n"
            "Added to the Jetpack Compose group in `input_data.json`, which is where "
            "the contributing guide asks for new entries."
        ),
    },
    {
        "repo": "ivon852/awesome-foss-android-apps",
        "path": "README.md",
        "apply": lambda t: edit_markdown_table(
            t,
            "## 通訊軟體",
            "|[ShutChat](https://github.com/ciao22king/ShutChat)|無伺服器、無帳號的點對點通訊軟體，"
            "透過區域網路、藍牙或加密中繼傳送訊息、檔案與通話。|",
        ),
        "title": "新增 ShutChat：無伺服器、無帳號的點對點通訊軟體",
        "body": (
            f"{DISCLOSURE}\n\n"
            "ShutChat 是 Android 與 Linux 的點對點通訊軟體，沒有伺服器也沒有帳號。"
            "兩台裝置交換一組代碼後直接通訊，可用區域網路、藍牙或加密中繼，"
            "訊息、檔案與通話都是端對端加密。\n\n"
            f"- 原始碼：{REPO_URL}\n"
            "- 授權：Apache-2.0\n"
            "- 平台：Android 8.0 以上，另有 Linux 桌面版\n\n"
            "新增於「通訊軟體」表格。"
        ),
    },
    {
        "repo": "pronzzz/awesome-android-foss",
        "path": "data/apps.yml",
        "apply": lambda t: edit_yaml_append(
            t,
            "- name: ShutChat\n"
            "  repo: ciao22king/ShutChat\n"
            "  category: communication\n"
            "  description: Serverless, account-free P2P messenger: messages, files and calls over the local network, Bluetooth or an encrypted relay.\n"
            "  added: 2026-11-25\n",
        ),
        "title": "Add ShutChat to the communication section",
        "body": (
            f"{DISCLOSURE}\n\n"
            "ShutChat is an Android and Linux messenger with no server and no account, "
            "Apache-2.0, with a real Android build and GitHub releases.\n\n"
            f"- Source: {REPO_URL}\n\n"
            "Added to `data/apps.yml` rather than the README, which the build script "
            "generates."
        ),
    },
]


def dry_run():
    for t in TARGETS:
        before = raw(t["repo"], "HEAD", t["path"])
        after = t["apply"](before)
        print(f"===== {t['repo']}/{t['path']}")
        if after is None:
            print("already listed, nothing to do")
            continue
        diff = difflib.unified_diff(
            before.split("\n"), after.split("\n"), "before", "after", lineterm="", n=2
        )
        print("\n".join(diff))
        print()


def submit(token):
    user = api(token, "GET", "/user")["login"]
    for t in TARGETS:
        repo = t["repo"]
        print(f"===== {repo}")
        fork_full = api(token, "POST", f"/repos/{repo}/forks")["full_name"]
        for _ in range(30):
            try:
                api(token, "GET", f"/repos/{fork_full}/git/ref/heads/{api(token, 'GET', f'/repos/{repo}')['default_branch']}")
                break
            except RuntimeError:
                time.sleep(2)
        else:
            raise SystemExit(f"fork {fork_full} never became ready")

        base = api(token, "GET", f"/repos/{repo}")["default_branch"]
        head = api(token, "GET", f"/repos/{fork_full}/git/ref/heads/{base}")["object"]["sha"]
        try:
            api(token, "DELETE", f"/repos/{fork_full}/git/refs/heads/{BRANCH}")
        except RuntimeError:
            pass
        api(token, "POST", f"/repos/{fork_full}/git/refs", {"ref": f"refs/heads/{BRANCH}", "sha": head})

        current = api(token, "GET", f"/repos/{fork_full}/contents/{t['path']}?ref={BRANCH}")
        before = base64.b64decode(current["content"]).decode()
        after = t["apply"](before)
        if after is None:
            print("  already listed, skipped")
            continue
        api(token, "PUT", f"/repos/{fork_full}/contents/{t['path']}", {
            "message": t["title"],
            "content": base64.b64encode(after.encode()).decode(),
            "sha": current["sha"],
            "branch": BRANCH,
        })
        pr = api(token, "POST", f"/repos/{repo}/pulls", {
            "title": t["title"],
            "head": f"{user}:{BRANCH}",
            "base": base,
            "body": t["body"],
        })
        print("  " + pr["html_url"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.dry_run:
        dry_run()
        return
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        sys.exit("set GITHUB_TOKEN to a token that can fork third-party repositories")
    submit(token)


if __name__ == "__main__":
    main()
