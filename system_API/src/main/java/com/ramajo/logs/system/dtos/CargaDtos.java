package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class CargaDtos {

    private CargaDtos() {
    }

    public record CriarCargaDTO(
            @NotBlank String nome,
            @NotNull TipoCarga tipo,
            @NotNull Posicao posicao,
            String tagId) {
    }

    /**
     * `ordensAcopladas` são as OS que pegam carona nesta carga — as peças delas
     * estão no mesmo tanque que as da titular (`ordemAtualId`). Vem na listagem
     * porque é o que o front precisa para oferecer o acoplamento sem uma
     * chamada por carga; o @BatchSize da entidade resolve a coleção em lote.
     */
    public record CargaDTO(
            Long id, String nome, TipoCarga tipo, Posicao posicao,
            boolean ativo, boolean emUso, Long ordemAtualId, String tagId,
            List<Long> ordensAcopladas) {

        public static CargaDTO from(Carga c) {
            // getOrdemAtual().getId() num proxy LAZY NÃO inicializa a OS: o id já
            // é conhecido. Só chamamos getId() se a referência não for nula.
            Long ordemAtualId = c.getOrdemAtual() != null ? c.getOrdemAtual().getId() : null;
            return new CargaDTO(
                    c.getId(), c.getNome(), c.getTipo(), c.getPosicao(),
                    c.isAtivo(), c.isEmUso(), ordemAtualId, c.getTagId(),
                    List.copyOf(c.getOrdensAcopladas()));
        }
    }
}
