#!/usr/bin/env bash
# Funcoes auxiliares compartilhadas pelos scripts de teste/experimento.
# Este arquivo e "source-ado" pelos outros scripts, nao e executado diretamente.

DIR_RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Espera até uma porta TCP aceitar conexao (ou desiste depois de N tentativas).
# Uso: aguardar_tcp localhost 5000
aguardar_tcp() {
    local host="$1" porta="$2" tentativas=0
    while ! (exec 3<>"/dev/tcp/$host/$porta") 2>/dev/null; do
        tentativas=$((tentativas + 1))
        if [ "$tentativas" -ge 50 ]; then
            echo "ERRO: porta $host:$porta nao respondeu apos 5s" >&2
            return 1
        fi
        sleep 0.1
    done
    exec 3<&- 3>&- 2>/dev/null || true
    return 0
}

titulo() {
    echo ""
    echo "==================================================================="
    echo "  $1"
    echo "==================================================================="
}

garantir_build() {
    if [ ! -d "$DIR_RAIZ/out" ] || [ -z "$(find "$DIR_RAIZ/out" -name '*.class' -print -quit 2>/dev/null)" ]; then
        echo "Classes nao encontradas em out/ - compilando..."
        bash "$DIR_RAIZ/scripts/build.sh"
    fi
}

# Mata (SIGTERM, depois SIGKILL se necessario) e ignora erro se o processo já tiver saído.
matar_pid() {
    local pid="$1"
    if [ -z "$pid" ]; then
        return 0
    fi
    kill "$pid" 2>/dev/null || true
    sleep 0.3
    kill -9 "$pid" 2>/dev/null || true
}
