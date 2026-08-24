package com.ramajo.logs.system.exceptions;

/**
 * Raiz de todas as exceptions de regra de negócio.
 *
 * Estende RuntimeException (unchecked) de propósito: o Spring faz rollback da
 * transação automaticamente para RuntimeException, e você não precisa poluir as
 * assinaturas dos services com `throws`.
 *
 * Não conhece HTTP. Quem traduz `codigo`/tipo em status (404, 409, 422...) é o
 * @RestControllerAdvice na camada web — assim o domínio não depende do Spring MVC.
 */
public abstract class DominioException extends RuntimeException {

    // Código estável e legível por máquina, para o corpo JSON do erro.
    // A mensagem pode mudar; o código é contrato com o front.
    private final String codigo;

    protected DominioException(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
