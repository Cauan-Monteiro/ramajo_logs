package com.ramajo.logs.system.exceptions;

/**
 * Pedido de correção de OS que não descreve correção nenhuma. -> HTTP 422.
 */
public class CorrecaoInvalidaException extends DominioException {

    /** Todos os campos iguais aos atuais: nada a gravar, nem no histórico. */
    public static CorrecaoInvalidaException semAlteracao(Long osId) {
        return new CorrecaoInvalidaException("CORRECAO_SEM_ALTERACAO",
                "Nenhum campo da ordem de serviço " + osId + " foi alterado.");
    }

    /**
     * Correção que deixaria a OS sem setor nenhum. Uma ordem que não roda em
     * lugar nenhum não é corrigível nem trabalhável — quem quer parar a OS
     * cancela-a. A trigger trg_ordem_posicoes_exige_uma (V19) garante o mesmo
     * no banco; esta recusa existe para a mensagem ser legível.
     */
    public static CorrecaoInvalidaException semPosicao(Long osId) {
        return new CorrecaoInvalidaException("CORRECAO_SEM_POSICAO",
                "A ordem de serviço " + osId + " precisa de pelo menos um setor.");
    }

    private CorrecaoInvalidaException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
