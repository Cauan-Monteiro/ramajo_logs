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

    /**
     * Serve o POST e o PUT: um processo é cadastrado inteiro de uma vez.
     *
     * `posicoes` é @NotEmpty porque processo sem setor nenhum não roda em lugar
     * algum — abrirLog o recusa com PROCESSO_SEM_POSICAO. Melhor barrar no
     * cadastro do que deixar o erro aparecer na hora de movimentar a carga.
     */
    public record CriarProcessoDTO(
            @NotBlank String descricao,
            @NotNull Etapa etapa,
            String tagId,
            @NotEmpty Set<Posicao> posicoes) {
    }

    public record DefinirPosicoesDTO(@NotEmpty Set<Posicao> posicoes) {
    }

    public record ProcessoDTO(
            Long id, String descricao, Etapa etapa, Set<Posicao> posicoes, String tagId,
            boolean ativo) {

        public static ProcessoDTO from(Processo p) {
            // Set.copyOf força a leitura da coleção LAZY aqui (sessão aberta) e
            // devolve uma cópia imutável, desacoplada do contexto de persistência.
            return new ProcessoDTO(
                    p.getId(), p.getDescricao(), p.getEtapa(),
                    Set.copyOf(p.getPosicoes()), p.getTagId(), p.isAtivo());
        }
    }
}
