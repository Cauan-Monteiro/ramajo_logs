package com.ramajo.logs.system.exceptions;

/**
 * Tentativa de reabrir uma OS que não está no estado que a reabertura desfaz.
 *
 * Reabrir é o inverso exato da expedição total: só faz sentido sobre uma OS
 * FINALIZADA. Uma OS ainda em produção não tem o que reabrir, e uma cancelada
 * não foi concluída — foi abortada, e ressuscitá-la apagaria essa distinção.
 *
 * Conflito de estado -> HTTP 409, como OrdemForaDeCirculacaoException.
 */
public class ReaberturaInvalidaException extends DominioException {

    /** Já está em produção: não há expedição a desfazer. */
    public static ReaberturaInvalidaException naoFinalizada(Long osId) {
        return new ReaberturaInvalidaException("REABERTURA_OS_NAO_FINALIZADA",
                "Ordem de serviço " + osId + " não está finalizada; não há o que reabrir.");
    }

    /** Cancelada é um fim, não uma pausa. */
    public static ReaberturaInvalidaException cancelada(Long osId) {
        return new ReaberturaInvalidaException("REABERTURA_OS_CANCELADA",
                "Ordem de serviço " + osId + " foi cancelada; só OS expedida pode ser reaberta.");
    }

    /**
     * Defensivo, e o que ux_lotes_os_aberto barraria de qualquer forma: uma OS
     * finalizada não deveria ter lote em aberto.
     */
    public static ReaberturaInvalidaException loteAberto(Long osId) {
        return new ReaberturaInvalidaException("REABERTURA_LOTE_ABERTO",
                "Ordem de serviço " + osId + " já tem um lote em aberto.");
    }

    private ReaberturaInvalidaException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }
}
