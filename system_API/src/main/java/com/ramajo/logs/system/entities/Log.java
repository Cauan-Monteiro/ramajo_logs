package com.ramajo.logs.system.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.generator.EventType;

/**
 * Um passo executado numa OS: uma Carga passou por um Processo, sob
 * responsabilidade de um Operador, de `iniciadoEm` a `finalizadoEm`.
 *
 * Imutabilidade PARCIAL (garantida por trigger na migration): id, os,
 * responsável, carga, processo e iniciadoEm nunca mudam; DELETE é proibido.
 * Só se permite fechar o intervalo (finalizadoEm) e marcar `cancelado` uma vez.
 * Por isso NÃO usamos @Immutable do Hibernate (que bloquearia todo UPDATE).
 */
@Entity
@Table(name = "logs")
public class Log {

    // UUIDv7: ordenável por tempo, coerente com um registro cronológico.
    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordem_servico_id", updatable = false)
    private OrdemServico ordemServico;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "responsavel_id", updatable = false)
    private Operador responsavel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carga_id", updatable = false)
    private Carga carga;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", updatable = false)
    private Processo processo;

    // Início do passo: carimbo do relógio do Postgres, relido após o INSERT.
    @Generated(event = EventType.INSERT)
    @ColumnDefault("clock_timestamp()")
    @Column(name = "iniciado_em", nullable = false, updatable = false)
    private Instant iniciadoEm;

    // Fim do passo: preenchido depois. A trigger impede reabrir um passo já
    // fechado e checa finalizadoEm >= iniciadoEm.
    @Column(name = "finalizado_em")
    private Instant finalizadoEm;

    @Column(nullable = false)
    private boolean cancelado = false;

    protected Log() {
    }

    public Log(OrdemServico ordemServico, Operador responsavel, Carga carga, Processo processo) {
        this.ordemServico = ordemServico;
        this.responsavel = responsavel;
        this.carga = carga;
        this.processo = processo;
    }

    public UUID getId() {
        return id;
    }

    public OrdemServico getOrdemServico() {
        return ordemServico;
    }

    public Operador getResponsavel() {
        return responsavel;
    }

    public Carga getCarga() {
        return carga;
    }

    public Processo getProcesso() {
        return processo;
    }

    public Instant getIniciadoEm() {
        return iniciadoEm;
    }

    public Instant getFinalizadoEm() {
        return finalizadoEm;
    }

    public void setFinalizadoEm(Instant finalizadoEm) {
        this.finalizadoEm = finalizadoEm;
    }

    public boolean isCancelado() {
        return cancelado;
    }

    public void setCancelado(boolean cancelado) {
        this.cancelado = cancelado;
    }
}
