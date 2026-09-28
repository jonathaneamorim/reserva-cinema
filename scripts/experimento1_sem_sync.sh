#!/usr/bin/env bash
# Experimento 1: roda os cenarios A e B (30 requisicoes concorrentes cada)
# contra um servidor SEM sincronizacao (--sync=false), para produzir e
# registrar a condicao de corrida. Grava tudo em logs/experimento1_sem_sync.log.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh

DELAY_MS="${1:-10}"
LOG="logs/experimento1_sem_sync.log"
mkdir -p logs
: > "$LOG"

garantir_build

PID_SERVIDOR=""
cleanup() {
    matar_pid "$PID_SERVIDOR"
}
trap cleanup EXIT

executar_cenario() {
    local cenario="$1" porta="$2"
    titulo "Experimento 1 (SEM sync) - Cenario $cenario - delay-ms=$DELAY_MS" | tee -a "$LOG"

    # Cada cenario usa um servidor novo (estado limpo de 50 assentos livres).
    # Portas diferentes por cenario evitam qualquer race de bind/TIME_WAIT
    # ao reiniciar o servidor rapidamente entre um cenario e outro.
    java -cp out cinema.servidor.Servidor --id=A --porta="$porta" --porta-udp="$((porta + 100))" \
        --porta-repl="$((porta + 1000))" --papel=primaria --sync=false --delay-ms="$DELAY_MS" \
        >> "$LOG" 2>&1 &
    PID_SERVIDOR=$!
    aguardar_tcp localhost "$porta"

    java -cp out cinema.cliente.ClienteTeste --modo="$cenario" --host=localhost --porta="$porta" | tee -a "$LOG"

    matar_pid "$PID_SERVIDOR"
    PID_SERVIDOR=""
}

executar_cenario A 5000
executar_cenario B 5010

trap - EXIT
echo "" | tee -a "$LOG"
echo "Experimento 1 concluido. Log completo em $LOG" | tee -a "$LOG"
