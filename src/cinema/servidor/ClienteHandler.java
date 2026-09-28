package cinema.servidor;

import cinema.comum.Log;
import cinema.comum.Protocolo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uma instância desta classe roda em sua própria thread e atende exatamente
 * um cliente TCP, do primeiro comando até o SAIR ou a queda da conexão.
 * É isso que permite 3+ clientes simultâneos: cada um tem sua própria thread,
 * e todos compartilham o mesmo GerenciadorAssentos (o recurso protegido pela
 * região crítica).
 */
public class ClienteHandler implements Runnable {

    private final Socket socket;
    private final GerenciadorAssentos gerenciador;
    private final Replicador replicador;
    private final EstadoNo estadoNo;
    private final AtomicInteger clientesConectados;

    public ClienteHandler(Socket socket, GerenciadorAssentos gerenciador, Replicador replicador,
                           EstadoNo estadoNo, AtomicInteger clientesConectados) {
        this.socket = socket;
        this.gerenciador = gerenciador;
        this.replicador = replicador;
        this.estadoNo = estadoNo;
        this.clientesConectados = clientesConectados;
    }

    @Override
    public void run() {
        clientesConectados.incrementAndGet();
        Log.info("Cliente conectado: " + socket.getRemoteSocketAddress()
                + " (total conectados=" + clientesConectados.get() + ")");
        try (Socket s = socket;
             BufferedReader entrada = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream saida = s.getOutputStream()) {

            String linha;
            boolean saiuComSair = false;
            while ((linha = entrada.readLine()) != null) {
                String resposta = processarComando(linha);
                saida.write((resposta + "\n").getBytes(StandardCharsets.UTF_8));
                saida.flush();
                if (Protocolo.CMD_SAIR.equals(linha.trim())) {
                    saiuComSair = true;
                    break;
                }
            }
            if (saiuComSair) {
                Log.info("Cliente encerrou a sessao normalmente (SAIR): " + s.getRemoteSocketAddress());
            } else {
                Log.info("Cliente desconectou sem enviar SAIR (EOF/conexao fechada pelo outro lado): "
                        + s.getRemoteSocketAddress());
            }
        } catch (SocketException e) {
            Log.info("Cliente desconectou abruptamente (SocketException): " + e.getMessage());
        } catch (IOException e) {
            Log.info("Cliente desconectou (IOException): " + e.getMessage());
        } finally {
            clientesConectados.decrementAndGet();
            Log.info("Conexao encerrada. Total conectados=" + clientesConectados.get());
        }
    }

    private String processarComando(String linhaBruta) {
        String linha = linhaBruta.trim();
        if (linha.isEmpty()) {
            return Protocolo.respostaErro(Protocolo.ERRO_COMANDO_INVALIDO);
        }
        String[] campos = Protocolo.partir(linha);
        String comando = campos[0];

        try {
            switch (comando) {
                case Protocolo.CMD_LISTAR:
                    if (!estadoNo.isPrimaria()) {
                        return Protocolo.respostaErro(Protocolo.ERRO_NAO_PRIMARIO);
                    }
                    return Protocolo.montar(Protocolo.RESP_LISTA, gerenciador.listar());

                case Protocolo.CMD_RESERVAR:
                    if (!estadoNo.isPrimaria()) {
                        return Protocolo.respostaErro(Protocolo.ERRO_NAO_PRIMARIO);
                    }
                    if (campos.length != 3) {
                        return Protocolo.respostaErro(Protocolo.ERRO_COMANDO_INVALIDO);
                    }
                    return executarComReplicacao(Protocolo.CMD_RESERVAR, campos[1], campos[2]);

                case Protocolo.CMD_CANCELAR:
                    if (!estadoNo.isPrimaria()) {
                        return Protocolo.respostaErro(Protocolo.ERRO_NAO_PRIMARIO);
                    }
                    if (campos.length != 3) {
                        return Protocolo.respostaErro(Protocolo.ERRO_COMANDO_INVALIDO);
                    }
                    return executarComReplicacao(Protocolo.CMD_CANCELAR, campos[1], campos[2]);

                case Protocolo.CMD_SAIR:
                    return Protocolo.RESP_BYE;

                case Protocolo.CMD_ADMIN:
                    if (campos.length == 2 && Protocolo.ADMIN_CONTADORES.equals(campos[1])) {
                        return gerenciador.contadoresParaAdmin();
                    }
                    return Protocolo.respostaErro(Protocolo.ERRO_COMANDO_INVALIDO);

                default:
                    return Protocolo.respostaErro(Protocolo.ERRO_COMANDO_INVALIDO);
            }
        } catch (NumberFormatException e) {
            return Protocolo.respostaErro(Protocolo.ERRO_ASSENTO_INVALIDO);
        }
    }

    /**
     * Executa a operação localmente (dentro da região crítica do
     * GerenciadorAssentos) e, se bem sucedida, replica para a backup ANTES
     * de responder ao cliente - é isso que dá consistência forte enquanto a
     * backup estiver disponível.
     */
    private String executarComReplicacao(String comando, String assentoStr, String usuario) {
        int assento = Integer.parseInt(assentoStr);
        String resultado = Protocolo.CMD_RESERVAR.equals(comando)
                ? gerenciador.reservar(assento, usuario)
                : gerenciador.cancelar(assento, usuario);

        if (Protocolo.RESP_OK.equals(resultado)) {
            replicador.replicarOperacao(comando, assento, usuario);
        }
        return resultado;
    }
}
