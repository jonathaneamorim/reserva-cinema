package cinema.servidor;

import cinema.comum.Log;
import cinema.comum.Protocolo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Responde a consultas de status via UDP. UDP foi escolhido aqui de propósito
 * (em vez de reaproveitar o TCP) para servir de exemplo prático da diferença
 * entre os dois protocolos: sem conexão, sem garantia de entrega, mas com
 * menos overhead para uma consulta simples e tolerante a perda ocasional
 * ("é só status, não é uma reserva").
 */
public class StatusUDP {

    private final int porta;
    private final AtomicInteger clientesConectados;
    private final GerenciadorAssentos gerenciador;
    private final EstadoNo estadoNo;

    public StatusUDP(int porta, AtomicInteger clientesConectados, GerenciadorAssentos gerenciador, EstadoNo estadoNo) {
        this.porta = porta;
        this.clientesConectados = clientesConectados;
        this.gerenciador = gerenciador;
        this.estadoNo = estadoNo;
    }

    public void iniciar() {
        Thread t = new Thread(this::loop, "StatusUDP");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        try (DatagramSocket socket = new DatagramSocket(porta)) {
            Log.info("StatusUDP escutando na porta " + porta);
            byte[] buffer = new byte[256];
            while (true) {
                DatagramPacket pacoteRecebido = new DatagramPacket(buffer, buffer.length);
                socket.receive(pacoteRecebido);
                String mensagem = new String(pacoteRecebido.getData(), 0, pacoteRecebido.getLength(), StandardCharsets.UTF_8).trim();
                if (Protocolo.UDP_STATUS.equals(mensagem)) {
                    String resposta = montarResposta();
                    byte[] dados = resposta.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket pacoteResposta = new DatagramPacket(dados, dados.length,
                            pacoteRecebido.getAddress(), pacoteRecebido.getPort());
                    socket.send(pacoteResposta);
                    Log.info("UDP STATUS respondido para " + pacoteRecebido.getAddress() + ":" + pacoteRecebido.getPort());
                }
            }
        } catch (Exception e) {
            Log.erro("StatusUDP encerrado: " + e.getMessage());
        }
    }

    private String montarResposta() {
        String ativo = estadoNo.isAtivo() ? "ATIVO" : "INATIVO";
        return Protocolo.montar(Protocolo.UDP_STATUS,
                "CLIENTES=" + clientesConectados.get(),
                "REQ=" + gerenciador.getTotalRequisicoes(),
                ativo,
                "PAPEL=" + estadoNo.getPapel());
    }
}
