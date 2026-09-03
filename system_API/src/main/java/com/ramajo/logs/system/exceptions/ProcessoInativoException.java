package com.ramajo.logs.system.exceptions;

/**
 * O processo foi arquivado (ativo = false) e não pode mais ser escolhido —
 * nem para abrir um passo, nem como entrada de um setor. Continua válido para
 * os registros que já o usaram. Requisição semanticamente inválida -> HTTP 422.
 */
public class ProcessoInativoException extends DominioException {

    public ProcessoInativoException(Long processoId, String descricao) {
        super("PROCESSO_INATIVO",
                "Processo " + processoId + " (" + descricao + ") está arquivado"
                        + " e não pode ser usado.");
    }
}
