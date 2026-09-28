#!/usr/bin/env bash
# Compila todo o projeto com javac puro (sem Maven/Gradle) para a pasta out/.
set -euo pipefail

DIR_RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR_RAIZ"

rm -rf out
mkdir -p out

echo "Compilando fontes em src/ para out/ ..."
LISTA_FONTES="out/.fontes.txt"
find src -name "*.java" > "$LISTA_FONTES"
javac -encoding UTF-8 --release 17 -Xlint:all -d out @"$LISTA_FONTES"
rm -f "$LISTA_FONTES"

echo "Build concluido com sucesso. Classes em out/."
