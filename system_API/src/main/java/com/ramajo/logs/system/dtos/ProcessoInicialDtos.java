package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.Posicao;
import jakarta.validation.constraints.NotNull;

public final class ProcessoInicialDtos {

    private ProcessoInicialDtos() {
    }

    public record DefinirProcessoInicialDTO(@NotNull Long processoId) {
    }

    public record ProcessoInicialDTO(
            Posicao posicao, Long processoId, String processoDescricao) {

        /** Toca o proxy LAZY de `processo` aqui, com a sessão ainda aberta. */
        public static ProcessoInicialDTO from(ProcessoInicial pi) {
            return new ProcessoInicialDTO(
                    pi.getPosicao(),
                    pi.getProcesso().getId(),
                    pi.getProcesso().getDescricao());
        }
    }
}
