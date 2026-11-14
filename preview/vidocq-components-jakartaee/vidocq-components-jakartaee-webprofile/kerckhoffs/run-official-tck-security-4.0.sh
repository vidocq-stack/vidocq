#!/usr/bin/env bash
# Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
# SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
#
# Official Jakarta Security 4.0 TCK on Kerckhoffs (PLAN.md "TCK plan", milestone K0).
#
# SCAFFOLD: the runner does not exist yet. This script refuses to run and exits non-zero, so that
# a green exit can never be read as a passing suite. K0 replaces the refusal with the real run.
set -euo pipefail

cd "$(dirname "$0")"
echo "The official Jakarta Security 4.0 TCK runner is not wired yet (PLAN.md, milestone K0)." >&2
exit 2
