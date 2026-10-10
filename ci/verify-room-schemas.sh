#!/usr/bin/env bash
set -euo pipefail

schema_dir="core/database/schemas"
database_dir="${schema_dir}/io.github.ioannes78.voica.database.VoicaDatabase"

for version in 1 2 3 4 5 6 7 8 9 10 11 12; do
    test -f "${database_dir}/${version}.json"
done

grep -Fq 'version = 12' core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt
git diff --exit-code -- "${schema_dir}"
test -z "$(git status --porcelain --untracked-files=all -- "${schema_dir}")"

echo "Verified committed Room schema lineage 1..12 and current schema v12."
