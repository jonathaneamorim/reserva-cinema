# Relatório técnico

Disciplina: Programação Concorrente e Distribuída — IFSC Câmpus Gaspar
Tema: Sistema de Reservas de Cinema (50 assentos, sessão única)

Todos os números abaixo são reais, extraídos de `logs/experimento1_sem_sync.log`,
`logs/experimento2_com_sync.log` e dos logs gerados por `scripts/teste6_falhas.sh`
(`logs/teste6_*.log`), executados neste repositório com `--delay-ms=10`.

---

## 1. O que caracteriza este sistema como distribuído?

O sistema roda como **dois processos independentes** (réplica A e réplica
B), cada um com seu próprio estado em memória, comunicando-se por rede
(sockets TCP/UDP) e não por memória compartilhada. Isso traz os problemas
clássicos de sistemas distribuídos que o projeto endereça explicitamente:

- **Não há relógio nem estado global compartilhado** - cada réplica só sabe
  o que foi comunicado explicitamente pela outra (snapshot inicial + stream
  de operações replicadas).
- **Falhas parciais são possíveis**: uma réplica pode cair enquanto a outra
  continua no ar (testado no TESTE 6).
- **Comunicação assíncrona e não confiável**: o canal de replicação e o
  UDP de status podem falhar, atrasar ou (no caso do UDP) simplesmente
  perder mensagens - o sistema precisa lidar com isso (heartbeat, timeout,
  modo degradado).
- **Clientes também são processos remotos** independentes do servidor,
  falando por um protocolo de rede (não chamada de função local).

Além disso, o sistema usa duas réplicas com uma estratégia de
**replicação primária-backup** (`src/cinema/servidor/Replicador.java`), o
que é a própria definição de um serviço distribuído tolerante a falhas.

## 2. Qual é o recurso compartilhado e qual é a região crítica no código?

**Recurso compartilhado**: o array `donoDoAssento[1..50]` e o contador
`totalReservas`, ambos campos de instância de
`src/cinema/servidor/GerenciadorAssentos.java`. Esse objeto é compartilhado
por **todas** as threads `ClienteHandler` (uma por cliente conectado).

**Região crítica**: o método privado
`GerenciadorAssentos.regiaoCriticaReservar(int assento, String usuario)`
(arquivo `src/cinema/servidor/GerenciadorAssentos.java`, a partir da linha
97), chamado por `reservar(...)` (linha ~70). Ele faz, nessa ordem:

1. lê `donoDoAssento[assento]` (check);
2. `Thread.sleep(delayMs)` (simula processamento - é a janela de corrida);
3. grava `donoDoAssento[assento] = usuario` (act);
4. lê `totalReservas` para uma variável local, dorme de novo, e escreve
   `local + 1` de volta em `totalReservas` (atualização não atômica do
   contador).

Quando `--sync=true`, o método `reservar(...)` (linha 70) executa esse
trecho dentro de `synchronized (this)` (linha 83); quando `--sync=false`,
executa exatamente o mesmo código **sem** nenhuma proteção (linha 87). Essa
é a única diferença entre as duas variantes pedidas na atividade.

## 3. O que é uma condição de corrida e como ela foi produzida no experimento?

Uma condição de corrida ocorre quando o resultado de um programa depende da
ordem/tempo relativo em que threads concorrentes executam trechos de código
que leem e escrevem o mesmo estado compartilhado, sem exclusão mútua.

Nós a produzimos deliberadamente com o padrão **check-then-act**: a thread
lê o estado ("o assento está livre?"), espera um pouco (`Thread.sleep`,
simulando processamento real) e só então escreve. Como não há bloqueio
entre a leitura e a escrita, várias threads podem ler o mesmo estado "livre"
antes de qualquer uma escrever - e todas acreditam ter vencido.

Trecho real do log (`logs/experimento1_sem_sync.log`, cenário B, `--sync=false`),
mostrando as 3 threads (uma por cliente) lendo o assento 1 como `LIVRE` e
todas escrevendo por cima, na mesma janela de 1ms:

```
[20:20:59.404][ClienteHandler-A-2] RESERVAR assento=1 usuario=clienteB1 antes=LIVRE depois=clienteB1
[20:20:59.405][ClienteHandler-A-4] RESERVAR assento=1 usuario=clienteB3 antes=LIVRE depois=clienteB3
[20:20:59.405][ClienteHandler-A-3] RESERVAR assento=1 usuario=clienteB2 antes=LIVRE depois=clienteB2
```

As três chamadas retornaram `OK` ao cliente - uma dupla (na verdade tripla)
reserva do mesmo assento.

O mesmo experimento também produziu a corrida clássica de **lost update** no
contador (cenário A), com as 3 threads lendo `totalReservas=0` antes de
qualquer uma escrever `1`:

```
[20:20:58.730][ClienteHandler-A-4] contador totalReservas antes=0 depois=1
[20:20:58.730][ClienteHandler-A-3] contador totalReservas antes=0 depois=1
[20:20:58.730][ClienteHandler-A-2] contador totalReservas antes=0 depois=1
```

Das 3 tentativas de incrementar, só 1 "sobreviveu" - as outras 2 escreveram
`1` por cima de `1` em vez de `2` e `3`.

## 4. Qual foi a inconsistência numérica observada?

Com `--delay-ms=10`, em **todas** as execuções realizadas (ver
`docs/comparacao.md`):

- **Cenário A**: 30 requisições de `RESERVAR` bem-sucedidas (30 `OK`, 30
  assentos de fato ocupados, um por cliente/assento), mas
  **`totalReservas` ficou em 10 em vez de 30** - 20 incrementos perdidos por
  lost update.
- **Cenário B**: 30 requisições de `RESERVAR` disputando os mesmos 10
  assentos produziram **30 respostas `OK` em vez de 10** - ou seja, 20
  respostas `OK` "fantasmas" para reservas que na prática foram sobrescritas
  por outro cliente. Apenas 10 assentos ficaram de fato ocupados no final.

## 5. Como o mecanismo de sincronização escolhido resolve o problema?

Usamos o monitor intrínseco do Java (`synchronized (this)`) envolvendo
exatamente a região crítica descrita na pergunta 2
(`GerenciadorAssentos.reservar`, linha 83). Isso garante que, quando
`--sync=true`, **no máximo uma thread por vez** executa o trecho
check→sleep→act→atualiza-contador; qualquer outra thread que tente entrar
fica bloqueada esperando a atual sair do bloco (mesmo que ela lance uma
exceção - o monitor é liberado automaticamente).

Optamos por `synchronized` em vez de `ReentrantLock` porque não precisamos
de nenhum recurso extra do `Lock` (tentar sem bloquear, múltiplas
condições, interromper a espera) e o `synchronized` já garante liberação
do bloqueio mesmo em caso de exceção dentro do bloco, com menos código e
menos chance de esquecer o `unlock()`.

Resultado observado com `--sync=true` (mesmos cenários, mesmo `--delay-ms=10`,
3 execuções): sempre `totalReservas=30`/30 assentos no cenário A e sempre
exatos 10 `OK`/10 assentos no cenário B (ver `docs/comparacao.md`).

## 6. Qual estratégia de replicação foi usada e por quê?

**Primária-backup com replicação síncrona** (`src/cinema/servidor/Replicador.java`):

- Os clientes só falam com a réplica que estiver no papel PRIMÁRIA
  (a BACKUP responde `ERRO|NAO_PRIMARIO` a qualquer comando de
  cliente, inclusive `LISTAR`, para simplificar).
- A cada `RESERVAR`/`CANCELAR` bem-sucedido, a primária aplica a operação
  localmente (dentro da região crítica) e só responde `OK` ao cliente
  **depois** de receber o `ACK` da backup.
- Uma réplica que inicia com `--peer` pergunta ao par quem ele é
  (`QUEM_E_PRIMARIO`); se o par for primária, ela assume backup e pede um
  snapshot completo do estado (`SNAPSHOT_PEDIDO`).
- A backup monitora a primária por heartbeat (a cada 1s, timeout de 3s) e
  se promove a primária se ele parar de responder.

Escolhemos essa estratégia (em vez de, por exemplo, múltiplas réplicas com
quórum/Raft) porque ela é **simples de implementar corretamente em TCP puro
e de explicar em 10 minutos**, cobre os requisitos da atividade (2+ réplicas
com o mesmo estado, failover, snapshot) e ilustra bem o trade-off central de
sistemas distribuídos: consistência forte (espera o ACK) contra
disponibilidade (modo degradado se a backup cair). As limitações dessa
escolha (sem quórum, split-brain possível) estão documentadas em
`docs/arquitetura.md`.

## 7. O que acontece quando uma réplica cai? Como o cliente se recupera?

Testado ponta a ponta em `scripts/teste6_falhas.sh` (também roda dentro de
`rodar_tudo.sh`, 3 execuções consecutivas, todas com sucesso):

1. Reservamos o assento 30 na primária A (`OK`), replicado para B.
2. Matamos A com `kill -9` (simulando um crash abrupto).
3. A backup B, sem receber `HEARTBEAT_ACK`, detecta a falha e se promove:

   ```
   [20:19:30.867][Heartbeat-B] !!! PRIMARIA (localhost:6000) sem responder ha 3002ms
   (timeout=3000ms) - PROMOVENDO este no (B) a PRIMARIA (failover) !!!
   ```

   (tempo de detecção observado entre a queda real e a promoção: cerca de
   2 a 3 segundos, dentro do esperado pelo heartbeat de 1s + timeout de 3s).

4. O cliente, usando `ConexaoComFailover` com as duas réplicas na lista,
   tenta A (conexão recusada), passa para B e **reenvia o mesmo comando**
   pendente (`RESERVAR|30|leo`). Como o assento 30 já pertencia a "leo", a
   idempotência do protocolo faz B responder `OK` **sem duplicar** a
   reserva. Uma nova reserva (assento 31) também é aceita normalmente.
   Resultado verificado: `totalReservas=2` (exatamente: assento 30 já
   contado antes da queda, assento 31 novo - nem perdeu, nem duplicou).
5. Quando a réplica A é reiniciada apontando `--peer` para B, ela pergunta
   `QUEM_E_PRIMARIO`, recebe `SOU_PRIMARIO`, assume o papel de BACKUP e
   pede o snapshot - terminando com o mesmo estado de B
   (`totalReservas=2` nas duas).

## 8. Qual é a diferença entre TCP e UDP, e onde cada um foi usado?

**TCP** é orientado a conexão, garante entrega e ordem, e por isso paga o
custo de um handshake a cada nova conexão. Usamos TCP para tudo que exige
confiabilidade: o protocolo de clientes (`RESERVAR`/`CANCELAR`/`LISTAR`) e o
canal de replicação entre réplicas (uma reserva perdida na replicação
quebraria a consistência).

**UDP** não tem conexão, não garante entrega nem ordem, mas tem menos
overhead por mensagem. Usamos UDP só para o `STATUS` - uma consulta de
monitoramento que pode ser perdida ocasionalmente sem consequência (o
cliente simplesmente pergunta de novo). Medimos isso com
`scripts/teste5_status_udp.sh`: numa mesma máquina (localhost), a primeira
consulta UDP custou ~14-22ms (aquecimento do socket) e as seguintes ~0ms;
o TCP (`LISTAR` completo, incluindo abrir a conexão) ficou em torno de
0-4ms. Em localhost a diferença é pequena, mas o ponto didático se mantém:
o `ClienteStatus` também demonstra a ausência de garantia de entrega
tentando falar com uma porta UDP errada e recebendo "sem resposta dentro de
500ms" em vez de travar.

## 9. Quais são as limitações conhecidas da solução?

(detalhado em `docs/arquitetura.md`, seção "Limitações conhecidas")

- Sem eleição por quórum: a promoção da backup é uma decisão unilateral
  dela mesma, baseada só no heartbeat.
- Split-brain é possível em caso de partição de rede (a primária antiga
  pode continuar viva do outro lado da partição enquanto a backup se
  promove).
- Failover não é instantâneo (até ~4s no pior caso: heartbeat de 1s +
  timeout de 3s).
- Replicação em modo degradado: se a backup cair, a primária não bloqueia
  os clientes - ela loga um aviso e responde `OK` mesmo sem replicar,
  priorizando disponibilidade.
- Sem persistência em disco: todo o estado é em memória.
- Cenário `CANCELAR` não faz parte do experimento de condição de corrida
  pedido na atividade (que é focado em `RESERVAR`); por isso ele sempre
  roda protegido por `synchronized`, independente da flag `--sync`.
