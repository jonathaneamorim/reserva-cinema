#!/usr/bin/env bash
# TESTE 5: consulta STATUS via UDP (com timeout) e compara com TCP.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh

PID_SERVIDOR=""
cleanup() {
    matar_pid "$PID_SERVIDOR"
}
trap cleanup EXIT

garantir_build

titulo "TESTE 5: STATUS via UDP e comparacao TCP x UDP"

java -cp out cinema.servidor.Servidor --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
    --papel=primaria --sync=true --delay-ms=10 > logs/teste5_servidor.log 2>&1 &
PID_SERVIDOR=$!
aguardar_tcp localhost 5000
sleep 0.3

echo "--- Consulta unica de STATUS via UDP (com timeout de 1s) ---"
java -cp out cinema.cliente.ClienteStatus --host=localhost --porta-udp=5100 --timeout-ms=1000

echo ""
echo "--- O que acontece se a porta UDP estiver errada (simula perda/indisponibilidade) ---"
java -cp out cinema.cliente.ClienteStatus --host=localhost --porta-udp=9999 --timeout-ms=500

echo ""
echo "--- Comparacao TCP x UDP (5 repeticoes) ---"
java -cp out cinema.cliente.ClienteStatus --host=localhost --porta-udp=5100 --porta-tcp=5000 --modo=comparar --repeticoes=5

trap - EXIT
matar_pid "$PID_SERVIDOR"
