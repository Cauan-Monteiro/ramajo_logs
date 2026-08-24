package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class CargaDtos {

    private CargaDtos() {
    }

    public record CriarCargaDTO(
            @NotBlank String nome,
            @NotNull TipoCarga tipo,
            @NotNull Posicao posicao,
            String tagId) {
    }

    public record CargaDTO(
            Long id, String nome, TipoCarga tipo, Posicao posicao,
            boolean ativo, boolean emUso, Long ordemAtualId, String tagId) {

        public static CargaDTO from(Carga c) {
            // getOrdemAtual().getId() num proxy LAZY NÃO inicializa a OS: o id já
            // é conhecido. Só chamamos getId() se a referência não for nula.
            Long ordemAtualId = c.getOrdemAtual() != null ? c.getOrdemAtual().getId() : null;
            return new CargaDTO(
                    c.getId(), c.getNome(), c.getTipo(), c.getPosicao(),
                    c.isAtivo(), c.isEmUso(), ordemAtualId, c.getTagId());
        }
    }
}
