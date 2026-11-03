#!/usr/bin/env bash
# Files a failed scheduled run: opens an issue titled $1 with the body in $2,
# or, when an open issue already has exactly that title, comments on it
# instead, so a run that fails night after night keeps one issue.
# Needs gh with GH_TOKEN (issues: write). Used by task faults:issue and
# task eval:issue.
set -euo pipefail
title="${1:?usage: file-issue.sh <title> <body file>}"
body="${2:?usage: file-issue.sh <title> <body file>}"
repo="${GITHUB_REPOSITORY:-G1DO/anvil}"
open=$(gh issue list --repo "$repo" --state open --search "in:title $title" --limit 100 --json number,title)
existing=$(TITLE="$title" python3 -c '
import json, os, sys
match = [i["number"] for i in json.load(sys.stdin) if i["title"] == os.environ["TITLE"]]
print(min(match) if match else "")
' <<<"$open")
if [ -n "$existing" ]; then
  echo "file-issue: #$existing is still open with this title; commenting on it"
  gh issue comment "$existing" --repo "$repo" --body-file "$body"
else
  gh issue create --repo "$repo" --title "$title" --body-file "$body"
fi
