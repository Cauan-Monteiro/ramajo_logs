package com.ramajo.logs.system.dtos;

public final class EstadoDtos {

    private EstadoDtos() {
    }

    /** Marca de versao do estado; muda a cada escrita e a cada boot da API. */
    public record RevisaoDTO(String instancia, long revisao) {
    }
}
