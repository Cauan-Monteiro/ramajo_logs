package com.ramajo.logs.system.web;

import java.time.Instant;
import java.util.List;

/**
 * Corpo JSON padronizado de erro da API.
 *
 * `codigo` é o contrato estável com o front (vem de DominioException.getCodigo()
 * ou de um código sintético para erros de framework); `mensagem` é legível e
 * pode mudar. `campos` só é preenchido em falha de validação de entrada.
 */
public record ApiError(
        String codigo,
        String mensagem,
        int status,
        String path,
        Instant timestamp,
        List<CampoInvalido> campos) {

    public record CampoInvalido(String campo, String erro) {
    }

    public static ApiError of(String codigo, String mensagem, int status, String path) {
        return new ApiError(codigo, mensagem, status, path, Instant.now(), null);
    }

    public static ApiError of(String codigo, String mensagem, int status, String path,
                              List<CampoInvalido> campos) {
        return new ApiError(codigo, mensagem, status, path, Instant.now(), campos);
    }
}
