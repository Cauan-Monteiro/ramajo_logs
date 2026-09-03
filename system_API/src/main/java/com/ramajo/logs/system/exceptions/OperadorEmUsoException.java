package com.ramajo.logs.system.exceptions;

/**
 * Recusa de exclusão definitiva de um operador.
 *
 * Ao contrário dos processos, aqui o histórico É motivo de recusa: o hard delete
 * apaga a linha de verdade, e `logs.responsavel_id` é NOT NULL — quem já assinou
 * um passo não pode sumir sem levar o passo junto. Nesse caso resta desativar,
 * que tira o operador do início de turno e preserva tudo.
 *
 * O último administrador ativo também não sai: sem ADMIN ninguém mais abre a
 * aba de Ajustes, e não há rota para criar o primeiro de volta pela tela.
 *
 * Conflito de estado -> HTTP 409.
 */
public class OperadorEmUsoException extends DominioException {

    /** Já assinou passos, ordens ou lotes. */
    public OperadorEmUsoException(Long operadorId, String nome,
                                  long passos, long ordens, long lotes) {
        super("OPERADOR_EM_USO",
                "Operador " + operadorId + " (" + nome + ") tem " + passos + " passo(s), "
                        + ordens + " ordem(ns) e " + lotes + " lote(s) registrados e não pode ser"
                        + " excluído. Desative-o para tirá-lo do início de turno sem apagar o"
                        + " histórico.");
    }

    /** É o único ADMIN ativo. */
    public OperadorEmUsoException(Long operadorId, String nome) {
        super("OPERADOR_EM_USO",
                "Operador " + operadorId + " (" + nome + ") é o único administrador ativo."
                        + " Promova outro operador a administrador antes.");
    }
}
