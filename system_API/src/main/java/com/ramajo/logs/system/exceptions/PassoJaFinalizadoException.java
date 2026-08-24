package com.ramajo.logs.system.exceptions;

import java.util.UUID;

/**
 * Tentativa de finalizar um Log que já tem `finalizadoEm` preenchido.
 * Conflito de estado -> HTTP 409.
 *
 * (A mesma regra é garantida no banco pela trigger de imutabilidade parcial;
 * esta exception antecipa o erro com uma mensagem legível.)
 */
public class PassoJaFinalizadoException extends DominioException {

    public PassoJaFinalizadoException(UUID logId) {
        super("PASSO_JA_FINALIZADO", "Passo (log) " + logId + " já foi finalizado.");
    }
}
