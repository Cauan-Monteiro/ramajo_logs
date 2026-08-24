package com.ramajo.logs.system.exceptions;

/**
 * Tentativa de operar sobre uma OS que já está finalizada OU cancelada.
 * Conflito de estado -> HTTP 409.
 *
 * (Cobre os dois casos que o `carregarAberta` barra; por isso o nome é "fora de
 * circulação" e não "finalizada", que seria estreito demais.)
 */
public class OrdemForaDeCirculacaoException extends DominioException {

    public OrdemForaDeCirculacaoException(Long osId) {
        super("ORDEM_FORA_DE_CIRCULACAO",
                "Ordem de serviço " + osId + " está fora de circulação (finalizada ou cancelada).");
    }
}
