package com.ramajo.logs.system.exceptions;

/**
 * Ponto da avaliação com valor fora do contrato `null | true | "texto"`.
 * -> HTTP 422.
 */
public class AvaliacaoInvalidaException extends DominioException {

    public AvaliacaoInvalidaException(String item, String motivo) {
        super("AVALIACAO_INVALIDA", "Avaliação inválida em '" + item + "': " + motivo);
    }
}
