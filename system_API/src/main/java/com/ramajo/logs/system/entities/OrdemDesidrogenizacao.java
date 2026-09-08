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
import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Uma desidrogenização APLICADA a uma Ordem de Serviço.
 *
 * Mesma forma de Lote: filha da OS, com o carimbo de início vindo do relógio do
 * Postgres (clock_timestamp), não do processo Java.
 *
 * `duracaoMin` e `temperatura` são cópias do cadastro e da configuração no
 * momento da aplicação, não referências vivas. Editar a receita amanhã não pode
 * reescrever o que já rodou ontem — é o mesmo cuidado que faz `finalizadaEm`
 * ser gravado em vez de recalculado a cada leitura.
 */
@Entity
@Table(name = "ordem_desidrogenizacoes")
public class OrdemDesidrogenizacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ordem_servico_id")
    private OrdemServico ordemServico;

    // Só para rastrear a origem no catálogo (e exibir o nome corrente); o que
    // vale para o histórico são as colunas copiadas abaixo.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "desidrogenizacao_id")
    private Desidrogenizacao desidrogenizacao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aplicada_por_id")
    private Operador aplicadaPor;

    @Column(name = "duracao_min", nullable = false)
    private Integer duracaoMin;

    @Column(nullable = false)
    private BigDecimal temperatura;

    @Generated(event = EventType.INSERT)
    @ColumnDefault("clock_timestamp()")
    @Column(name = "iniciada_em", nullable = false, updatable = false)
    private Instant iniciadaEm;

    // Preenchida pela trigger trg_odesidro_fim (iniciada_em + duracao_min). O
    // Hibernate lê depois do INSERT e nunca escreve — daí insertable/updatable
    // false, sem os quais ele mandaria um NULL e violaria o NOT NULL da coluna.
    @Generated(event = EventType.INSERT)
    @Column(name = "finalizada_em", nullable = false, insertable = false, updatable = false)
    private Instant finalizadaEm;

    protected OrdemDesidrogenizacao() {
    }

    public OrdemDesidrogenizacao(OrdemServico ordemServico, Desidrogenizacao desidrogenizacao,
                                 Operador aplicadaPor, BigDecimal temperatura) {
        this.ordemServico = ordemServico;
        this.desidrogenizacao = desidrogenizacao;
        this.aplicadaPor = aplicadaPor;
        this.duracaoMin = desidrogenizacao.getDuracaoMin();
        this.temperatura = temperatura;
    }

    public Long getId() {
        return id;
    }

    public OrdemServico getOrdemServico() {
        return ordemServico;
    }

    public Desidrogenizacao getDesidrogenizacao() {
        return desidrogenizacao;
    }

    public Operador getAplicadaPor() {
        return aplicadaPor;
    }

    public Integer getDuracaoMin() {
        return duracaoMin;
    }

    public BigDecimal getTemperatura() {
        return temperatura;
    }

    public Instant getIniciadaEm() {
        return iniciadaEm;
    }

    public Instant getFinalizadaEm() {
        return finalizadaEm;
    }
}
