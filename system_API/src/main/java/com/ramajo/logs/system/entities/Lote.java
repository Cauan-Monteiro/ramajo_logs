package com.ramajo.logs.system.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;

import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Lote: uma parte da produção de UMA Ordem de Serviço.
 *
 * Quando a OS é quebrada para produzir ("esta OS teve 3 lotes"), cada parte
 * vira um lote com seu próprio carimbo de fechamento. É apenas um indicativo
 * de progresso — o lote não se liga a cargas nem a logs.
 *
 * Ciclo de vida: a OS nasce com o lote 1 aberto; fechar um lote abre o
 * seguinte; finalizar a OS fecha o lote corrente sem abrir outro. Por isso
 * existe sempre exatamente um lote aberto enquanto a OS está em processo
 * (garantido no banco pelo índice parcial `ux_lotes_os_aberto`).
 */
@Entity
@Table(name = "lotes")
public class Lote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordem_servico_id")
    private OrdemServico ordemServico;

    // Sequencial DENTRO da OS (1, 2, 3...), não global.
    @Column(nullable = false)
    private Short numero;

    // Mesmo raciocínio de OrdemServico.iniciadaEm: o carimbo vem do relógio do
    // Postgres (clock_timestamp), não do processo Java.
    @Generated(event = EventType.INSERT)
    @ColumnDefault("clock_timestamp()")
    @Column(name = "iniciado_em", nullable = false, updatable = false)
    private Instant iniciadoEm;

    @Setter
    @Column(name = "finalizado_em")
    private Instant finalizadoEm;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finalizado_por_id")
    private Operador finalizadoPor;

    protected Lote() {
    }

    public Lote(OrdemServico ordemServico, Short numero) {
        this.ordemServico = ordemServico;
        this.numero = numero;
    }

    @Transient
    public boolean isFinalizado() {
        return finalizadoEm != null;
    }

    public Long getId() {
        return id;
    }

    public OrdemServico getOrdemServico() {
        return ordemServico;
    }

    public Short getNumero() {
        return numero;
    }

    public Instant getIniciadoEm() {
        return iniciadoEm;
    }

    public Instant getFinalizadoEm() {
        return finalizadoEm;
    }

    public Operador getFinalizadoPor() {
        return finalizadoPor;
    }
}
