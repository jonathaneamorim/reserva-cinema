package cinema.comum;

/**
 * Constantes e utilitários do protocolo de aplicação usado entre clientes e
 * servidor (via TCP) e também nas mensagens administrativas/replicação.
 *
 * Formato geral: uma linha de texto por mensagem, campos separados por "|".
 */
public final class Protocolo {

    private Protocolo() {
    }

    public static final String SEPARADOR = "\\|";
    public static final String SEP = "|";

    // ---- comandos do cliente (TCP) ----
    public static final String CMD_LISTAR = "LISTAR";
    public static final String CMD_RESERVAR = "RESERVAR";
    public static final String CMD_CANCELAR = "CANCELAR";
    public static final String CMD_SAIR = "SAIR";
    public static final String CMD_ADMIN = "ADMIN";

    // ---- sub-comandos administrativos ----
    public static final String ADMIN_CONTADORES = "CONTADORES";

    // ---- respostas ----
    public static final String RESP_OK = "OK";
    public static final String RESP_ERRO = "ERRO";
    public static final String RESP_LISTA = "LISTA";
    public static final String RESP_BYE = "BYE";
    public static final String RESP_CONTADORES = "CONTADORES";

    // ---- motivos de erro ----
    public static final String ERRO_ASSENTO_OCUPADO = "ASSENTO_OCUPADO";
    public static final String ERRO_ASSENTO_INVALIDO = "ASSENTO_INVALIDO";
    public static final String ERRO_NAO_E_DONO = "NAO_E_DONO";
    public static final String ERRO_NAO_RESERVADO = "NAO_RESERVADO";
    public static final String ERRO_COMANDO_INVALIDO = "COMANDO_INVALIDO";
    public static final String ERRO_NAO_PRIMARIO = "NAO_PRIMARIO";
    public static final String ERRO_FALHA_REPLICACAO = "FALHA_REPLICACAO";

    // ---- protocolo de replicação interna (entre réplicas, TCP próprio) ----
    public static final String REPL_OP = "OP";
    public static final String REPL_ACK = "ACK";
    public static final String REPL_SNAPSHOT_PEDIDO = "SNAPSHOT_PEDIDO";
    public static final String REPL_SNAPSHOT = "SNAPSHOT";
    public static final String REPL_HEARTBEAT = "HEARTBEAT";
    public static final String REPL_HEARTBEAT_ACK = "HEARTBEAT_ACK";
    public static final String REPL_QUEM_E_PRIMARIO = "QUEM_E_PRIMARIO";
    public static final String REPL_SOU_PRIMARIO = "SOU_PRIMARIO";
    public static final String REPL_SOU_BACKUP = "SOU_BACKUP";

    // ---- UDP de status ----
    public static final String UDP_STATUS = "STATUS";

    /** Número de assentos da sessão (fixo, conforme especificação da atividade). */
    public static final int TOTAL_ASSENTOS = 50;

    /** Quebra uma linha recebida em seus campos, sem limite de tamanho de tokens vazios finais. */
    public static String[] partir(String linha) {
        return linha.split(SEPARADOR, -1);
    }

    public static String montar(String... campos) {
        return String.join(SEP, campos);
    }

    public static String respostaOk() {
        return RESP_OK;
    }

    public static String respostaErro(String motivo) {
        return montar(RESP_ERRO, motivo);
    }
}
