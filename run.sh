#!/usr/bin/env bash
# Compile everything under src/ into out/, then run a main class.
#   ./run.sh                          -> runs Main
#   ./run.sh tools.Advisor TicTacToe  -> runs tools.Advisor with argument "TicTacToe"
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
javac -d out $(find src -name '*.java')

MAIN="${1:-Main}"
shift || true
exec java -ea -Xmx4g -cp out "$MAIN" "$@"
