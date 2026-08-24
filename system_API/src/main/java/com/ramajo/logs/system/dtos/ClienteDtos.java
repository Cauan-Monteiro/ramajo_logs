package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.Cliente;
import jakarta.validation.constraints.NotBlank;

public final class ClienteDtos {

    private ClienteDtos() {
    }

    public record SincronizarClienteDTO(@NotBlank String nome) {
    }

    public record ClienteDTO(Long id, String nome) {

        public static ClienteDTO from(Cliente c) {
            return new ClienteDTO(c.getId(), c.getNome());
        }
    }
}
