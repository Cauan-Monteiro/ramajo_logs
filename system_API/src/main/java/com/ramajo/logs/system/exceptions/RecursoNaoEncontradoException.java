package com.ramajo.logs.system.exceptions;

/**
 * Uma entidade referenciada por id não existe.
 * Mapeada para HTTP 404 pelo advice.
 *
 * Um único tipo para todos os recursos (cliente, operador, carga, processo,
 * log, OS): o nome do recurso vai na mensagem, não em subclasses separadas.
 */
public class RecursoNaoEncontradoException extends DominioException {

    public RecursoNaoEncontradoException(String recurso, Object id) {
        super("RECURSO_NAO_ENCONTRADO", recurso + " não encontrado(a): " + id);
    }

    public RecursoNaoEncontradoException(String mensagem) {
        super("RECURSO_NAO_ENCONTRADO", mensagem);
    }
}
