package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;

/**
 * A lista de OS acopladas a um passo não fecha. Acoplar é dizer "peças destas
 * OS estavam na MESMA carga quando o processo rodou" — uma afirmação sobre o
 * mundo físico, então as recusas aqui são todas de coerência física.
 *
 * Requisição semanticamente inválida -> HTTP 422 (rede de segurança do
 * RestExceptionHandler para DominioException).
 */
public class AcoplamentoInvalidoException extends DominioException {

    /** A titular já é dona do passo; listá-la de novo não significa nada. */
    public static AcoplamentoInvalidoException aSiMesma(Long osId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_A_SI_MESMA",
                "OS " + osId + " é a titular do passo; não pode ser acoplada a ele.");
    }

    /**
     * Setores diferentes: uma carga está fisicamente num setor só, então OS de
     * posições distintas não podem ter dividido a mesma carga.
     */
    public static AcoplamentoInvalidoException posicaoDiferente(Long acopladaId, Posicao acoplada,
                                                                Long titularId, Posicao titular) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_POSICAO_INCOMPATIVEL",
                "OS " + acopladaId + " roda em " + acoplada + " e a OS " + titularId
                        + " (titular do passo) em " + titular
                        + "; a mesma carga não está nos dois setores.");
    }

    /**
     * Teto defensivo. Não há limite físico exato, mas a API não tem
     * autenticação: sem um teto, um POST cru anexaria a fábrica inteira a um
     * passo. O número real de uso é 2-3.
     */
    public static AcoplamentoInvalidoException demais(int enviadas, int limite) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_EXCEDE_LIMITE",
                "São " + enviadas + " OS acopladas; o limite por passo é " + limite + ".");
    }

    /**
     * Acoplar uma OS que já pega carona noutro passo aberto. As peças dela
     * estão dentro de um tanque; entrar num segundo sem sair do primeiro não
     * descreve nada que possa ter acontecido.
     */
    public static AcoplamentoInvalidoException jaEmOutroPasso(Long osId, java.util.UUID logId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_EM_OUTRO_PASSO",
                "OS " + osId + " já está acoplada ao passo aberto " + logId
                        + "; desacople de lá antes.");
    }

    /**
     * Passo cancelado não recebe composição nova: ele afirma que o processo
     * NÃO aconteceu, então pendurar OS nele seria registrar carona num
     * evento inexistente.
     */
    public static AcoplamentoInvalidoException passoCancelado(java.util.UUID logId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_PASSO_CANCELADO",
                "O passo " + logId + " está cancelado; não recebe OS acopladas.");
    }

    /** Desacoplar algo que não estava acoplado: o passo existe, o vínculo não. */
    public static AcoplamentoInvalidoException naoAcoplada(java.util.UUID logId, Long osId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_INEXISTENTE",
                "OS " + osId + " não está acoplada ao passo " + logId + ".");
    }

    private AcoplamentoInvalidoException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
