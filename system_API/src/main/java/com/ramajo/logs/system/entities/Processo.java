package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Posicao;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * Processo de tratamento. Pertence a UMA etapa e pode ocorrer em VÁRIAS
 * posições (setores) — daí `posicoes` ser uma coleção, gravada na tabela
 * associativa `processo_posicoes`.
 */
@Entity
@Table(name = "processos")
public class Processo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String descricao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Etapa etapa;

    // Conjunto de setores onde o processo pode ser executado. Set (não List)
    // porque a mesma posição não se repete e a ordem é irrelevante.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "processo_posicoes",
            joinColumns = @JoinColumn(name = "processo_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "posicao", nullable = false, length = 20)
    private Set<Posicao> posicoes = new HashSet<>();

    @Column(name = "tag_id", length = 64)
    private String tagId;

    // Soft-delete (V8), como em Carga: arquivar tira o processo das listas de
    // escolha sem apagar a linha que os logs históricos referenciam.
    @Column(nullable = false)
    private boolean ativo = true;

    protected Processo() {
    }

    public Processo(String descricao, Etapa etapa) {
        this.descricao = descricao;
        this.etapa = etapa;
    }

    public Long getId() {
        return id;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public Etapa getEtapa() {
        return etapa;
    }

    public void setEtapa(Etapa etapa) {
        this.etapa = etapa;
    }

    public Set<Posicao> getPosicoes() {
        return posicoes;
    }

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }
}
