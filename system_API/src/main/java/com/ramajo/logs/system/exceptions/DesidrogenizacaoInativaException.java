package com.ramajo.logs.system.exceptions;

/**
 * A desidrogenização foi arquivada (ativo = false) e não pode mais ser aplicada
 * a uma OS. Continua válida para as aplicações que já a usaram. Requisição
 * semanticamente inválida -> HTTP 422. Espelha ProcessoInativoException.
 */
public class DesidrogenizacaoInativaException extends DominioException {

    public DesidrogenizacaoInativaException(Long id, String nome) {
        super("DESIDROGENIZACAO_INATIVA",
                "Desidrogenização " + id + " (" + nome + ") está arquivada"
                        + " e não pode ser aplicada.");
    }
}
