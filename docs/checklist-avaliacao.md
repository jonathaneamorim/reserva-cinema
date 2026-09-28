# Checklist de avaliação

Mapeamento de cada item da rubrica (10 pontos) para o arquivo/script/log que
o comprova.

| Item da rubrica | Pontos | Onde está a prova |
|---|---|---|
| Servidor TCP + múltiplos clientes | 1,0 | `src/cinema/servidor/Servidor.java` (accept loop) + `ClienteHandler.java` (1 thread por cliente) - demonstrado em `scripts/teste1_replicas_e_clientes.sh` (3 clientes simultâneos) e `scripts/teste2_30_requisicoes.sh` (log com várias threads `ClienteHandler-A-N` atendendo em paralelo, ver `logs/teste2_servidor.log`) |
| Protocolo documentado em tabela | 0,5 | `docs/protocolo.md` (tabelas de comandos TCP, erros, UDP, replicação interna e argumentos de linha de comando) |
| Produção da condição de corrida com 30 requisições e inconsistência real em log | 2,0 | `scripts/experimento1_sem_sync.sh` / `scripts/teste3_sem_sync.sh` + `logs/experimento1_sem_sync.log` (trechos citados em `docs/relatorio.md`, pergunta 3 e 4: `totalReservas=10` em vez de 30; 30 `OK` em vez de 10) |
| Correção com sincronização, mesmo teste consistente | 2,0 | `scripts/experimento2_com_sync.sh` / `scripts/teste4_com_sync.sh` + `logs/experimento2_com_sync.log` (30/30 e 10 `OK` em todas as execuções) |
| Tabela comparativa e explicação técnica | 1,0 | `docs/comparacao.md` (tabela com números reais das duas versões) + `docs/relatorio.md` perguntas 3-5 |
| ≥2 réplicas com o mesmo estado e estratégia justificada | 1,5 | `src/cinema/servidor/Replicador.java` (snapshot + replicação síncrona) - demonstrado em `scripts/teste1_replicas_e_clientes.sh` (A e B com `ADMIN\|CONTADORES` idênticos) e justificado em `docs/relatorio.md` pergunta 6 e `docs/arquitetura.md` |
| Tolerância a falhas | 0,5 | `scripts/teste6_falhas.sh` (kill -9 da primária, heartbeat, promoção da backup, cliente reconectando sem duplicar, réplica caída voltando como backup) - com verificações automáticas e `exit 0`/`exit 1` |
| UDP + comparação TCP×UDP | 0,5 | `src/cinema/servidor/StatusUDP.java` + `src/cinema/cliente/ClienteStatus.java` - demonstrado em `scripts/teste5_status_udp.sh` (tabela de tempos TCP×UDP e demonstração de timeout sem resposta) |
| Desconexão de cliente | 0,5 | `src/cinema/servidor/ClienteHandler.java` (captura `IOException`/EOF, decrementa `AtomicInteger`, loga e mantém o servidor rodando) - demonstrado no PASSO 1 de `scripts/teste6_falhas.sh` e nos logs de `logs/experimento1_sem_sync.log` (linha do probe de `aguardar_tcp` desconectando) |
| Documentação e apresentação | 0,5 | `docs/README.md`, `docs/protocolo.md`, `docs/arquitetura.md`, `docs/relatorio.md`, `docs/roteiro-apresentacao.md`, este checklist |

## Verificação de reprodutibilidade

`scripts/rodar_tudo.sh` executa os TESTES 1 a 6 em sequência e retorna
código de saída ≠ 0 se qualquer veredito esperado não ocorrer (condição de
corrida no experimento 1, consistência no experimento 2, e todas as
verificações internas do TESTE 6). Foi executado com sucesso em 3 rodadas
consecutivas durante o desenvolvimento deste projeto.
