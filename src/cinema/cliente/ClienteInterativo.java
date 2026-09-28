package cinema.cliente;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * CLI simples para demonstração ao vivo. O usuário digita comandos no
 * formato exato do protocolo (ver docs/protocolo.md):
 *
 *   LISTAR
 *   RESERVAR|<assento>|<usuario>
 *   CANCELAR|<assento>|<usuario>
 *   SAIR
 *
 * Usa ConexaoComFailover, então se a réplica atual cair no meio da
 * demonstração, o próprio cliente troca de réplica sem duplicar reservas.
 */
public class ClienteInterativo {

    public static void main(String[] args) throws Exception {
        Map<String, String> opcoes = parseArgs(args);
        String replicas = opcoes.getOrDefault("replicas", "localhost:5000,localhost:5001");

        ConexaoComFailover conexao = new ConexaoComFailover(replicas);

        System.out.println("=== Cliente interativo - Sistema de Reservas de Cinema ===");
        System.out.println("Replicas configuradas: " + replicas);
        System.out.println("Comandos: LISTAR | RESERVAR|<assento>|<usuario> | CANCELAR|<assento>|<usuario> | SAIR");
        System.out.println();

        boolean jaEnviouSair = false;
        try (BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in))) {
            String linha;
            while (true) {
                System.out.print("> ");
                linha = teclado.readLine();
                if (linha == null) {
                    break;
                }
                linha = linha.trim();
                if (linha.isEmpty()) {
                    continue;
                }
                String resposta = conexao.enviarComando(linha);
                System.out.println(resposta);
                if ("SAIR".equalsIgnoreCase(linha)) {
                    jaEnviouSair = true;
                    break;
                }
            }
        }
        if (jaEnviouSair) {
            conexao.fecharSilenciosamente();
        } else {
            conexao.encerrar();
        }
        System.out.println("Cliente encerrado.");
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
