package com.ramajo.logs.system.exceptions;

import com.ramajo.logs.system.enums.Posicao;

/**
 * A composição de uma carga não fecha. Acoplar é dizer "peças destas OS estão
 * na MESMA carga" — uma afirmação sobre o mundo físico, então as recusas aqui
 * são todas de coerência física.
 *
 * Requisição semanticamente inválida -> HTTP 422 (rede de segurança do
 * RestExceptionHandler para DominioException).
 */
public class AcoplamentoInvalidoException extends DominioException {

    /** A titular já é dona da carga; listá-la de novo não significa nada. */
    public static AcoplamentoInvalidoException aSiMesma(Long osId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_A_SI_MESMA",
                "OS " + osId + " é a titular da carga; não pode ser acoplada a ela.");
    }

    /**
     * Setores diferentes: uma carga está fisicamente num setor só, então OS de
     * posições distintas não podem estar a dividi-la.
     */
    public static AcoplamentoInvalidoException posicaoDiferente(Long acopladaId, Posicao acoplada,
                                                                Long titularId, Posicao titular) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_POSICAO_INCOMPATIVEL",
                "OS " + acopladaId + " roda em " + acoplada + " e a OS " + titularId
                        + " (titular da carga) em " + titular
                        + "; a mesma carga não está nos dois setores.");
    }

    /**
     * Teto defensivo. Não há limite físico exato, mas a API não tem
     * autenticação: sem um teto, um POST cru anexaria a fábrica inteira a uma
     * carga. O número real de uso é 2-3.
     */
    public static AcoplamentoInvalidoException demais(int enviadas, int limite) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_EXCEDE_LIMITE",
                "São " + enviadas + " OS acopladas; o limite por carga é " + limite + ".");
    }

    /**
     * Acoplar uma OS que já pega carona noutra carga. As peças dela estão
     * dentro de um tanque; entrar num segundo sem sair do primeiro não
     * descreve nada que possa ter acontecido.
     */
    public static AcoplamentoInvalidoException jaEmOutraCarga(Long osId, Long cargaId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_EM_OUTRA_CARGA",
                "OS " + osId + " já está acoplada à carga " + cargaId
                        + "; desacople de lá antes.");
    }

    /** Desacoplar algo que não estava acoplado: a carga existe, o vínculo não. */
    public static AcoplamentoInvalidoException naoAcoplada(Long cargaId, Long osId) {
        return new AcoplamentoInvalidoException("ACOPLAMENTO_INEXISTENTE",
                "OS " + osId + " não está acoplada à carga " + cargaId + ".");
    }

    private AcoplamentoInvalidoException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
