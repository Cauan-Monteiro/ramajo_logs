package com.ramajo.logs.system.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Incrementa a RevisaoEstado depois de toda escrita bem-sucedida em /api.
 *
 * Fica no filtro, e não nos services, de propósito: assim nenhuma rota nova
 * precisa lembrar de marcar mudança, e OrdemServicoService continua sem saber
 * que existe sincronização de terminais.
 */
@Component
public class RevisaoFilter extends OncePerRequestFilter {

    private final RevisaoEstado revisao;

    public RevisaoFilter(RevisaoEstado revisao) {
        this.revisao = revisao;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        if (mudouEstado(request, response)) revisao.marcarMudanca();
    }

    private static boolean mudouEstado(HttpServletRequest req, HttpServletResponse res) {
        if (!req.getRequestURI().startsWith("/api/")) return false;
        // 4xx/5xx nao mudaram nada: contar erro faria todo terminal recarregar a toa.
        if (res.getStatus() >= 400) return false;
        return switch (req.getMethod()) {
            case "GET", "HEAD", "OPTIONS" -> false;
            default -> true;
        };
    }
}
