#!/usr/bin/env bash
set -euo pipefail

schema_dir="core/database/schemas"
database_dir="${schema_dir}/io.github.ioannes78.voica.database.VoicaDatabase"
previous_schema="${database_dir}/16.json"
current_schema="${database_dir}/17.json"

test -f "$previous_schema"
grep -Fq 'version = 17' core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt
grep -Fq '"version": 16' "$previous_schema"

# Stage 17.3 P2 bootstrap: the Room compiler produces the authoritative v17 schema. The temporary
# schema-capture workflow records that generated file; once it is committed this branch is tightened
# again to require v17 at checkout. Do not hand-author an identity hash.
if [ -f "$current_schema" ]; then
    grep -Fq '"version": 17' "$current_schema"
fi

unexpected="$(find "$database_dir" -maxdepth 1 -type f -name '*.json' ! -name '16.json' ! -name '17.json' -print)"
if [ -n "$unexpected" ]; then
    echo "::error::Stage 17.3 Room baseline may contain only schema v16 and v17."
    printf '%s\n' "$unexpected"
    exit 1
fi

git diff --exit-code -- "$schema_dir"
test -z "$(git status --porcelain --untracked-files=all -- "$schema_dir")"

echo "Verified Stage 17.3 Room transition boundary at v16 -> v17 (compiler schema capture may still be pending)."
