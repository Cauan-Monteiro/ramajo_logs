package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Posicao;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public final class ProcessoDtos {

    private ProcessoDtos() {
    }

    public record CriarProcessoDTO(
            @NotBlank String descricao,
            @NotNull Etapa etapa,
            String tagId) {
    }

    public record DefinirPosicoesDTO(@NotEmpty Set<Posicao> posicoes) {
    }

    public record ProcessoDTO(
            Long id, String descricao, Etapa etapa, Set<Posicao> posicoes, String tagId) {

        public static ProcessoDTO from(Processo p) {
            // Set.copyOf força a leitura da coleção LAZY aqui (sessão aberta) e
            // devolve uma cópia imutável, desacoplada do contexto de persistência.
            return new ProcessoDTO(
                    p.getId(), p.getDescricao(), p.getEtapa(),
                    Set.copyOf(p.getPosicoes()), p.getTagId());
        }
    }
}
