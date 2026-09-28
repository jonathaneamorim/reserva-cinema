#!/usr/bin/env bash
# TESTE 1: sobe a replica A (primaria) e a replica B (backup), sincronizadas
# por snapshot, e conecta 3 clientes simultaneamente para mostrar o servidor
# atendendo varias conexoes ao mesmo tempo.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh

garantir_build

PID_A="" PID_B=""
cleanup() {
    matar_pid "$PID_A"
    matar_pid "$PID_B"
}
trap cleanup EXIT

titulo "TESTE 1: 2 replicas + 3 clientes simultaneos"

echo "Subindo replica A (primaria) na porta 5000 (UDP 5100, replicacao 6000)..."
java -cp out cinema.servidor.Servidor --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
    --peer=localhost:6001 --papel=primaria --sync=true --delay-ms=10 > logs/teste1_A.log 2>&1 &
PID_A=$!
aguardar_tcp localhost 5000

echo "Subindo replica B (backup) na porta 5001 (UDP 5101, replicacao 6001)..."
java -cp out cinema.servidor.Servidor --id=B --porta=5001 --porta-udp=5101 --porta-repl=6001 \
    --peer=localhost:6000 --papel=backup --sync=true --delay-ms=10 > logs/teste1_B.log 2>&1 &
PID_B=$!
aguardar_tcp localhost 5001
sleep 0.5

echo ""
echo "--- Estado inicial (via ADMIN|CONTADORES) ---"
echo -n "Replica A: "; printf 'ADMIN|CONTADORES\nSAIR\n' | java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5000 | grep CONTADORES
echo -n "Replica B: "; printf 'ADMIN|CONTADORES\nSAIR\n' | java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5001 | grep CONTADORES

echo ""
echo "--- Conectando 3 clientes simultaneamente na primaria (porta 5000) ---"
java -cp out cinema.cliente.ClienteTeste --modo=conectar --host=localhost --porta=5000

echo ""
echo "--- Papel de cada replica (visto no log de inicializacao) ---"
grep "Servidor 'A' iniciado\|Servidor 'B' iniciado" logs/teste1_A.log logs/teste1_B.log

trap - EXIT
matar_pid "$PID_A"
matar_pid "$PID_B"
echo ""
echo "TESTE 1 concluido: A=PRIMARIA, B=BACKUP, 3 clientes atendidos simultaneamente."
