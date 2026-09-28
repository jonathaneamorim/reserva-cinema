package cinema.servidor;

import cinema.comum.Log;
import cinema.comum.Protocolo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

/**
 * Canal de replicação entre as duas réplicas (primária-backup).
 *
 * Estratégia escolhida: replicação primária-backup síncrona (a primária só
 * responde OK ao cliente depois de receber o ACK da backup), com sincronização
 * inicial por snapshot completo e failover por heartbeat. É a estratégia mais
 * simples de implementar e de explicar em 10 minutos, ao custo de não ter
 * eleição por quórum nem tolerância real a partição de rede (ver docs/arquitetura.md).
 *
 * Cada operação de replicação (pedir snapshot, empurrar uma operação, sondar
 * com heartbeat, perguntar quem é a primária) abre uma conexão TCP curta:
 * conecta, manda uma linha, lê a resposta, fecha. Isso evita ter que manter
 * sockets persistentes vivos e reconectar manualmente quando o par reinicia -
 * o preço é um pequeno overhead de handshake TCP por operação, aceitável para
 * esta atividade.
 */
public class Replicador {

    private final EstadoNo estadoNo;
    private final GerenciadorAssentos gerenciador;
    private final int portaReplLocal;
    private final String peerHost;
    private final int peerPortaRepl;
    private final String idNo;

    private static final int TIMEOUT_CONEXAO_MS = 800;
    private static final int TIMEOUT_RESPOSTA_MS = 1500;
    private static final long INTERVALO_HEARTBEAT_MS = 1000;
    private static final long TIMEOUT_HEARTBEAT_MS = 3000;

    public Replicador(String idNo, EstadoNo estadoNo, GerenciadorAssentos gerenciador,
                       int portaReplLocal, String peerHost, int peerPortaRepl) {
        this.idNo = idNo;
        this.estadoNo = estadoNo;
        this.gerenciador = gerenciador;
        this.portaReplLocal = portaReplLocal;
        this.peerHost = peerHost;
        this.peerPortaRepl = peerPortaRepl;
    }

    public boolean temPeerConfigurado() {
        return peerHost != null;
    }

    /** Sobe a thread que aceita conexões de replicação vindas do par (em qualquer sentido). */
    public void iniciarServidorReplicacao() {
        Thread t = new Thread(this::loopAceitacao, "Replicacao-Servidor-" + idNo);
        t.setDaemon(true);
        t.start();
    }

    private void loopAceitacao() {
        try (ServerSocket servidor = new ServerSocket(portaReplLocal)) {
            Log.info("Canal de replicacao escutando na porta " + portaReplLocal);
            while (true) {
                Socket conexao = servidor.accept();
                Thread handler = new Thread(() -> tratarConexao(conexao), "Replicacao-Conexao-" + idNo);
                handler.setDaemon(true);
                handler.start();
            }
        } catch (IOException e) {
            Log.erro("Canal de replicacao encerrado: " + e.getMessage());
        }
    }

    private void tratarConexao(Socket conexao) {
        try (Socket s = conexao;
             BufferedReader entrada = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream saida = s.getOutputStream()) {
            String linha = entrada.readLine();
            if (linha == null) {
                return;
            }
            String[] campos = Protocolo.partir(linha);
            String comando = campos[0];
            String resposta;
            switch (comando) {
                case Protocolo.REPL_SNAPSHOT_PEDIDO:
                    resposta = Protocolo.montar(Protocolo.REPL_SNAPSHOT, gerenciador.gerarSnapshot());
                    break;
                case Protocolo.REPL_OP:
                    // campos: OP|RESERVAR|assento|usuario  (ou CANCELAR)
                    String opComando = campos[1];
                    int assento = Integer.parseInt(campos[2]);
                    String usuario = campos[3];
                    gerenciador.aplicarOperacaoReplicada(opComando, assento, usuario);
                    Log.info("replicacao recebida: " + opComando + " assento=" + assento + " usuario=" + usuario);
                    resposta = Protocolo.REPL_ACK;
                    break;
                case Protocolo.REPL_HEARTBEAT:
                    resposta = Protocolo.REPL_HEARTBEAT_ACK;
                    break;
                case Protocolo.REPL_QUEM_E_PRIMARIO:
                    resposta = estadoNo.isPrimaria() ? Protocolo.REPL_SOU_PRIMARIO : Protocolo.REPL_SOU_BACKUP;
                    break;
                default:
                    resposta = Protocolo.respostaErro(Protocolo.ERRO_COMANDO_INVALIDO);
            }
            saida.write((resposta + "\n").getBytes(StandardCharsets.UTF_8));
            saida.flush();
        } catch (IOException e) {
            Log.erro("Falha tratando conexao de replicacao: " + e.getMessage());
        }
    }

    /**
     * Chamado pela primária após aplicar localmente um RESERVAR/CANCELAR bem-sucedido.
     * Envia a operação à backup e só retorna depois do ACK (replicação síncrona).
     * Se a backup não responder, loga o aviso e segue em modo degradado - a
     * primária continua atendendo clientes normalmente (ver docs/arquitetura.md).
     */
    public boolean replicarOperacao(String comando, int assento, String usuario) {
        if (peerHost == null) {
            return true; // nó único, sem par configurado - nada a replicar
        }
        String linha = Protocolo.montar(Protocolo.REPL_OP, comando, String.valueOf(assento), usuario);
        try {
            String resposta = enviarERecebe(linha);
            if (Protocolo.REPL_ACK.equals(resposta)) {
                return true;
            }
            Log.erro("Backup respondeu algo inesperado a OP: " + resposta + " - modo degradado.");
            return false;
        } catch (IOException e) {
            Log.erro("Nao foi possivel replicar para a backup (" + peerHost + ":" + peerPortaRepl
                    + "): " + e.getMessage() + " - primaria segue em MODO DEGRADADO (sem backup ativa).");
            return false;
        }
    }

    /** Pede o snapshot completo ao par (usado na sincronização inicial da backup). */
    public boolean pedirSnapshotAoPeer() {
        if (peerHost == null) {
            return false;
        }
        try {
            String resposta = enviarERecebe(Protocolo.REPL_SNAPSHOT_PEDIDO);
            String[] campos = Protocolo.partir(resposta);
            if (campos.length >= 2 && Protocolo.REPL_SNAPSHOT.equals(campos[0])) {
                gerenciador.restaurarSnapshot(campos[1]);
                Log.info("Snapshot recebido do par e aplicado com sucesso (sincronizacao inicial).");
                return true;
            }
            Log.erro("Resposta inesperada ao pedir snapshot: " + resposta);
            return false;
        } catch (IOException e) {
            Log.erro("Nao foi possivel obter snapshot do par: " + e.getMessage());
            return false;
        }
    }

    /** Pergunta ao par qual o papel dele. Retorna null se o par nao respondeu (indisponivel). */
    public EstadoNo.Papel consultarPapelDoPeer() {
        if (peerHost == null) {
            return null;
        }
        try {
            String resposta = enviarERecebe(Protocolo.REPL_QUEM_E_PRIMARIO);
            if (Protocolo.REPL_SOU_PRIMARIO.equals(resposta)) {
                return EstadoNo.Papel.PRIMARIA;
            }
            if (Protocolo.REPL_SOU_BACKUP.equals(resposta)) {
                return EstadoNo.Papel.BACKUP;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Sobe a thread de monitoramento por heartbeat: enquanto este nó for BACKUP,
     * sonda periodicamente a primária. Se ela ficar sem responder por mais que
     * TIMEOUT_HEARTBEAT_MS, este nó se promove a primária (failover).
     */
    public void iniciarMonitorHeartbeat() {
        Thread t = new Thread(this::loopHeartbeat, "Heartbeat-" + idNo);
        t.setDaemon(true);
        t.start();
    }

    private void loopHeartbeat() {
        long ultimoSucesso = System.currentTimeMillis();
        while (estadoNo.getPapel() == EstadoNo.Papel.BACKUP) {
            try {
                String resposta = enviarERecebe(Protocolo.REPL_HEARTBEAT);
                if (Protocolo.REPL_HEARTBEAT_ACK.equals(resposta)) {
                    ultimoSucesso = System.currentTimeMillis();
                }
            } catch (IOException e) {
                // sem resposta desta vez - só promove se passar do timeout total abaixo
            }

            long semRespostaHaMs = System.currentTimeMillis() - ultimoSucesso;
            if (semRespostaHaMs > TIMEOUT_HEARTBEAT_MS) {
                Log.info("!!! PRIMARIA (" + peerHost + ":" + peerPortaRepl + ") sem responder ha "
                        + semRespostaHaMs + "ms (timeout=" + TIMEOUT_HEARTBEAT_MS + "ms) - "
                        + "PROMOVENDO este no (" + idNo + ") a PRIMARIA (failover) !!!");
                estadoNo.promoverAPrimaria();
                return;
            }

            dormir(INTERVALO_HEARTBEAT_MS);
        }
    }

    private String enviarERecebe(String linha) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerHost, peerPortaRepl), TIMEOUT_CONEXAO_MS);
            socket.setSoTimeout(TIMEOUT_RESPOSTA_MS);
            OutputStream saida = socket.getOutputStream();
            saida.write((linha + "\n").getBytes(StandardCharsets.UTF_8));
            saida.flush();
            BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String resposta = entrada.readLine();
            if (resposta == null) {
                throw new IOException("conexao encerrada sem resposta");
            }
            return resposta;
        }
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
