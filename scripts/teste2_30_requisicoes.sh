#!/usr/bin/env bash
# TESTE 2: dispara 30 requisicoes concorrentes (3 clientes x 10) contra um
# servidor com sincronizacao, mostrando nos logs do servidor as threads
# atendendo cada requisicao (uma thread por cliente).
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh

PID_SERVIDOR=""
cleanup() {
    matar_pid "$PID_SERVIDOR"
}
trap cleanup EXIT

garantir_build

titulo "TESTE 2: 30 requisicoes concorrentes (3 clientes x 10), com sincronizacao"

java -cp out cinema.servidor.Servidor --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
    --papel=primaria --sync=true --delay-ms=10 > logs/teste2_servidor.log 2>&1 &
PID_SERVIDOR=$!
aguardar_tcp localhost 5000

java -cp out cinema.cliente.ClienteTeste --modo=A --host=localhost --porta=5000

echo ""
echo "--- Trecho do log do servidor mostrando uma thread por cliente (ClienteHandler-A-N) ---"
grep "ClienteHandler" logs/teste2_servidor.log | head -15
echo "..."
echo "(log completo em logs/teste2_servidor.log)"

trap - EXIT
matar_pid "$PID_SERVIDOR"
