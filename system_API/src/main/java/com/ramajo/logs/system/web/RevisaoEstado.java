package com.ramajo.logs.system.web;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Marca de versão do estado do sistema, em memória.
 *
 * Os terminais consultam esta marca de poucos em poucos segundos e só refazem a
 * carga completa (que é cara: catálogos + ordens + um GET de logs por OS em
 * processo) quando ela muda. É o que mantém todos os terminais no mesmo ponto
 * sem manter uma conexão aberta por cliente.
 *
 * `instancia` muda a cada boot da API: sem ela, um restart zeraria o contador e
 * o cliente concluiria "nada mudou" enquanto olha para dados de antes da queda.
 */
@Component
public class RevisaoEstado {

    private final String instancia = UUID.randomUUID().toString();
    private final AtomicLong revisao = new AtomicLong();

    public String instancia() {
        return instancia;
    }

    public long atual() {
        return revisao.get();
    }

    public void marcarMudanca() {
        revisao.incrementAndGet();
    }
}
