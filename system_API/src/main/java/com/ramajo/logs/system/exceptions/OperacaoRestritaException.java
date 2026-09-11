package com.ramajo.logs.system.exceptions;

/**
 * Operação que só um ADMIN ativo pode fazer — a correção de OS e a avaliação
 * feita fora da expedição.
 *
 * A API não tem autenticação: quem chama informa o `operadorId`, e esta é a
 * conferência que dá para fazer com isso. Não impede quem conhece o id de um
 * admin, mas impede o terminal de chão de fábrica de corrigir por engano.
 * -> HTTP 403.
 */
public class OperacaoRestritaException extends DominioException {
    public OperacaoRestritaException(Long operadorId) {
        this(operadorId, "corrigir OS");
    }

    /** `acao` completa a frase "só ADMIN pode ...". */
    public OperacaoRestritaException(Long operadorId, String acao) {
        super("SOMENTE_ADMIN",
                "Operador " + operadorId + " não é um ADMIN ativo; só ADMIN pode " + acao + ".");
    }
}
