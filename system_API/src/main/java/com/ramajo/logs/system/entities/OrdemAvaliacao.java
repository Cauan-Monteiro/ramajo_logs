package com.ramajo.logs.system.entities;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A avaliação da inspeção final de uma OS: quatro pontos conferidos (cada um
 * com observação própria opcional), uma observação geral, o "verificado" —
 * gravado ao salvar — e quem avaliou.
 *
 * Uma por OS (UNIQUE em V15). Avaliar de novo não cria linha: {@link #substituir}
 * troca tudo na mesma, e o avaliador passa a ser quem salvou por último.
 *
 * Sem @OneToOne do lado de OrdemServico de propósito: o lado inverso de um
 * one-to-one é carregado eager pelo Hibernate, e a listagem de OS pagaria uma
 * query por ordem. Quem precisa da avaliação a busca pelo repositório.
 */
@Entity
@Table(name = "ordem_avaliacoes")
public class OrdemAvaliacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordem_servico_id", unique = true, updatable = false)
    private OrdemServico ordemServico;

    @Column(name = "is_verificado", nullable = false)
    private boolean verificado;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "avaliado", column = @Column(name = "visual_avaliado", nullable = false)),
            @AttributeOverride(name = "observacao", column = @Column(name = "visual_observacao", length = 500))})
    private ItemAvaliacao visual;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "avaliado", column = @Column(name = "aderencia_avaliado", nullable = false)),
            @AttributeOverride(name = "observacao", column = @Column(name = "aderencia_observacao", length = 500))})
    private ItemAvaliacao aderencia;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "avaliado", column = @Column(name = "embalagem_avaliado", nullable = false)),
            @AttributeOverride(name = "observacao", column = @Column(name = "embalagem_observacao", length = 500))})
    private ItemAvaliacao embalagem;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "avaliado", column = @Column(name = "camada_avaliado", nullable = false)),
            @AttributeOverride(name = "observacao", column = @Column(name = "camada_observacao", length = 500))})
    private ItemAvaliacao camada;

    @Column(length = 500)
    private String observacao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "avaliada_por_id")
    private Operador avaliadaPor;

    // Relógio do Postgres no INSERT (default) e no UPDATE (trigger da V15).
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @ColumnDefault("clock_timestamp()")
    @Column(name = "avaliada_em", nullable = false)
    private Instant avaliadaEm;

    protected OrdemAvaliacao() {
    }

    public OrdemAvaliacao(OrdemServico ordemServico, Dados dados, Operador avaliadaPor) {
        this.ordemServico = ordemServico;
        substituir(dados, avaliadaPor);
    }

    /** Os campos que o avaliador preenche, já validados. */
    public record Dados(ItemAvaliacao visual, ItemAvaliacao aderencia,
                        ItemAvaliacao embalagem, ItemAvaliacao camada, String observacao) {
    }

    /**
     * Salvar é concluir a avaliação: `verificado` não é escolha do avaliador,
     * é o registo de que ele a deu por feita. A OS expedida sem avaliar
     * simplesmente não tem linha.
     */
    public void substituir(Dados dados, Operador avaliadaPor) {
        this.verificado = true;
        this.visual = dados.visual();
        this.aderencia = dados.aderencia();
        this.embalagem = dados.embalagem();
        this.camada = dados.camada();
        this.observacao = dados.observacao();
        this.avaliadaPor = avaliadaPor;
    }

    public Long getId() {
        return id;
    }

    public OrdemServico getOrdemServico() {
        return ordemServico;
    }

    public boolean isVerificado() {
        return verificado;
    }

    public ItemAvaliacao getVisual() {
        return visual;
    }

    public ItemAvaliacao getAderencia() {
        return aderencia;
    }

    public ItemAvaliacao getEmbalagem() {
        return embalagem;
    }

    public ItemAvaliacao getCamada() {
        return camada;
    }

    public String getObservacao() {
        return observacao;
    }

    public Operador getAvaliadaPor() {
        return avaliadaPor;
    }

    public Instant getAvaliadaEm() {
        return avaliadaEm;
    }
}
