package cinema.servidor;

import cinema.comum.Log;
import cinema.comum.Protocolo;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recurso compartilhado da aplicação: o mapa de assentos da sessão (1..50) e
 * os contadores totalReservas/totalRequisicoes.
 *
 * Esta classe é o coração do experimento de concorrência pedido na
 * atividade. Ela existe em DUAS variantes de comportamento, escolhidas em
 * tempo de execução pelo parâmetro "sync":
 *
 *   sync=false -> a região crítica (verificar + Thread.sleep + gravar +
 *                 atualizar contador) roda SEM nenhuma proteção. É o padrão
 *                 clássico "check-then-act" com uma janela de tempo entre a
 *                 verificação e a gravação, propositalmente vulnerável.
 *
 *   sync=true  -> a MESMA sequência de código roda dentro de um bloco
 *                 "synchronized (this)", que funciona como uma seção crítica:
 *                 só uma thread por vez consegue executar o trecho entre o
 *                 "lock" implícito de entrada e a saída do bloco. As demais
 *                 threads ficam bloqueadas esperando a vez.
 *
 * Optamos por "synchronized" (monitor intrínseco do objeto) em vez de
 * ReentrantLock porque, para esta atividade, não precisamos de
 * funcionalidades extras do Lock (tentar sem bloquear, interromper a espera,
 * múltiplas condições, etc.) e o synchronized já garante liberação do
 * bloqueio mesmo se uma exceção for lançada dentro do bloco - menos código,
 * menos chance de esquecer um unlock().
 */
public class GerenciadorAssentos {

    private final String[] donoDoAssento = new String[Protocolo.TOTAL_ASSENTOS + 1]; // índice 0 não usado
    private final boolean sync;
    private final long delayMs;

    // Contador de reservas efetivadas. Propositalmente um "int" comum (sem
    // volatile, sem Atomic*) para permitir a demonstração de "lost update"
    // quando sync=false: a leitura, o sleep e a escrita abaixo não são uma
    // operação atômica.
    private int totalReservas = 0;

    // Contador apenas informativo (usado no STATUS/ADMIN), não faz parte do
    // experimento de condição de corrida - por isso pode ser atômico sem
    // prejudicar a demonstração pedida na atividade.
    private final AtomicInteger totalRequisicoes = new AtomicInteger(0);

    public GerenciadorAssentos(boolean sync, long delayMs) {
        this.sync = sync;
        this.delayMs = delayMs;
    }

    public boolean isSync() {
        return sync;
    }

    /** Lista os números dos assentos livres, em ordem, separados por vírgula. */
    public String listar() {
        totalRequisicoes.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        for (int assento = 1; assento <= Protocolo.TOTAL_ASSENTOS; assento++) {
            if (donoDoAssento[assento] == null) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(assento);
            }
        }
        return sb.toString();
    }

    public String reservar(int assento, String usuario) {
        totalRequisicoes.incrementAndGet();
        if (assento < 1 || assento > Protocolo.TOTAL_ASSENTOS) {
            return Protocolo.respostaErro(Protocolo.ERRO_ASSENTO_INVALIDO);
        }
        // A ÚNICA diferença entre a variante insegura e a variante segura é
        // se o trecho abaixo é executado dentro de um monitor ou não.
        if (sync) {
            synchronized (this) {
                return regiaoCriticaReservar(assento, usuario);
            }
        }
        return regiaoCriticaReservar(assento, usuario);
    }

    /**
     * Região crítica do RESERVAR: verifica, simula processamento (sleep),
     * grava a reserva e atualiza o contador global. Quando chamada fora de
     * um bloco "synchronized" (sync=false), duas threads podem executar este
     * trecho ao mesmo tempo e produzir tanto assentos reservados em
     * duplicidade quanto perda de incrementos no contador.
     */
    private String regiaoCriticaReservar(int assento, String usuario) {
        String donoAtual = donoDoAssento[assento];

        // Idempotência: se o próprio usuário já é o dono, não duplica nada.
        // Isso é o que permite ao cliente reenviar um RESERVAR com segurança
        // após um failover, sem correr risco de reservar duas vezes.
        if (usuario.equals(donoAtual)) {
            Log.info("RESERVAR assento=" + assento + " usuario=" + usuario
                    + " (idempotente: ja era o dono) -> OK");
            return Protocolo.respostaOk();
        }

        // 1) CHECK: lê o estado atual do assento.
        boolean livreNoMomentoDaLeitura = (donoAtual == null);

        // 2) Simula processamento entre o check e o act - é exatamente aqui
        //    que se abre a janela da condição de corrida "check-then-act":
        //    duas threads podem ler "livre" antes que qualquer uma delas
        //    grave sua reserva.
        dormir();

        if (!livreNoMomentoDaLeitura) {
            Log.info("RESERVAR assento=" + assento + " usuario=" + usuario
                    + " antes=OCUPADO depois=OCUPADO motivo=ASSENTO_OCUPADO");
            return Protocolo.respostaErro(Protocolo.ERRO_ASSENTO_OCUPADO);
        }

        // 3) ACT: grava a reserva. Sem sincronização, mais de uma thread
        //    pode chegar até aqui pensando que o assento estava livre.
        donoDoAssento[assento] = usuario;
        Log.info("RESERVAR assento=" + assento + " usuario=" + usuario
                + " antes=LIVRE depois=" + usuario);

        // 4) Atualiza o contador de reservas efetivadas de forma
        //    deliberadamente NÃO atômica: lê para uma variável local, dorme
        //    (janela de corrida) e só então escreve local+1. Se duas threads
        //    lerem o mesmo valor antes de qualquer uma escrever, um dos
        //    incrementos se perde ("lost update").
        int valorLido = totalReservas;
        dormir();
        int valorNovo = valorLido + 1;
        totalReservas = valorNovo;
        Log.info("contador totalReservas antes=" + valorLido + " depois=" + valorNovo);

        return Protocolo.respostaOk();
    }

    public String cancelar(int assento, String usuario) {
        totalRequisicoes.incrementAndGet();
        if (assento < 1 || assento > Protocolo.TOTAL_ASSENTOS) {
            return Protocolo.respostaErro(Protocolo.ERRO_ASSENTO_INVALIDO);
        }
        // CANCELAR não faz parte do experimento de condição de corrida pedido
        // na atividade (que é focado em RESERVAR); por segurança geral do
        // sistema ele sempre roda protegido, independente da flag --sync.
        synchronized (this) {
            String donoAtual = donoDoAssento[assento];
            if (donoAtual == null) {
                Log.info("CANCELAR assento=" + assento + " usuario=" + usuario
                        + " motivo=NAO_RESERVADO");
                return Protocolo.respostaErro(Protocolo.ERRO_NAO_RESERVADO);
            }
            if (!donoAtual.equals(usuario)) {
                Log.info("CANCELAR assento=" + assento + " usuario=" + usuario
                        + " motivo=NAO_E_DONO (dono real=" + donoAtual + ")");
                return Protocolo.respostaErro(Protocolo.ERRO_NAO_E_DONO);
            }
            donoDoAssento[assento] = null;
            Log.info("CANCELAR assento=" + assento + " usuario=" + usuario
                    + " antes=" + donoAtual + " depois=LIVRE");
            return Protocolo.respostaOk();
        }
    }

    /**
     * Aplica no nó BACKUP uma operação que a primária já decidiu e
     * confirmou. Não repete a simulação de processamento (isso já aconteceu
     * na primária) - aqui é só espelhar o resultado final.
     */
    public synchronized void aplicarOperacaoReplicada(String comando, int assento, String usuario) {
        if (Protocolo.CMD_RESERVAR.equals(comando)) {
            if (!usuario.equals(donoDoAssento[assento])) {
                donoDoAssento[assento] = usuario;
                totalReservas++;
            }
        } else if (Protocolo.CMD_CANCELAR.equals(comando)) {
            donoDoAssento[assento] = null;
        }
    }

    /** Gera uma representação textual do estado completo, para snapshot de sincronização inicial. */
    public synchronized String gerarSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append(totalReservas);
        for (int assento = 1; assento <= Protocolo.TOTAL_ASSENTOS; assento++) {
            sb.append(';').append(donoDoAssento[assento] == null ? "" : donoDoAssento[assento]);
        }
        return sb.toString();
    }

    /** Restaura o estado completo a partir de um snapshot recebido da primária. */
    public synchronized void restaurarSnapshot(String snapshot) {
        String[] partes = snapshot.split(";", -1);
        totalReservas = Integer.parseInt(partes[0]);
        for (int assento = 1; assento <= Protocolo.TOTAL_ASSENTOS && assento < partes.length; assento++) {
            String dono = partes[assento];
            donoDoAssento[assento] = dono.isEmpty() ? null : dono;
        }
    }

    public synchronized int contarAssentosOcupados() {
        int ocupados = 0;
        for (int assento = 1; assento <= Protocolo.TOTAL_ASSENTOS; assento++) {
            if (donoDoAssento[assento] != null) {
                ocupados++;
            }
        }
        return ocupados;
    }

    public synchronized int getTotalReservas() {
        return totalReservas;
    }

    public int getTotalRequisicoes() {
        return totalRequisicoes.get();
    }

    /** Resposta ao comando administrativo ADMIN|CONTADORES. */
    public synchronized String contadoresParaAdmin() {
        return Protocolo.montar(Protocolo.RESP_CONTADORES,
                "totalReservas=" + totalReservas,
                "totalRequisicoes=" + totalRequisicoes.get(),
                "assentosOcupados=" + contarAssentosOcupados());
    }

    private void dormir() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
