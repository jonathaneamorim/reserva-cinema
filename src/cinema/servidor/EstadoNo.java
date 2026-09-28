package cinema.servidor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado de papel do nó (réplica) dentro do esquema primária-backup.
 *
 * É consultado por várias threads ao mesmo tempo (ClienteHandler, Replicador,
 * StatusUDP), por isso usamos referências atômicas em vez de um simples
 * campo mutável comum.
 */
public class EstadoNo {

    public enum Papel { PRIMARIA, BACKUP }

    private final AtomicReference<Papel> papel;
    private final AtomicBoolean ativo = new AtomicBoolean(true);

    public EstadoNo(Papel papelInicial) {
        this.papel = new AtomicReference<>(papelInicial);
    }

    public Papel getPapel() {
        return papel.get();
    }

    public boolean isPrimaria() {
        return papel.get() == Papel.PRIMARIA;
    }

    public void promoverAPrimaria() {
        papel.set(Papel.PRIMARIA);
    }

    public void assumirComoBackup() {
        papel.set(Papel.BACKUP);
    }

    public boolean isAtivo() {
        return ativo.get();
    }

    public void marcarInativo() {
        ativo.set(false);
    }
}
