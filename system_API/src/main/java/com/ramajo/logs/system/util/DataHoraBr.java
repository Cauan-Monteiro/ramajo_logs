package com.ramajo.logs.system.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Conversão de instantes para a hora de parede da fábrica.
 *
 * Todo carimbo do domínio é um {@link Instant} gravado em timestamptz UTC (ver
 * nota em OrdemServico). A conversão para America/Sao_Paulo acontece só na
 * apresentação — hoje, na planilha de exportação. Este é o único lugar do
 * projeto que conhece o fuso de exibição.
 */
public final class DataHoraBr {

    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private DataHoraBr() {
    }

    /** Null-safe: instante ausente (passo em aberto) continua ausente. */
    public static LocalDateTime local(Instant instante) {
        return instante == null ? null : LocalDateTime.ofInstant(instante, ZONA);
    }

    /**
     * Duração fechada no formato HH:MM:SS (as horas não estouram em 24 —
     * um passo pode durar dias). Intervalo ainda aberto devolve string vazia,
     * para a célula ficar vazia em vez de mentir um zero.
     */
    public static String duracao(Instant inicio, Instant fim) {
        if (inicio == null || fim == null) {
            return "";
        }
        Duration d = Duration.between(inicio, fim);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        return String.format("%02d:%02d:%02d",
                d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
}
