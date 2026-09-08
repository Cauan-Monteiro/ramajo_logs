package com.ramajo.logs.system.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Desidrogenização: uma receita de forno do catálogo — nome, quanto tempo dura
 * e uma observação livre.
 *
 * Não tem temperatura: ela é a mesma para todas (ver ConfigDesidrogenizacao).
 * Não conhece OS: aplicar uma receita a uma ordem cria uma
 * OrdemDesidrogenizacao, que copia daqui a duração vigente.
 */
@Entity
@Table(name = "desidrogenizacoes")
public class Desidrogenizacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String nome;

    // Em minutos inteiros: é como o operador pensa e como o formulário digita.
    @Column(name = "duracao_min", nullable = false)
    private Integer duracaoMin;

    @Column(columnDefinition = "text")
    private String observacao;

    // Soft-delete, como em Processo: arquivar tira o cadastro das listas de
    // escolha sem apagar a linha que as aplicações históricas referenciam.
    @Column(nullable = false)
    private boolean ativo = true;

    protected Desidrogenizacao() {
    }

    public Desidrogenizacao(String nome, Integer duracaoMin) {
        this.nome = nome;
        this.duracaoMin = duracaoMin;
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

    public Integer getDuracaoMin() {
        return duracaoMin;
    }

    public void setDuracaoMin(Integer duracaoMin) {
        this.duracaoMin = duracaoMin;
    }

    public String getObservacao() {
        return observacao;
    }

    public void setObservacao(String observacao) {
        this.observacao = observacao;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }
}
