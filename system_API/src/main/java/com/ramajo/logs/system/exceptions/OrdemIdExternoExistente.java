package com.ramajo.logs.system.exceptions;

    /**
    * A carga já está vinculada a OUTRA ordem de serviço; precisa ser liberada
    * antes de ser reatribuída. Conflito de estado -> HTTP 409.
    */
public class OrdemIdExternoExistente extends DominioException {
    public OrdemIdExternoExistente(Long idExterno) {
        super("OS_EXISTENTE",
                "Ordem de serviço já cadastrada: "+idExterno);
    }
}
