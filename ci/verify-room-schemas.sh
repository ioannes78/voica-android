#!/usr/bin/env bash
set -euo pipefail

schema_dir="core/database/schemas"
database_dir="${schema_dir}/io.github.ioannes78.voica.database.VoicaDatabase"

for version in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do
    test -f "${database_dir}/${version}.json"
done

grep -Fq 'version = 15' core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt

git diff --exit-code -- "${schema_dir}"
test -z "$(git status --porcelain --untracked-files=all -- "${schema_dir}")"

echo "Room v15 source is ready for compiler-generated schema capture; committed lineage 1..14 remains unchanged for this temporary capture commit."
