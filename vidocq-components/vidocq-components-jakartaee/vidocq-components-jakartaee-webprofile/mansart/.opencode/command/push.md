---
description: Push the current branch. The only command that publishes.
agent: lead
---
ARGS: $ARGUMENTS
Expected: <XXX>

/next commits but NEVER pushes. Pushing publishes; a human decides that.

1. git status --short. Uncommitted changes? List them and ASK before anything.
2. git log --oneline origin/HEAD..HEAD — show what will be published.
3. Every commit signed? git log --show-signature -1. Unsigned -> STOP.
4. Push the current branch to its remote. Never force. Never to main.
5. Report: branch, number of commits, remote.
