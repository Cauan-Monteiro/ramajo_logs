package com.ramajo.logs.system.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Cliente importado do sistema principal de OS.
 *
 * O id vem de fora (135, 830, 21...), então NÃO é gerado aqui: sem
 * {@code @GeneratedValue}. O banco não deve criar IDENTITY que brigaria com
 * os ids importados.
 */
@Entity
@Table(name = "clientes")
public class Cliente {

    @Id
    private Long id;

    @Column(nullable = false, length = 120)
    private String nome;

    protected Cliente() {
    }

    public Cliente(Long id, String nome) {
        this.id = id;
        this.nome = nome;
    }

    public Long getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }
}
