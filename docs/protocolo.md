# Protocolo da aplicação

Todas as mensagens (cliente↔servidor, réplica↔réplica e o STATUS via UDP) são
texto puro, **uma mensagem por linha** (terminada em `\n`), com os campos
separados pelo caractere `|`. Não há nenhum framework de serialização: tudo é
`String.split("\\|")` de um lado e `String.join("|", ...)` do outro (ver
`cinema.comum.Protocolo`).

## 1. Comandos do cliente (TCP, porta do servidor, ex.: 5000/5001)

| Comando | Formato | Resposta em caso de sucesso | Resposta em caso de erro |
|---|---|---|---|
| Listar assentos livres | `LISTAR` | `LISTA\|1,4,7,...` (lista separada por vírgula, pode ser vazia) | `ERRO\|NAO_PRIMARIO` |
| Reservar assento | `RESERVAR\|<assento>\|<usuario>` | `OK` | `ERRO\|<motivo>` |
| Cancelar reserva | `CANCELAR\|<assento>\|<usuario>` | `OK` | `ERRO\|<motivo>` |
| Encerrar conexão | `SAIR` | `BYE` | — |
| Consultar contadores (uso do experimento/depuração) | `ADMIN\|CONTADORES` | `CONTADORES\|totalReservas=<n>\|totalRequisicoes=<n>\|assentosOcupados=<n>` | `ERRO\|COMANDO_INVALIDO` |

`<assento>` é um inteiro entre 1 e 50. `<usuario>` é qualquer string sem `|`
(ex.: um nome ou id de usuário).

### Motivos de erro (`ERRO|<motivo>`)

| Motivo | Quando ocorre |
|---|---|
| `ASSENTO_OCUPADO` | `RESERVAR` de um assento já reservado por **outro** usuário |
| `ASSENTO_INVALIDO` | Número de assento fora do intervalo 1..50, ou não numérico |
| `NAO_E_DONO` | `CANCELAR` de um assento reservado por **outro** usuário |
| `NAO_RESERVADO` | `CANCELAR` de um assento que já está livre |
| `COMANDO_INVALIDO` | Comando desconhecido ou número de campos incorreto |
| `NAO_PRIMARIO` | `LISTAR`/`RESERVAR`/`CANCELAR` enviado a uma réplica BACKUP |
| `FALHA_REPLICACAO` | Reservado no protocolo para uma futura política de replicação estrita (quorum/2-fase). **Não é usado** pela política atual: quando a backup está indisponível, a primária opera em *modo degradado* e loga o aviso em vez de recusar o cliente (ver `docs/arquitetura.md`, seção Limitações) |

### Idempotência (essencial para o failover)

- `RESERVAR|<assento>|<usuario>` quando `<assento>` **já pertence** a
  `<usuario>` retorna `OK` imediatamente, sem alterar nada. Isso é o que
  permite ao `ConexaoComFailover` reenviar com segurança o último comando
  pendente depois de trocar de réplica.
- `CANCELAR` de um assento livre retorna `ERRO|NAO_RESERVADO` (nunca duplica
  cancelamento).
- `CANCELAR` por quem não é o dono retorna `ERRO|NAO_E_DONO`.

### Exemplo de troca de mensagens

```
cliente -> RESERVAR|5|joao
servidor -> OK

cliente -> RESERVAR|5|joao      (repetido - idempotente)
servidor -> OK

cliente -> RESERVAR|5|maria
servidor -> ERRO|ASSENTO_OCUPADO

cliente -> CANCELAR|5|maria
servidor -> ERRO|NAO_E_DONO

cliente -> CANCELAR|5|joao
servidor -> OK

cliente -> SAIR
servidor -> BYE
```

## 2. UDP de status (porta própria, ex.: 5100/5101)

Datagrama enviado pelo cliente: a string `STATUS`.

Resposta do servidor:

```
STATUS|CLIENTES=<n>|REQ=<n>|<ATIVO|INATIVO>|PAPEL=<PRIMARIA|BACKUP>
```

| Campo | Significado |
|---|---|
| `CLIENTES=<n>` | Quantidade de clientes TCP conectados agora (`AtomicInteger`) |
| `REQ=<n>` | Total de requisições atendidas desde que o processo iniciou |
| `ATIVO`/`INATIVO` | Estado geral do nó |
| `PAPEL=<PRIMARIA\|BACKUP>` | Papel atual desta réplica |

UDP **não garante entrega**: o cliente `ClienteStatus` usa
`DatagramSocket.setSoTimeout(1000)` (1s por padrão) e, se nenhum datagrama de
resposta chegar dentro do timeout, imprime um aviso em vez de travar
esperando indefinidamente.

## 3. Protocolo de replicação interna (entre réplicas, porta `--porta-repl`, ex.: 6000/6001)

Canal TCP próprio, separado do canal de clientes. Cada operação abre uma
conexão curta: conecta, envia uma linha, lê a resposta, fecha.

| Comando | Formato | Resposta | Quem envia -> quem recebe |
|---|---|---|---|
| Pedir snapshot completo | `SNAPSHOT_PEDIDO` | `SNAPSHOT\|<totalReservas>;<dono_assento_1>;<dono_assento_2>;...;<dono_assento_50>` (dono vazio = livre) | backup (na inicialização) -> qualquer par |
| Replicar uma operação | `OP\|RESERVAR\|<assento>\|<usuario>` ou `OP\|CANCELAR\|<assento>\|<usuario>` | `ACK` | primária -> backup, após cada RESERVAR/CANCELAR bem-sucedido |
| Heartbeat (sonda de vida) | `HEARTBEAT` | `HEARTBEAT_ACK` | backup -> primária, a cada 1s |
| Perguntar o papel do par | `QUEM_E_PRIMARIO` | `SOU_PRIMARIO` ou `SOU_BACKUP` | qualquer réplica na inicialização -> peer configurado em `--peer` |

## 4. Argumentos de linha de comando do servidor

```
java cinema.servidor.Servidor \
     --id=A --porta=5000 --porta-udp=5100 --porta-repl=6000 \
     [--peer=host:porta-repl] --papel=primaria|backup \
     --sync=true|false --delay-ms=10
```

| Argumento | Padrão | Significado |
|---|---|---|
| `--id` | `A` | Rótulo do nó nos logs |
| `--porta` | `5000` | Porta TCP dos clientes |
| `--porta-udp` | `5100` | Porta UDP do STATUS |
| `--porta-repl` | `6000` | Porta TCP do canal de replicação |
| `--peer` | (nenhum) | `host:porta-repl` do outro nó, se houver |
| `--papel` | `primaria` | Papel inicial (pode ser sobrescrito na descoberta contra o peer - ver `docs/arquitetura.md`) |
| `--sync` | `true` | Liga/desliga a proteção da região crítica em `GerenciadorAssentos` |
| `--delay-ms` | `10` | Duração do `Thread.sleep` que simula processamento dentro da região crítica |

## 5. Cliente interativo e de teste

- `cinema.cliente.ClienteInterativo --replicas=host:porta,host:porta` - CLI que
  aceita exatamente as linhas do protocolo acima.
- `cinema.cliente.ClienteTeste --modo=A|B|conectar --host=<h> --porta=<p>` -
  harness dos cenários de concorrência (ver `docs/relatorio.md`).
- `cinema.cliente.ClienteStatus --host=<h> --porta-udp=<p> [--modo=comparar --porta-tcp=<p> --repeticoes=N]` -
  cliente UDP com timeout e o modo de comparação TCP×UDP.
