package com.ramajo.logs.system.exceptions;

/**
 * O operador está desligado (ativo = false) e não pode iniciar OS nem passos.
 * Requisição semanticamente inválida -> HTTP 422.
 */
public class OperadorInativoException extends DominioException {

    public OperadorInativoException(Long operadorId) {
        super("OPERADOR_INATIVO",
                "Operador " + operadorId + " está inativo e não pode executar esta ação.");
    }
}
