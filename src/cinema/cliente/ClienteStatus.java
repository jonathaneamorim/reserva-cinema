package cinema.cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Cliente UDP para consultar o STATUS das réplicas e medir o tempo de
 * resposta (RTT). UDP não garante entrega, então usamos um timeout (padrão
 * 1s): se o datagrama de resposta não chegar a tempo, avisamos isso
 * explicitamente em vez de travar esperando para sempre.
 *
 * O modo --modo=comparar mede, na mesma máquina, o tempo de uma consulta
 * UDP (STATUS) contra o tempo de um LISTAR via TCP (incluindo o custo de
 * abrir a conexão), repetindo N vezes e imprimindo uma tabela comparativa.
 */
public class ClienteStatus {

    public static void main(String[] args) throws Exception {
        Map<String, String> opcoes = parseArgs(args);
        String host = opcoes.getOrDefault("host", "localhost");
        int portaUdp = Integer.parseInt(opcoes.getOrDefault("porta-udp", "5100"));
        int portaTcp = Integer.parseInt(opcoes.getOrDefault("porta-tcp", "5000"));
        int timeoutMs = Integer.parseInt(opcoes.getOrDefault("timeout-ms", "1000"));
        String modo = opcoes.getOrDefault("modo", "status");

        if ("comparar".equals(modo)) {
            int repeticoes = Integer.parseInt(opcoes.getOrDefault("repeticoes", "5"));
            compararTcpUdp(host, portaTcp, portaUdp, timeoutMs, repeticoes);
        } else {
            consultarStatusUnico(host, portaUdp, timeoutMs);
        }
    }

    private static void consultarStatusUnico(String host, int portaUdp, int timeoutMs) throws IOException {
        long inicio = System.nanoTime();
        String resposta = consultarStatusUdp(host, portaUdp, timeoutMs);
        long fimMs = (System.nanoTime() - inicio) / 1_000_000;
        if (resposta == null) {
            System.out.println("UDP: sem resposta dentro de " + timeoutMs + "ms "
                    + "(UDP nao garante entrega - o datagrama pode ter se perdido ou o servidor pode estar fora do ar).");
        } else {
            System.out.println("UDP: " + resposta + "  (RTT=" + fimMs + "ms)");
        }
    }

    private static void compararTcpUdp(String host, int portaTcp, int portaUdp, int timeoutMs, int repeticoes) throws IOException {
        System.out.println("=== Comparacao TCP x UDP - " + repeticoes + " repeticoes ===");
        System.out.printf("%-12s %-12s %-12s%n", "repeticao", "UDP(ms)", "TCP(ms)");
        long somaUdp = 0, somaTcp = 0;
        int amostrasUdp = 0;

        for (int i = 1; i <= repeticoes; i++) {
            long t0 = System.nanoTime();
            String respUdp = consultarStatusUdp(host, portaUdp, timeoutMs);
            long udpMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            consultarListarTcp(host, portaTcp);
            long tcpMs = (System.nanoTime() - t1) / 1_000_000;

            String udpTexto = (respUdp == null) ? "sem resposta" : String.valueOf(udpMs);
            System.out.printf("%-12d %-12s %-12d%n", i, udpTexto, tcpMs);

            if (respUdp != null) {
                somaUdp += udpMs;
                amostrasUdp++;
            }
            somaTcp += tcpMs;
        }

        System.out.println();
        if (amostrasUdp > 0) {
            System.out.println("Media UDP: " + (somaUdp / amostrasUdp) + "ms (com base em " + amostrasUdp + "/" + repeticoes + " respostas recebidas)");
        } else {
            System.out.println("Media UDP: sem nenhuma resposta recebida.");
        }
        System.out.println("Media TCP (LISTAR, incluindo abertura de conexao): " + (somaTcp / repeticoes) + "ms");
        System.out.println();
        System.out.println("Observacao: TCP garante entrega e ordem, mas paga o custo do handshake de");
        System.out.println("conexao a cada consulta neste teste. UDP nao tem esse custo de conexao nem");
        System.out.println("garantia de entrega - por isso e adequado para um STATUS de monitoramento,");
        System.out.println("mas nao para operacoes que precisam de confirmacao, como RESERVAR/CANCELAR.");
    }

    private static String consultarStatusUdp(String host, int portaUdp, int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            byte[] envio = "STATUS".getBytes(StandardCharsets.UTF_8);
            InetAddress endereco = InetAddress.getByName(host);
            socket.send(new DatagramPacket(envio, envio.length, endereco, portaUdp));

            byte[] buffer = new byte[256];
            DatagramPacket resposta = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(resposta);
                return new String(resposta.getData(), 0, resposta.getLength(), StandardCharsets.UTF_8);
            } catch (SocketTimeoutException e) {
                return null;
            }
        }
    }

    private static void consultarListarTcp(String host, int portaTcp) {
        try (Socket socket = new Socket(host, portaTcp);
             BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream saida = socket.getOutputStream()) {
            saida.write("LISTAR\n".getBytes(StandardCharsets.UTF_8));
            saida.flush();
            entrada.readLine();
        } catch (IOException e) {
            System.out.println("Falha na consulta TCP: " + e.getMessage());
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
                }
            }
        }
        return opcoes;
    }
}
