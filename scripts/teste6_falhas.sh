#!/usr/bin/env bash
# TESTE 6: tolerancia a falhas, passo a passo.
#   1) desconecta um cliente abruptamente e mostra o servidor seguindo vivo;
#   2) derruba a replica primaria durante uma sequencia de reservas;
#   3) mostra o cliente falhando para a outra replica e continuando SEM
#      duplicar nem perder reservas confirmadas (idempotencia);
#   4) reinicia a replica caida e mostra ela voltando como BACKUP, com o
#      snapshot correto.
#
# Rode com MODO_AUTO=1 para pular as pausas interativas (usado pelo rodar_tudo.sh).
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh

MODO_AUTO="${MODO_AUTO:-0}"
pausar() {
    if [ "$MODO_AUTO" != "1" ]; then
        read -r -p ">>> $1 [ENTER para continuar] " _dummy
    else
        echo ">>> $1"
    fi
}

PID_A="" PID_B=""
cleanup() {
    matar_pid "$PID_A"
    matar_pid "$PID_B"
}
trap cleanup EXIT

garantir_build
FALHOU=0

titulo "TESTE 6: tolerancia a falhas (desconexao de cliente + failover de replica)"

rm -f logs/teste6_A.log logs/teste6_A2.log logs/teste6_B.log

echo "Subindo replica A (primaria, porta 5000) e replica B (backup, porta 5001)..."
java -cp out cinema.servidor.Servidor --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
    --peer=localhost:6001 --papel=primaria --sync=true --delay-ms=10 > logs/teste6_A.log 2>&1 &
PID_A=$!
aguardar_tcp localhost 5000

java -cp out cinema.servidor.Servidor --id=B --porta=5001 --porta-udp=5101 --porta-repl=6001 \
    --peer=localhost:6000 --papel=backup --sync=true --delay-ms=10 > logs/teste6_B.log 2>&1 &
PID_B=$!
aguardar_tcp localhost 5001
sleep 0.5

pausar "PASSO 1: vamos conectar um cliente na primaria e desconecta-lo abruptamente (sem SAIR)."

exec 3<>/dev/tcp/localhost/5000
printf 'LISTAR\n' >&3
IFS= read -r resposta <&3
echo "Cliente temporario recebeu: ${resposta:0:40}..."
exec 3<&- 3>&-   # fecha a conexao sem enviar SAIR - simula uma queda abrupta
sleep 0.3
echo "Trecho do log da primaria apos a desconexao:"
grep "desconectou sem enviar SAIR" logs/teste6_A.log | tail -1
echo -n "Servidor primaria continua respondendo? "
RESP_VIVO=$(printf 'LISTAR\nSAIR\n' | java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5000 | grep '^> LISTA')
if [ -n "$RESP_VIVO" ]; then
    echo "SIM - respondeu LISTAR normalmente."
else
    echo "NAO - FALHA!"
    FALHOU=1
fi

pausar "PASSO 2: vamos reservar o assento 30 na primaria (deve dar OK e replicar para a backup)."
RESP1=$(printf 'RESERVAR|30|leo\nSAIR\n' | java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5000 | grep '^> ' | head -1 | sed 's/^> //')
echo "Resposta da primaria: $RESP1"

pausar "PASSO 3: agora vamos MATAR a replica primaria (kill -9) e esperar o failover por heartbeat (timeout=3s)."
echo "Derrubando replica A (PID=$PID_A) ..."
kill -9 "$PID_A" 2>/dev/null
PID_A=""
echo "Aguardando deteccao de falha por heartbeat na backup (ate 5s)..."
sleep 5
if grep -q "PROMOVENDO este no" logs/teste6_B.log; then
    echo "Backup B se promoveu a PRIMARIA:"
    grep "PROMOVENDO este no" logs/teste6_B.log
else
    echo "FALHA: backup nao se promoveu a tempo!"
    FALHOU=1
fi

pausar "PASSO 4: o cliente (com lista de ambas as replicas) reenvia o assento 30 (idempotente) e reserva o 31 (novo)."
RESULTADO=$(printf 'RESERVAR|30|leo\nRESERVAR|31|leo\nADMIN|CONTADORES\nSAIR\n' \
    | java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5000,localhost:5001)
echo "$RESULTADO"
CONTADORES_LINHA=$(echo "$RESULTADO" | grep CONTADORES)
TOTAL_RESERVAS=$(echo "$CONTADORES_LINHA" | grep -o 'totalReservas=[0-9]*' | cut -d= -f2)
echo ""
echo "totalReservas apos failover e reenvio idempotente: $TOTAL_RESERVAS (esperado: 2 - assento 30 nao duplicou, assento 31 e novo)"
if [ "$TOTAL_RESERVAS" = "2" ]; then
    echo "OK: sem duplicacao e sem perda de reservas."
else
    echo "FALHA: valor inesperado de totalReservas!"
    FALHOU=1
fi

pausar "PASSO 5: reiniciando a replica A caida - ela deve entrar como BACKUP e receber o snapshot."
java -cp out cinema.servidor.Servidor --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
    --peer=localhost:6001 --papel=primaria --sync=true --delay-ms=10 > logs/teste6_A2.log 2>&1 &
PID_A=$!
aguardar_tcp localhost 5000
sleep 0.5

if grep -q "assumindo BACKUP" logs/teste6_A2.log; then
    echo "A reconheceu B como primaria e assumiu BACKUP:"
    grep "assumindo BACKUP\|Snapshot recebido" logs/teste6_A2.log
else
    echo "FALHA: A nao assumiu papel de BACKUP ao reiniciar!"
    FALHOU=1
fi

RESP_A_POS=$(printf 'ADMIN|CONTADORES\nSAIR\n' | java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5000 | grep CONTADORES)
echo "Contadores da replica A apos reingressar como backup: $RESP_A_POS"
TOTAL_A_POS=$(echo "$RESP_A_POS" | grep -o 'totalReservas=[0-9]*' | cut -d= -f2)
if [ "$TOTAL_A_POS" = "2" ]; then
    echo "OK: snapshot restaurado corretamente (totalReservas=2 igual ao da primaria atual B)."
else
    echo "FALHA: snapshot da replica A nao corresponde ao estado da primaria!"
    FALHOU=1
fi

trap - EXIT
matar_pid "$PID_A"
matar_pid "$PID_B"

echo ""
if [ "$FALHOU" -eq 0 ]; then
    titulo "TESTE 6: TODAS AS VERIFICACOES PASSARAM"
    exit 0
else
    titulo "TESTE 6: FALHOU EM UMA OU MAIS VERIFICACOES"
    exit 1
fi
