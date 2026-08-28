package com.ramajo.logs.system.exceptions;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * O intervalo pedido num relatório não fecha (fim antes do início).
 * Mapeada para HTTP 400 pelo advice — é entrada malformada, não conflito de
 * estado do domínio, por isso não cai no 422 padrão de DominioException.
 */
public class PeriodoInvalidoException extends DominioException {

    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public PeriodoInvalidoException(LocalDate inicio, LocalDate fim) {
        super("PERIODO_INVALIDO",
                "A data final (" + fim.format(BR) + ") é anterior à inicial ("
                        + inicio.format(BR) + ").");
    }
}
