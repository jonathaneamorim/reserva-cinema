# Roteiro de apresentação (10 minutos)

Pré-requisito: já ter rodado `bash scripts/build.sh` uma vez (sem erros).
Todos os comandos abaixo assumem terminal aberto na raiz do projeto.

## 0. Abertura (30s)

"O tema é um sistema de reservas de assentos de cinema: 50 assentos, uma
sessão só. O foco da atividade não é o cinema em si, é usar esse domínio
simples para demonstrar três coisas: condição de corrida real, a correção
com sincronização, e um sistema distribuído com 2 réplicas e failover."

## 1. TESTE 1 - servidor + múltiplas réplicas + múltiplos clientes (1min30)

```bash
bash scripts/teste1_replicas_e_clientes.sh
```

Dizer enquanto roda: "Subimos a réplica A como primária e a réplica B como
backup - B pergunta pra A quem é primária, e pede um snapshot completo do
estado pra sincronizar. Depois conectamos 3 clientes ao mesmo tempo na
primária: cada um ganha sua própria thread no servidor (`ClienteHandler`),
por isso conseguimos atender vários clientes simultaneamente."

Apontar no log impresso: papel de A = PRIMARIA, papel de B = BACKUP.

## 2. TESTE 2 - 30 requisições concorrentes (1min)

```bash
bash scripts/teste2_30_requisicoes.sh
```

"Agora 3 clientes disparam 10 requisições cada, ao mesmo tempo (usamos
`CountDownLatch` pra garantir que largam juntos). O log mostra threads
diferentes (`ClienteHandler-A-2`, `-3`, `-4`) atendendo em paralelo."

## 3. TESTE 3 - produzindo a condição de corrida (2min)

```bash
bash scripts/teste3_sem_sync.sh
```

"Esse mesmo teste de 30 requisições, agora contra um servidor iniciado com
`--sync=false`. O código da região crítica em `GerenciadorAssentos` faz
check-then-act: lê se o assento está livre, dorme 10ms simulando
processamento, e só depois grava - sem nenhum lock. Vejam o resultado:"

- Cenário A: aponta a linha `totalReservas (servidor): 10` (esperado 30).
- Cenário B: aponta `Respostas OK: 30` (esperado 10) e
  `Assentos ocupados de fato: 10`.
- "Isso é 100% reproduzível - já rodamos dezenas de vezes e sempre dá essa
  mesma inconsistência com esse delay."

## 4. TESTE 4 - corrigindo com sincronização (1min30)

```bash
bash scripts/teste4_com_sync.sh
```

"Mesmo teste, mesmo delay, só muda a flag `--sync=true`: agora o mesmo
trecho de código roda dentro de um `synchronized (this)`. Resultado: 30/30
no cenário A, exatamente 10 OK no cenário B - toda vez."

Se der tempo, abrir `docs/comparacao.md` para mostrar a tabela consolidada.

## 5. TESTE 5 - UDP e comparação com TCP (1min)

```bash
bash scripts/teste5_status_udp.sh
```

"O STATUS é via UDP, sem conexão - por isso tem timeout no cliente (1s) e
o teste mostra o que acontece quando a porta está errada: 'sem resposta',
em vez de travar. Depois comparamos o tempo de uma consulta UDP com um
LISTAR via TCP completo, incluindo abrir a conexão."

## 6. TESTE 6 - queda de cliente e failover de réplica (2min30)

```bash
bash scripts/teste6_falhas.sh
```

(Sem `MODO_AUTO=1`, o script pausa em cada passo - apertar ENTER para
avançar enquanto narra.)

- Passo 1: "um cliente se desconecta sem mandar SAIR (simulando queda
  abrupta) - o servidor detecta, loga, decrementa o contador de clientes
  conectados, e continua rodando normalmente."
- Passo 2-3: "reservamos um assento na primária, e agora eu mato a
  primária de propósito (`kill -9`). A backup sonda ela por heartbeat a
  cada 1 segundo; depois de 3 segundos sem resposta, ela se promove."
- Passo 4: "o cliente tenta a réplica antiga (recusada), passa pra outra e
  reenvia o mesmo comando - como o protocolo é idempotente, não duplica a
  reserva que já tinha sido feita antes da queda."
- Passo 5: "religamos a réplica que caiu - ela pergunta pra outra quem é
  primária, entra como backup e sincroniza pelo snapshot."

## Fechamento (30s)

"Resumindo: região crítica identificada e comprovadamente insegura sem
sincronização, corrigida com `synchronized`, replicação primária-backup
com 2 réplicas reais, failover funcionando e cliente que se recupera sem
duplicar reservas. As limitações - sem quórum, split-brain possível,
sem persistência - estão documentadas e são escolhas conscientes de
escopo."

---

## Perguntas prováveis do professor (respostas prontas)

**Por que primária-backup e não outra estratégia (ex.: múltiplas cópias com
quórum)?**
"Porque cobre os requisitos da atividade (2+ réplicas, mesmo estado,
failover) com uma implementação que a gente consegue implementar
corretamente em TCP puro e explicar em poucos minutos. Quórum/Raft
resolveria o split-brain, mas é ordens de magnitude mais complexo -
documentamos isso como limitação conhecida."

**Por que `synchronized` e não `ReentrantLock`?**
"Porque não precisávamos de nenhum recurso extra do Lock (tentar sem
bloquear, múltiplas condições, interromper espera), e o `synchronized`
libera o monitor automaticamente mesmo se a região crítica lançar uma
exceção - menos código, menos chance de esquecer o unlock."

**O que acontece se a backup cair (não a primária)?**
"A primária tenta replicar, não recebe ACK dentro do timeout, loga um
aviso de 'modo degradado' e responde OK ao cliente mesmo assim - ela
prioriza continuar disponível em vez de travar esperando uma backup que
pode nunca voltar. Quando a backup volta, ela pede snapshot de novo e
resincroniza."

**E se a primária cair no meio de uma reserva (entre aplicar localmente e
mandar pra backup)?**
"Se a operação já foi aplicada localmente na primária mas ela morre antes
de mandar o OP (ou antes do ACK voltar), essa operação específica pode não
ter chegado à backup. O cliente, como não recebe resposta, trata como
falha e reenvia o mesmo comando pra outra réplica; se a operação nunca
chegou lá, ela simplesmente é refeita com sucesso. O único caso não
coberto é o cliente desistir antes de reenviar - é uma limitação
documentada, não veríamos isso como 'bug': é o trade-off de não ter um
protocolo de commit distribuído (2PC) nessa entrega."

**Como vocês garantem que o cliente não duplica reserva depois do
failover?**
"Idempotência no protocolo: `RESERVAR` de um assento que já pertence ao
mesmo usuário retorna OK sem alterar nada. Então reenviar o último comando
depois de trocar de réplica é seguro por construção."

**Por que UDP para o STATUS e não TCP também?**
"Justamente para mostrar a diferença na prática: STATUS é uma consulta de
monitoramento, tolerante a perda ocasional, então não precisa do custo
de conexão do TCP. Já RESERVAR/CANCELAR precisam de confirmação garantida,
por isso são TCP."

**O contador `totalReservas` ficou sempre em 10 no cenário sem sync - isso
não é meio "bom demais", determinístico?**
"É real e reproduzível - documentamos isso em `docs/comparacao.md`. Com
`--delay-ms=10` e as 3 threads largando juntas via `CountDownLatch`, elas
tendem a ficar em lockstep ao longo das rodadas, então a corrida se repete
de forma consistente. Isso não é um valor forjado: é o resultado de rodar
o experimento de verdade, várias vezes."
