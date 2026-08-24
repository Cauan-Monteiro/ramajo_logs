package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.enums.Permissao;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class OperadorDtos {

    private OperadorDtos() {
    }

    public record CriarOperadorDTO(
            @NotBlank String nome,
            @NotNull Permissao permissao,
            String tagId) {
    }

    public record OperadorDTO(
            Long id, String nome, Permissao permissao, boolean ativo, String tagId) {

        public static OperadorDTO from(Operador o) {
            return new OperadorDTO(
                    o.getId(), o.getNome(), o.getPermissao(), o.isAtivo(), o.getTagId());
        }
    }
}
