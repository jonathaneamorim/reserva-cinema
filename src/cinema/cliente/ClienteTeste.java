package cinema.cliente;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Harness de teste de concorrência. Dispara 3 "clientes" (threads, cada um
 * com sua própria conexão TCP) que largam ao mesmo tempo (CountDownLatch) e
 * fazem 10 requisições cada, totalizando 30 requisições - exatamente como
 * pede a atividade.
 *
 * Modos:
 *   --modo=A         cenário A: contador (lost update). Cada cliente reserva
 *                    10 assentos distintos. Esperado: totalReservas=30.
 *   --modo=B         cenário B: dupla reserva (check-then-act). Os 3 clientes
 *                    disputam os mesmos 10 assentos. Esperado: exatamente 10 OK.
 *   --modo=conectar  sobe 3 clientes simultâneos só para mostrar o servidor
 *                    atendendo várias conexões ao mesmo tempo (TESTE 1).
 */
public class ClienteTeste {

    public static void main(String[] args) throws Exception {
        Map<String, String> opcoes = parseArgs(args);
        String host = opcoes.getOrDefault("host", "localhost");
        int porta = Integer.parseInt(opcoes.getOrDefault("porta", "5000"));
        String modo = opcoes.getOrDefault("modo", "A");

        switch (modo) {
            case "A":
                cenarioA(host, porta);
                break;
            case "B":
                cenarioB(host, porta);
                break;
            case "conectar":
                modoConectar(host, porta);
                break;
            default:
                System.out.println("Modo desconhecido: " + modo + " (use A, B ou conectar)");
        }
    }

    // ---------------------------------------------------------------
    // Cenário A: contador (lost update). 3 clientes x 10 assentos distintos.
    // ---------------------------------------------------------------
    private static void cenarioA(String host, int porta) throws InterruptedException {
        System.out.println("=== CENARIO A: contador (lost update) - 3 clientes x 10 assentos distintos ===");
        int numClientes = 3;
        int porCliente = 10;
        CountDownLatch largada = new CountDownLatch(1);
        CountDownLatch chegada = new CountDownLatch(numClientes);
        List<List<String>> respostasPorCliente = new ArrayList<>();
        for (int i = 0; i < numClientes; i++) {
            respostasPorCliente.add(null);
        }

        for (int c = 0; c < numClientes; c++) {
            final int clienteIdx = c;
            Thread t = new Thread(() -> {
                List<Integer> assentos = new ArrayList<>();
                for (int a = 1; a <= porCliente; a++) {
                    assentos.add(clienteIdx * porCliente + a);
                }
                String usuario = "clienteA" + (clienteIdx + 1);
                ConexaoComFailover conexao = new ConexaoComFailover(host + ":" + porta);
                try {
                    largada.await();
                    List<String> respostas = new ArrayList<>();
                    for (int assento : assentos) {
                        respostas.add(conexao.enviarComando("RESERVAR|" + assento + "|" + usuario));
                    }
                    respostasPorCliente.set(clienteIdx, respostas);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    conexao.encerrar();
                    chegada.countDown();
                }
            }, "ClienteTeste-A" + (clienteIdx + 1));
            t.start();
        }

        largada.countDown(); // todos largam juntos
        chegada.await();

        int enviadas = 0, ok = 0, erro = 0;
        for (List<String> respostas : respostasPorCliente) {
            for (String r : respostas) {
                enviadas++;
                if ("OK".equals(r)) {
                    ok++;
                } else {
                    erro++;
                }
            }
        }

        ConexaoComFailover admin = new ConexaoComFailover(host + ":" + porta);
        String contadores = admin.enviarComando("ADMIN|CONTADORES");
        admin.encerrar();
        Map<String, Integer> valores = parseContadores(contadores);
        int totalReservas = valores.getOrDefault("totalReservas", -1);
        int assentosOcupados = valores.getOrDefault("assentosOcupados", -1);

        int esperado = 30;
        boolean consistente = (totalReservas == esperado) && (assentosOcupados == esperado) && (ok == esperado);

        imprimirResumo("A", enviadas, ok, erro, totalReservas, assentosOcupados, esperado, ok, consistente);
    }

    // ---------------------------------------------------------------
    // Cenário B: dupla reserva (check-then-act). 3 clientes disputam os mesmos 10 assentos.
    // ---------------------------------------------------------------
    private static void cenarioB(String host, int porta) throws InterruptedException {
        System.out.println("=== CENARIO B: dupla reserva (check-then-act) - 3 clientes disputando os mesmos 10 assentos ===");
        int numClientes = 3;
        int numAssentos = 10;
        CountDownLatch largada = new CountDownLatch(1);
        CountDownLatch chegada = new CountDownLatch(numClientes);
        List<List<String>> respostasPorCliente = new ArrayList<>();
        for (int i = 0; i < numClientes; i++) {
            respostasPorCliente.add(null);
        }

        for (int c = 0; c < numClientes; c++) {
            final int clienteIdx = c;
            Thread t = new Thread(() -> {
                String usuario = "clienteB" + (clienteIdx + 1);
                ConexaoComFailover conexao = new ConexaoComFailover(host + ":" + porta);
                try {
                    largada.await();
                    List<String> respostas = new ArrayList<>();
                    for (int assento = 1; assento <= numAssentos; assento++) {
                        respostas.add(conexao.enviarComando("RESERVAR|" + assento + "|" + usuario));
                    }
                    respostasPorCliente.set(clienteIdx, respostas);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    conexao.encerrar();
                    chegada.countDown();
                }
            }, "ClienteTeste-B" + (clienteIdx + 1));
            t.start();
        }

        largada.countDown();
        chegada.await();

        int enviadas = 0, ok = 0, erro = 0;
        for (List<String> respostas : respostasPorCliente) {
            for (String r : respostas) {
                enviadas++;
                if ("OK".equals(r)) {
                    ok++;
                } else {
                    erro++;
                }
            }
        }

        ConexaoComFailover admin = new ConexaoComFailover(host + ":" + porta);
        String contadores = admin.enviarComando("ADMIN|CONTADORES");
        admin.encerrar();
        Map<String, Integer> valores = parseContadores(contadores);
        int totalReservas = valores.getOrDefault("totalReservas", -1);
        int assentosOcupados = valores.getOrDefault("assentosOcupados", -1);

        int esperado = 10;
        boolean consistente = (ok == esperado) && (assentosOcupados == esperado);

        imprimirResumo("B", enviadas, ok, erro, totalReservas, assentosOcupados, esperado, ok, consistente);
    }

    private static void imprimirResumo(String cenario, int enviadas, int ok, int erro,
                                        int totalReservasServidor, int assentosOcupados,
                                        int esperado, int observado, boolean consistente) {
        System.out.println();
        System.out.println("--- Resumo cenario " + cenario + " ---");
        System.out.println("Requisicoes enviadas : " + enviadas);
        System.out.println("Respostas OK          : " + ok);
        System.out.println("Respostas ERRO        : " + erro);
        System.out.println("totalReservas (servidor): " + totalReservasServidor);
        System.out.println("Assentos ocupados de fato: " + assentosOcupados);
        System.out.println("Esperado vs observado : " + esperado + " vs " + observado);
        System.out.println("VEREDITO: " + (consistente ? "CONSISTENTE" : "CONDICAO DE CORRIDA DETECTADA"));
        System.out.println();
    }

    private static Map<String, Integer> parseContadores(String resposta) {
        // formato: CONTADORES|totalReservas=30|totalRequisicoes=30|assentosOcupados=30
        Map<String, Integer> valores = new HashMap<>();
        String[] campos = resposta.split("\\|");
        for (String campo : campos) {
            int idx = campo.indexOf('=');
            if (idx > 0) {
                try {
                    valores.put(campo.substring(0, idx), Integer.parseInt(campo.substring(idx + 1)));
                } catch (NumberFormatException ignored) {
                    // campo não numérico (ex.: o próprio nome "CONTADORES"), ignora
                }
            }
        }
        return valores;
    }

    // ---------------------------------------------------------------
    // Modo "conectar": 3 clientes conectados ao mesmo tempo, fazendo LISTAR.
    // ---------------------------------------------------------------
    private static void modoConectar(String host, int porta) throws InterruptedException {
        System.out.println("=== 3 clientes conectando simultaneamente ao servidor " + host + ":" + porta + " ===");
        CountDownLatch chegada = new CountDownLatch(3);
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                ConexaoComFailover conexao = new ConexaoComFailover(host + ":" + porta);
                String resposta = conexao.enviarComando("LISTAR");
                System.out.println("Cliente " + n + " -> " + resposta);
                conexao.encerrar();
                chegada.countDown();
            }, "ClienteTeste-Conectar" + n);
            t.start();
        }
        chegada.await();
        System.out.println("Os 3 clientes conectaram, consultaram e se desconectaram com sucesso.");
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
