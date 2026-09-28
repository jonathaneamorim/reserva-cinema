# Comparação: sem sincronização × com sincronização

Números reais, obtidos executando `scripts/experimento1_sem_sync.sh` e
`scripts/experimento2_com_sync.sh` (`--delay-ms=10`, o padrão). Cada
experimento foi executado **3 vezes** em sequência (mais as execuções feitas
via `rodar_tudo.sh`, também 3 vezes) e o resultado foi **idêntico em todas as
execuções** - ver `logs/experimento1_sem_sync.log` e
`logs/experimento2_com_sync.log` para os logs completos com timestamps reais.

| Experimento | Sincronização | Cenário | Requisições | Resultado esperado | Resultado observado | Veredito |
|---|---|---|---|---|---|---|
| 1 | NÃO (`--sync=false`) | A - contador (lost update) | 30 (3 clientes × 10) | `totalReservas=30`, 30 assentos ocupados | `totalReservas=10`, 30 assentos ocupados, 30 `OK` | **CONDIÇÃO DE CORRIDA DETECTADA** |
| 1 | NÃO (`--sync=false`) | B - dupla reserva (check-then-act) | 30 (3 clientes × 10) | exatamente 10 `OK` | **30 `OK`** (todo cliente "ganhou" todo assento), apenas 10 assentos ocupados de fato | **CONDIÇÃO DE CORRIDA DETECTADA** |
| 2 | SIM (`--sync=true`) | A - contador (lost update) | 30 (3 clientes × 10) | `totalReservas=30`, 30 assentos ocupados | `totalReservas=30`, 30 assentos ocupados, 30 `OK` | **CONSISTENTE** |
| 2 | SIM (`--sync=true`) | B - dupla reserva (check-then-act) | 30 (3 clientes × 10) | exatamente 10 `OK` | **10 `OK`**, 20 `ERRO\|ASSENTO_OCUPADO`, 10 assentos ocupados | **CONSISTENTE** |

## Leitura dos números

- **Cenário A (contador).** Sem proteção, o padrão "ler variável local →
  `Thread.sleep` → escrever `local+1`" perde incrementos: das 30 chamadas
  bem-sucedidas de `RESERVAR` (30 assentos realmente ficaram ocupados,
  porque cada cliente usa uma faixa de assentos exclusiva), o contador
  `totalReservas` só registrou 10 - ou seja, **20 incrementos "desapareceram"**
  porque threads concorrentes leram o mesmo valor antigo antes de qualquer
  uma escrever o novo. Com `synchronized`, as 30 chamadas serializam e o
  contador bate exatamente com o número de reservas efetivadas.

- **Cenário B (dupla reserva).** Sem proteção, os 3 clientes competem pelos
  mesmos 10 assentos. Como o `sleep` entre o "check" (assento livre?) e o
  "act" (gravar) abre uma janela grande o bastante para as 3 threads
  passarem pela verificação antes de qualquer uma escrever, **todas as 30
  tentativas retornaram `OK`** - inclusive as que, de fato, perderam a
  disputa (o valor final do assento é sempre o do último `write`, mas os
  clientes anteriores já tinham recebido `OK` erroneamente). Com
  `synchronized`, apenas a primeira tentativa por assento consegue: exatas
  10 `OK` e 20 `ERRO|ASSENTO_OCUPADO`.

## Calibração do `--delay-ms`

Usamos `--delay-ms=10` (o padrão). Com esse valor, o experimento SEM
sincronização produziu a **mesma** inconsistência em todas as execuções
testadas (3 rodadas manuais + múltiplas rodadas via `rodar_tudo.sh`):
sempre `totalReservas=10` no cenário A e sempre 30 `OK`/10 assentos no
cenário B. Não observamos nenhuma execução "por acaso consistente" - o que
faz sentido porque, com 10ms de sleep e 3 threads lançadas quase no mesmo
instante (via `CountDownLatch`), o sistema operacional tende a manter as 3
threads em lockstep ao longo das 10 rodadas de cada cliente, reproduzindo a
janela de corrida em praticamente toda rodada.
