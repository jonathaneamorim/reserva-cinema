package cinema.cliente;

import cinema.comum.Log;
import cinema.comum.Protocolo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsula a conexão do cliente com o conjunto de réplicas, com failover
 * automático: se a réplica atual cair ou responder ERRO|NAO_PRIMARIO, esta
 * classe fecha a conexão, avança para a próxima réplica da lista e REENVIA
 * o mesmo comando. Isso não duplica reservas porque o servidor trata
 * RESERVAR/CANCELAR de forma idempotente (ver Protocolo e GerenciadorAssentos).
 */
public class ConexaoComFailover {

    private final List<String> hosts = new ArrayList<>();
    private final List<Integer> portas = new ArrayList<>();
    private int indiceAtual = 0;

    private Socket socket;
    private BufferedReader entrada;
    private OutputStream saida;

    /** @param listaReplicas ex.: "localhost:5000,localhost:5001" */
    public ConexaoComFailover(String listaReplicas) {
        for (String par : listaReplicas.split(",")) {
            String[] partes = par.trim().split(":");
            hosts.add(partes[0]);
            portas.add(Integer.parseInt(partes[1]));
        }
    }

    public String replicaAtualDescricao() {
        return hosts.get(indiceAtual) + ":" + portas.get(indiceAtual);
    }

    /**
     * Envia um comando e devolve a resposta, tentando outras réplicas em
     * caso de falha de conexão ou de resposta ERRO|NAO_PRIMARIO.
     */
    public String enviarComando(String comando) {
        int maxTentativas = hosts.size() * 2 + 1; // dá pelo menos uma volta completa na lista
        for (int tentativa = 0; tentativa < maxTentativas; tentativa++) {
            try {
                garantirConectado();
                saida.write((comando + "\n").getBytes(StandardCharsets.UTF_8));
                saida.flush();
                String resposta = entrada.readLine();
                if (resposta == null) {
                    throw new IOException("conexao fechada pelo servidor");
                }
                if (respostaIndicaTrocarDeReplica(resposta)) {
                    Log.info("Replica " + replicaAtualDescricao() + " respondeu " + resposta
                            + " - tentando a proxima replica com o mesmo comando.");
                    fecharConexaoAtual();
                    avancarReplica();
                    continue;
                }
                return resposta;
            } catch (IOException e) {
                Log.info("Falha ao falar com " + replicaAtualDescricao() + ": " + e.getMessage()
                        + " - tentando a proxima replica com o mesmo comando.");
                fecharConexaoAtual();
                avancarReplica();
            }
        }
        return Protocolo.respostaErro("SEM_REPLICA_DISPONIVEL");
    }

    private boolean respostaIndicaTrocarDeReplica(String resposta) {
        return resposta.equals(Protocolo.montar(Protocolo.RESP_ERRO, Protocolo.ERRO_NAO_PRIMARIO));
    }

    private void garantirConectado() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        String host = hosts.get(indiceAtual);
        int porta = portas.get(indiceAtual);
        socket = new Socket(host, porta);
        entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        saida = socket.getOutputStream();
    }

    private void fecharConexaoAtual() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // já estamos tratando uma falha; nada mais a fazer aqui.
        }
        socket = null;
    }

    private void avancarReplica() {
        indiceAtual = (indiceAtual + 1) % hosts.size();
    }

    /** Envia SAIR (se ainda houver conexao aberta) e fecha. Use quando o comando SAIR ainda nao foi enviado. */
    public void encerrar() {
        try {
            if (socket != null && !socket.isClosed()) {
                enviarComando(Protocolo.CMD_SAIR);
            }
        } catch (Exception ignored) {
            // encerrando mesmo assim
        }
        fecharConexaoAtual();
    }

    /** Fecha a conexao sem falar o protocolo. Use quando um SAIR ja foi enviado e respondido com BYE. */
    public void fecharSilenciosamente() {
        fecharConexaoAtual();
    }
}
