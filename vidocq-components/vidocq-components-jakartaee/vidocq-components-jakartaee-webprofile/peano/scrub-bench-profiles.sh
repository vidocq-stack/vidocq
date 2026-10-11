#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ! -d "$1" || -e "$2" ]]; then
    echo 'Usage: scrub-bench-profiles.sh <JMH JFR input directory> <new output directory>' >&2
    exit 1
fi
profile_input="$1"
profile_output="$2"
profile_count=0
mkdir -p "$profile_output"

for profile_recording in "$profile_input"/*/profile.jfr; do
    [[ -f "$profile_recording" ]] || continue
    profile_parent="${profile_recording%/profile.jfr}"
    profile_name="${profile_parent##*/}"
    mkdir -p "$profile_output/$profile_name"
    jfr scrub --include-events jdk.ExecutionSample,jdk.ObjectAllocationSample \
        "$profile_recording" "$profile_output/$profile_name/profile.jfr" > /dev/null
    jfr summary "$profile_output/$profile_name/profile.jfr" | awk '
        $1 ~ /^jdk\./ && $2 > 0 && $1 !~ /^jdk\.(ExecutionSample|ObjectAllocationSample|Checkpoint|Metadata)$/ { rejected=1 }
        END { exit rejected }
    '
    profile_count=$((profile_count+1))
done

if [[ $profile_count -eq 0 ]]; then
    echo 'No JMH profile.jfr recordings found.' >&2
    exit 1
fi
echo "Filtered $profile_count recordings to CPU and allocation events."
