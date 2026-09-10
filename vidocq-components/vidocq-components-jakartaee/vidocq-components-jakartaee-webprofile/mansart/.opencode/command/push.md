---
description: Push the current branch. The only command that publishes.
agent: lead
---
ARGS: $ARGUMENTS   (<XXX>)

1. git status --short — uncommitted changes? List them, ASK, do nothing else.
2. git log --oneline @{upstream}..HEAD — show what will be published.
3. git log --show-signature -1 — unsigned? STOP.
4. Push the current branch to its remote. Never force. Never main.
5. Report: branch, commits, remote.
