package com.ramajo.logs.system.exceptions;

/**
 * Pedido de correção de OS que não descreve correção nenhuma. -> HTTP 422.
 */
public class CorrecaoInvalidaException extends DominioException {

    /** Todos os campos iguais aos atuais: nada a gravar, nem no histórico. */
    public static CorrecaoInvalidaException semAlteracao(Long osId) {
        return new CorrecaoInvalidaException("CORRECAO_SEM_ALTERACAO",
                "Nenhum campo da ordem de serviço " + osId + " foi alterado.");
    }

    private CorrecaoInvalidaException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
