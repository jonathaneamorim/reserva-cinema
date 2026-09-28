package cinema.comum;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Utilitário simples de log para console (e, opcionalmente, arquivo via
 * redirecionamento do shell). Todas as mensagens seguem o formato:
 * [timestamp][thread] mensagem
 *
 * Usamos System.out com synchronized para evitar que linhas de threads
 * diferentes fiquem intercaladas no meio (o println de uma thread poderia
 * ser cortado pelo de outra sem essa proteção).
 */
public final class Log {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    public static synchronized void info(String mensagem) {
        String ts = LocalTime.now().format(FORMATO);
        String thread = Thread.currentThread().getName();
        System.out.println("[" + ts + "][" + thread + "] " + mensagem);
    }

    public static synchronized void erro(String mensagem) {
        String ts = LocalTime.now().format(FORMATO);
        String thread = Thread.currentThread().getName();
        System.out.println("[" + ts + "][" + thread + "][ERRO] " + mensagem);
    }
}
