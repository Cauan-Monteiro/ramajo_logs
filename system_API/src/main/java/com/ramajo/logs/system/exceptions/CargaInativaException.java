package com.ramajo.logs.system.exceptions;

/**
 * A carga foi sucateada/retirada (ativo = false) e não pode ser designada.
 * Requisição semanticamente inválida -> HTTP 422.
 */
public class CargaInativaException extends DominioException {

    public CargaInativaException(Long cargaId) {
        super("CARGA_INATIVA", "Carga " + cargaId + " está inativa e não pode ser usada.");
    }
}
