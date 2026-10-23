#!/usr/bin/env bash
set -euo pipefail

schema_dir="core/database/schemas"
database_dir="${schema_dir}/io.github.ioannes78.voica.database.VoicaDatabase"
current_schema="${database_dir}/16.json"

test -f "$current_schema"
grep -Fq 'version = 16' core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt
grep -Fq '"version": 16' "$current_schema"

unexpected="$(find "$database_dir" -maxdepth 1 -type f -name '*.json' ! -name '16.json' -print)"
if [ -n "$unexpected" ]; then
    echo "::error::Pre-release clean baseline must contain only Room schema v16."
    printf '%s\n' "$unexpected"
    exit 1
fi

git diff --exit-code -- "$schema_dir"
test -z "$(git status --porcelain --untracked-files=all -- "$schema_dir")"

echo "Verified pre-release Room clean baseline at schema v16."
