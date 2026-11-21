#!/usr/bin/env bash
set -euo pipefail

schema_dir="core/database/schemas"
database_dir="${schema_dir}/io.github.ioannes78.voica.database.VoicaDatabase"
previous_schema="${database_dir}/16.json"
current_schema="${database_dir}/17.json"
room_db="core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt"

test -f "$previous_schema"
test -f "$current_schema"
grep -Eq 'version\s*=\s*17' "$room_db"
grep -Fq '.addMigrations(MIGRATION_16_17)' "$room_db"
grep -Fq 'object : Migration(16, 17)' "$room_db"
grep -Fq '"version": 16' "$previous_schema"
grep -Fq '"version": 17' "$current_schema"

unexpected="$(find "$database_dir" -maxdepth 1 -type f -name '*.json' ! -name '16.json' ! -name '17.json' -print)"
if [ -n "$unexpected" ]; then
    echo "::error::Stage 17.3 Room baseline may contain only schema v16 and v17."
    printf '%s\n' "$unexpected"
    exit 1
fi

git diff --exit-code -- "$schema_dir"
test -z "$(git status --porcelain --untracked-files=all -- "$schema_dir")"

echo "Verified Stage 17.3 Room schemas v16/v17 and additive MIGRATION_16_17 boundary."
