#!/usr/bin/env bash
# TESTE 4: mesmo teste, agora com sincronizacao, resultado consistente (delega ao experimento 2).
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh
titulo "TESTE 4: correcao com sincronizacao (resultado consistente)"
bash scripts/experimento2_com_sync.sh
