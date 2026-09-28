package cinema.servidor;

import cinema.comum.Log;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ponto de entrada de uma réplica do servidor de reservas.
 *
 * Uso:
 *   java cinema.servidor.Servidor --id=A --porta=5000 --porta-udp=5100 \
 *        --porta-repl=6000 [--peer=host:porta-repl] --papel=primaria|backup \
 *        --sync=true|false --delay-ms=10
 */
public class Servidor {

    public static void main(String[] args) throws Exception {
        Map<String, String> opcoes = parseArgs(args);

        String id = opcoes.getOrDefault("id", "A");
        int porta = Integer.parseInt(opcoes.getOrDefault("porta", "5000"));
        int portaUdp = Integer.parseInt(opcoes.getOrDefault("porta-udp", "5100"));
        int portaRepl = Integer.parseInt(opcoes.getOrDefault("porta-repl", "6000"));
        boolean sync = Boolean.parseBoolean(opcoes.getOrDefault("sync", "true"));
        long delayMs = Long.parseLong(opcoes.getOrDefault("delay-ms", "10"));
        String papelStr = opcoes.getOrDefault("papel", "primaria");
        String peerArg = opcoes.get("peer");

        EstadoNo.Papel papelInicial = "backup".equalsIgnoreCase(papelStr)
                ? EstadoNo.Papel.BACKUP : EstadoNo.Papel.PRIMARIA;

        String peerHost = null;
        int peerPorta = -1;
        if (peerArg != null) {
            String[] partes = peerArg.split(":");
            peerHost = partes[0];
            peerPorta = Integer.parseInt(partes[1]);
        }

        EstadoNo estadoNo = new EstadoNo(papelInicial);
        GerenciadorAssentos gerenciador = new GerenciadorAssentos(sync, delayMs);
        AtomicInteger clientesConectados = new AtomicInteger(0);
        AtomicInteger sequenciaThreads = new AtomicInteger(0);

        Replicador replicador = new Replicador(id, estadoNo, gerenciador, portaRepl, peerHost, peerPorta);
        replicador.iniciarServidorReplicacao();

        // --- descoberta de papel junto ao par, na inicialização ---
        if (peerArg != null) {
            EstadoNo.Papel papelDoPeer = replicador.consultarPapelDoPeer();
            if (papelDoPeer == EstadoNo.Papel.PRIMARIA) {
                estadoNo.assumirComoBackup();
                Log.info("Peer " + peerArg + " respondeu que e PRIMARIA - assumindo BACKUP e pedindo snapshot.");
                replicador.pedirSnapshotAoPeer();
            } else if (papelDoPeer == EstadoNo.Papel.BACKUP) {
                Log.info("Peer " + peerArg + " respondeu que e BACKUP - mantendo papel definido por --papel="
                        + estadoNo.getPapel());
            } else {
                Log.info("Peer " + peerArg + " nao respondeu (indisponivel agora) - mantendo papel definido por --papel="
                        + estadoNo.getPapel());
            }
            if (estadoNo.getPapel() == EstadoNo.Papel.BACKUP) {
                replicador.iniciarMonitorHeartbeat();
            }
        } else {
            Log.info("Sem --peer configurado - executando como no unico. Papel=" + estadoNo.getPapel());
        }

        StatusUDP statusUDP = new StatusUDP(portaUdp, clientesConectados, gerenciador, estadoNo);
        statusUDP.iniciar();

        Log.info("=== Servidor '" + id + "' iniciado === papel=" + estadoNo.getPapel()
                + " porta-tcp=" + porta + " porta-udp=" + portaUdp + " porta-repl=" + portaRepl
                + " sync=" + sync + " delay-ms=" + delayMs
                + (peerArg != null ? " peer=" + peerArg : " (sem peer)"));

        try (ServerSocket serverSocket = new ServerSocket(porta)) {
            while (true) {
                Socket socketCliente = serverSocket.accept();
                int n = sequenciaThreads.incrementAndGet();
                Thread thread = new Thread(
                        new ClienteHandler(socketCliente, gerenciador, replicador, estadoNo, clientesConectados),
                        "ClienteHandler-" + id + "-" + n);
                thread.start();
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opcoes = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String semPrefixo = arg.substring(2);
                int idx = semPrefixo.indexOf('=');
                if (idx >= 0) {
                    opcoes.put(semPrefixo.substring(0, idx), semPrefixo.substring(idx + 1));
                } else {
                    opcoes.put(semPrefixo, "true");
                }
            }
        }
        return opcoes;
    }
}
