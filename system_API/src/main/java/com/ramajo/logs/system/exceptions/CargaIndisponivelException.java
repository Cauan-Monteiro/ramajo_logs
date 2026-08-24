package com.ramajo.logs.system.exceptions;

/**
 * A carga já está vinculada a OUTRA ordem de serviço; precisa ser liberada
 * antes de ser reatribuída. Conflito de estado -> HTTP 409.
 */
public class CargaIndisponivelException extends DominioException {

    public CargaIndisponivelException(Long cargaId, Long ordemAtualId) {
        super("CARGA_INDISPONIVEL",
                "Carga " + cargaId + " já está vinculada à OS " + ordemAtualId
                        + "; libere-a antes de reatribuir.");
    }
}
