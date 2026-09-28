#!/usr/bin/env bash
# TESTE 3: produz e registra a condicao de corrida (delega ao experimento 1).
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh
titulo "TESTE 3: producao da condicao de corrida (SEM sincronizacao)"
bash scripts/experimento1_sem_sync.sh
