package com.ramajo.logs.system.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * A meia-noite daquele dia no fuso da fábrica, como instante.
     *
     * Um filtro por período chega como data de calendário ("de 01/08 a 27/08"),
     * mas o domínio guarda Instant em UTC — a fronteira do dia depende do fuso,
     * e é aqui que ela é resolvida.
     */
    public static Instant inicioDoDia(LocalDate dia) {
        return dia.atStartOfDay(ZONA).toInstant();
    }

    /**
     * A meia-noite do dia SEGUINTE — o fim exclusivo de uma janela que inclui o
     * dia inteiro. Comparar com `< fim` em vez de `<= 23:59:59` não deixa buraco
     * para os milissegundos que o timestamptz guarda.
     */
    public static Instant inicioDoDiaSeguinte(LocalDate dia) {
        return dia.plusDays(1).atStartOfDay(ZONA).toInstant();
    }

    /** Null-safe: instante ausente (passo em aberto) continua ausente. */
    public static LocalDateTime local(Instant instante) {
        return instante == null ? null : LocalDateTime.ofInstant(instante, ZONA);
    }

    /** Só a data de calendário, para quando a planilha separa dia e hora em colunas. */
    public static LocalDate dia(Instant instante) {
        return instante == null ? null : LocalDate.ofInstant(instante, ZONA);
    }

    /**
     * Só a hora do dia, como fração de dia — a unidade de tempo do Excel.
     *
     * Escrever o LocalDateTime inteiro numa célula formatada hh:mm:ss mostraria
     * a mesma coisa, mas arrastaria a data escondida junto: ordenar a coluna
     * agruparia por dia antes de por hora, e comparar duas linhas de dias
     * diferentes daria errado. Aqui a célula é de fato uma hora.
     */
    public static Double horaDoDia(Instant instante) {
        return instante == null
                ? null
                : LocalDateTime.ofInstant(instante, ZONA).toLocalTime().toSecondOfDay() / 86_400d;
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

    /**
     * A mesma duração, mas como fração de dia — a unidade de tempo do Excel.
     * Escrita numa célula com formato [h]:mm:ss, ela continua sendo lida como
     * "01:47:30" e ainda assim entra em SOMA() e MÉDIA().
     *
     * Intervalo ainda aberto devolve null, para a célula nem ser criada: soma
     * ignora célula vazia, mas somaria um zero.
     */
    public static Double duracaoNumerica(Instant inicio, Instant fim) {
        if (inicio == null || fim == null) {
            return null;
        }
        long segundos = Duration.between(inicio, fim).toSeconds();
        return segundos < 0 ? 0d : segundos / 86_400d;
    }
}
