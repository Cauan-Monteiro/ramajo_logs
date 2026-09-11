package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.enums.CampoAlterado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Uma linha do histórico de correções de uma OS: um campo, o valor de antes e
 * o de depois, quem corrigiu e por quê.
 *
 * Os valores são TEXTO pronto para ler, não referências: o histórico descreve
 * o que se via no momento da correção, e renomear o cliente depois não pode
 * reescrevê-lo — o mesmo cuidado das cópias em OrdemDesidrogenizacao.
 *
 * Sem setters: a tabela é append-only por trigger (V14).
 */
@Entity
@Table(name = "ordem_alteracoes")
public class OrdemAlteracao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordem_servico_id", updatable = false)
    private OrdemServico ordemServico;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private CampoAlterado campo;

    // columnDefinition: o ddl-auto=validate compara com a coluna TEXT, não com
    // o varchar(255) que o Hibernate presumiria. Mesmo caso de Desidrogenizacao.
    @Column(name = "valor_anterior", columnDefinition = "text", updatable = false)
    private String valorAnterior;

    @Column(name = "valor_novo", columnDefinition = "text", updatable = false)
    private String valorNovo;

    @Column(nullable = false, length = 500, updatable = false)
    private String motivo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alterada_por_id", updatable = false)
    private Operador alteradaPor;

    // Relógio do Postgres, como OrdemServico.iniciadaEm.
    @Generated(event = EventType.INSERT)
    @ColumnDefault("clock_timestamp()")
    @Column(name = "alterada_em", nullable = false, updatable = false)
    private Instant alteradaEm;

    protected OrdemAlteracao() {
    }

    public OrdemAlteracao(OrdemServico ordemServico, CampoAlterado campo, String valorAnterior,
                          String valorNovo, String motivo, Operador alteradaPor) {
        this.ordemServico = ordemServico;
        this.campo = campo;
        this.valorAnterior = valorAnterior;
        this.valorNovo = valorNovo;
        this.motivo = motivo;
        this.alteradaPor = alteradaPor;
    }

    public Long getId() {
        return id;
    }

    public OrdemServico getOrdemServico() {
        return ordemServico;
    }

    public CampoAlterado getCampo() {
        return campo;
    }

    public String getValorAnterior() {
        return valorAnterior;
    }

    public String getValorNovo() {
        return valorNovo;
    }

    public String getMotivo() {
        return motivo;
    }

    public Operador getAlteradaPor() {
        return alteradaPor;
    }

    public Instant getAlteradaEm() {
        return alteradaEm;
    }
}
