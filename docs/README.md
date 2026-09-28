# Sistema de Reservas de Cinema - Concorrência, Sincronização e Distribuição

Trabalho da disciplina Programação Concorrente e Distribuída (IFSC Câmpus
Gaspar). Java 17 puro (só JDK, sem frameworks, sem dependências externas,
compilado com `javac`). Tema: reserva de assentos de uma sessão de cinema
(50 assentos, numerados de 1 a 50).

## Estrutura do projeto

```
src/
  cinema/servidor/  Servidor.java, ClienteHandler.java, GerenciadorAssentos.java,
                     Replicador.java, StatusUDP.java, EstadoNo.java
  cinema/cliente/   ClienteInterativo.java, ClienteTeste.java,
                     ConexaoComFailover.java, ClienteStatus.java
  cinema/comum/     Protocolo.java, Log.java
scripts/  build.sh, teste1..6_*.sh, experimento1_sem_sync.sh,
          experimento2_com_sync.sh, rodar_tudo.sh, _lib.sh
logs/     saída real dos experimentos e testes (versionada no git)
docs/     esta pasta
out/      classes compiladas (gerado pelo build.sh, não versionado)
```

## Compilando

```bash
bash scripts/build.sh
```

Compila tudo com `javac --release 17` para `out/`. Não usa Maven nem
Gradle.

## Rodando uma réplica do servidor

```bash
java -cp out cinema.servidor.Servidor \
     --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
     --peer=localhost:6001 --papel=primaria --sync=true --delay-ms=10
```

Para a segunda réplica (backup), em outro terminal:

```bash
java -cp out cinema.servidor.Servidor \
     --id=B --porta=5001 --porta-udp=5101 --porta-repl=6001 \
     --peer=localhost:6000 --papel=backup --sync=true --delay-ms=10
```

Veja todos os argumentos em `docs/protocolo.md` (seção 4). Rodando sem
`--peer`, a réplica funciona como nó único.

## Rodando os clientes

```bash
# Cliente interativo (CLI), com failover entre as 2 replicas:
java -cp out cinema.cliente.ClienteInterativo --replicas=localhost:5000,localhost:5001

# Cliente de teste de concorrencia (cenarios A/B, ou 3 conexoes simultaneas):
java -cp out cinema.cliente.ClienteTeste --modo=A --host=localhost --porta=5000
java -cp out cinema.cliente.ClienteTeste --modo=B --host=localhost --porta=5000
java -cp out cinema.cliente.ClienteTeste --modo=conectar --host=localhost --porta=5000

# Cliente UDP de status, com timeout, e comparacao TCP x UDP:
java -cp out cinema.cliente.ClienteStatus --host=localhost --porta-udp=5100
java -cp out cinema.cliente.ClienteStatus --host=localhost --porta-udp=5100 --porta-tcp=5000 --modo=comparar --repeticoes=5
```

No cliente interativo, os comandos são exatamente os do protocolo (ver
`docs/protocolo.md`): `LISTAR`, `RESERVAR|<assento>|<usuario>`,
`CANCELAR|<assento>|<usuario>`, `SAIR`.

## Rodando os testes/experimentos

Todos os scripts ficam em `scripts/`, sobem e derrubam seus próprios
servidores (guardando os PIDs), e funcionam em Linux/macOS/WSL/Git Bash.

| Script | O que faz |
|---|---|
| `scripts/build.sh` | Compila o projeto |
| `scripts/teste1_replicas_e_clientes.sh` | Sobe A (primária) + B (backup) + 3 clientes simultâneos |
| `scripts/teste2_30_requisicoes.sh` | 30 requisições concorrentes (3×10), com logs das threads |
| `scripts/teste3_sem_sync.sh` | Produz e registra a condição de corrida (chama o experimento 1) |
| `scripts/teste4_com_sync.sh` | Mesmo teste, com sincronização, resultado consistente (chama o experimento 2) |
| `scripts/teste5_status_udp.sh` | Consulta STATUS via UDP e compara com TCP |
| `scripts/teste6_falhas.sh` | Desconexão de cliente + queda e failover da réplica primária + reingresso como backup |
| `scripts/experimento1_sem_sync.sh` | Cenários A e B contra um servidor `--sync=false` |
| `scripts/experimento2_com_sync.sh` | Cenários A e B contra um servidor `--sync=true` |
| `scripts/rodar_tudo.sh` | Roda os TESTES 1 a 6 em sequência; sai com código ≠ 0 se algum veredito esperado falhar |

```bash
bash scripts/rodar_tudo.sh
```

`scripts/teste6_falhas.sh` pausa entre passos para demonstração ao vivo
(pressione ENTER para avançar); rode com `MODO_AUTO=1` para pular as pausas
(é o que `rodar_tudo.sh` faz automaticamente).

## Outros documentos

- `docs/protocolo.md` - protocolo completo, em tabelas.
- `docs/arquitetura.md` - diagramas (Mermaid e ASCII), sequência de
  RESERVAR replicado e de failover, limitações conhecidas.
- `docs/relatorio.md` - relatório técnico respondendo às 9 perguntas da
  atividade, com números reais dos experimentos.
- `docs/comparacao.md` - tabela comparativa sem/com sincronização.
- `docs/roteiro-apresentacao.md` - roteiro de 10 minutos, comando por
  comando, com respostas prontas para perguntas do professor.
- `docs/checklist-avaliacao.md` - cada item da rubrica mapeado para a prova
  correspondente no projeto.
