#!/usr/bin/env bash
set -euo pipefail

schema_dir="core/database/schemas"
database_dir="${schema_dir}/io.github.ioannes78.voica.database.VoicaDatabase"
previous_schema="${database_dir}/15.json"

test -f "$previous_schema"
grep -Fq 'version = 16' core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt
grep -Fq '"version": 15' "$previous_schema"

unexpected="$(find "$database_dir" -maxdepth 1 -type f -name '*.json' ! -name '15.json' -print)"
if [ -n "$unexpected" ]; then
    echo "::error::Room v16 capture must start from the committed v15 clean baseline."
    printf '%s\n' "$unexpected"
    exit 1
fi

git diff --exit-code -- "$schema_dir"
test -z "$(git status --porcelain --untracked-files=all -- "$schema_dir")"

echo "Room v16 source is ready for compiler-generated schema capture; committed v15 baseline remains unchanged until capture completes."
