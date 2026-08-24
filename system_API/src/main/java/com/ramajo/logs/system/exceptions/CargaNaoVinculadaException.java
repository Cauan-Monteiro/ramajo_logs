package com.ramajo.logs.system.exceptions;

/**
 * Tentativa de registrar um passo (Log) para uma carga que não está vinculada
 * à OS informada. Requisição semanticamente inválida -> HTTP 422.
 */
public class CargaNaoVinculadaException extends DominioException {

    public CargaNaoVinculadaException(Long cargaId, Long osId) {
        super("CARGA_NAO_VINCULADA",
                "Carga " + cargaId + " não está vinculada à OS " + osId + ".");
    }

    /** Fluxo por tag: não há OS a inferir porque a carga está livre. */
    public CargaNaoVinculadaException(Long cargaId) {
        super("CARGA_NAO_VINCULADA",
                "Carga " + cargaId + " não está vinculada a nenhuma OS em processo.");
    }
}
