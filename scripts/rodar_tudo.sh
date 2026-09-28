#!/usr/bin/env bash
# Executa todos os TESTES em sequencia. Falha (exit != 0) se algum veredito
# esperado nao ocorrer:
#   - TESTE 3 (sem sync) deve mostrar CONDICAO DE CORRIDA DETECTADA nos 2 cenarios
#   - TESTE 4 (com sync) deve mostrar CONSISTENTE (30/30 e 10 OK) nos 2 cenarios
#   - TESTE 6 deve passar em todas as suas verificacoes internas
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/_lib.sh

export MODO_AUTO=1
mkdir -p logs
FALHOU=0

titulo "RODAR TUDO: executando TESTE 1 a TESTE 6 em sequencia"

echo ""
bash scripts/teste1_replicas_e_clientes.sh
[ $? -eq 0 ] || { echo "TESTE 1 falhou"; FALHOU=1; }

echo ""
bash scripts/teste2_30_requisicoes.sh
[ $? -eq 0 ] || { echo "TESTE 2 falhou"; FALHOU=1; }

echo ""
SAIDA_T3=$(bash scripts/teste3_sem_sync.sh)
echo "$SAIDA_T3"
QTD_CORRIDA=$(echo "$SAIDA_T3" | grep -c "CONDICAO DE CORRIDA DETECTADA")
if [ "$QTD_CORRIDA" -ge 2 ]; then
    echo ">>> TESTE 3 OK: condicao de corrida detectada nos 2 cenarios, como esperado."
else
    echo ">>> TESTE 3 FALHOU: esperava 2 ocorrencias de 'CONDICAO DE CORRIDA DETECTADA', achou $QTD_CORRIDA."
    FALHOU=1
fi

echo ""
SAIDA_T4=$(bash scripts/teste4_com_sync.sh)
echo "$SAIDA_T4"
QTD_CONSISTENTE=$(echo "$SAIDA_T4" | grep -c "^VEREDITO: CONSISTENTE")
if [ "$QTD_CONSISTENTE" -ge 2 ]; then
    echo ">>> TESTE 4 OK: resultado CONSISTENTE nos 2 cenarios, como esperado."
else
    echo ">>> TESTE 4 FALHOU: esperava 2 ocorrencias de 'VEREDITO: CONSISTENTE', achou $QTD_CONSISTENTE."
    FALHOU=1
fi

echo ""
bash scripts/teste5_status_udp.sh
[ $? -eq 0 ] || { echo "TESTE 5 falhou"; FALHOU=1; }

echo ""
bash scripts/teste6_falhas.sh
[ $? -eq 0 ] || { echo "TESTE 6 falhou"; FALHOU=1; }

echo ""
if [ "$FALHOU" -eq 0 ]; then
    titulo "RODAR TUDO: TODOS OS TESTES PASSARAM"
    exit 0
else
    titulo "RODAR TUDO: PELO MENOS UM TESTE FALHOU"
    exit 1
fi
