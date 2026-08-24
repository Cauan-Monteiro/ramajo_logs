package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.enums.Posicao;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Ordem de Serviço: o agregado que amarra o trabalho.
 *
 * Criada neste app (id gerado pelo Postgres); `idExterno` guarda o número
 * correspondente no sistema principal, para conciliação. Roda por UMA posição
 * (setor).
 */
@Entity
@Table(name = "ordens_servico")
public class OrdemServico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Número no sistema principal. Único quando presente (índice na migration).
    @Setter
    @Column(name = "id_externo")
    private Long idExterno;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Posicao posicao;

    // Carimbo gerado pelo RELÓGIO DO POSTGRES (clock_timestamp), não pelo
    // processo Java — imune a relógio dessincronizado do cliente. @Generated faz
    // o Hibernate reler o valor após o INSERT. Armazenado em timestamptz (UTC);
    // a conversão para America/Sao_Paulo acontece só na apresentação.
    @Generated(event = EventType.INSERT)
    @ColumnDefault("clock_timestamp()")
    @Column(name = "iniciada_em", nullable = false, updatable = false)
    private Instant iniciadaEm;

    @Setter
    @Column(name = "finalizada_em")
    private Instant finalizadaEm;

    // Soft-cancel: OS inválida sem apagar nada. (era isCanceled)
    @Setter
    @Column(nullable = false)
    private boolean cancelada = false;

    // Espelha "not is_finished", mas indexado, para responder "quais OSs estão
    // em produção?" sem varrer finalizada_em.
    @Column(name = "em_processo", nullable = false)
    private boolean emProcesso = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "iniciada_por_id")
    private Operador iniciadaPor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finalizada_por_id")
    private Operador finalizadaPor;

    // Histórico de passos, em ordem cronológica. Sem cascade de remoção: logs
    // nunca são apagados.
    @OneToMany(mappedBy = "ordemServico", fetch = FetchType.LAZY)
    @OrderBy("iniciadoEm ASC, id ASC")
    private List<Log> logs = new ArrayList<>();

    // Cargas ATUALMENTE vinculadas (as liberadas têm ordemAtual = null e somem
    // daqui). Logo, `cargas` == o que ainda está fisicamente na OS.
    @OneToMany(mappedBy = "ordemAtual", fetch = FetchType.LAZY)
    private List<Carga> cargas = new ArrayList<>();

    // Partes em que a produção desta OS foi quebrada, na ordem. @BatchSize evita
    // o N+1 na listagem de OSs: o Hibernate traz os lotes de até 100 ordens numa
    // query só, em vez de uma por ordem.
    @OneToMany(mappedBy = "ordemServico", fetch = FetchType.LAZY)
    @OrderBy("numero ASC")
    @BatchSize(size = 100)
    private List<Lote> lotes = new ArrayList<>();

    protected OrdemServico() {
    }

    public OrdemServico(Long idExterno, Cliente cliente, Posicao posicao) {
        this.idExterno = idExterno;
        this.cliente = cliente;
        this.posicao = posicao;
    }

    @Transient
    public boolean isFinalizada() {
        return finalizadaEm != null;
    }

    // Quantos lotes esta OS teve. Derivado da coleção — sem coluna redundante
    // para sair de sincronia.
    @Transient
    public int getTotalLotes() {
        return lotes.size();
    }

    @Transient
    public long getLotesFinalizados() {
        return lotes.stream().filter(Lote::isFinalizado).count();
    }

    // O lote em produção agora. Null só depois que a OS foi finalizada.
    @Transient
    public Lote getLoteAberto() {
        return lotes.stream().filter(l -> !l.isFinalizado()).findFirst().orElse(null);
    }

    public Long getId() {
        return id;
    }

    public Long getIdExterno() {
        return idExterno;
    }

    public Cliente getCliente() {
        return cliente;
    }

    public Posicao getPosicao() {
        return posicao;
    }

    public Instant getIniciadaEm() {
        return iniciadaEm;
    }

    public Instant getFinalizadaEm() {
        return finalizadaEm;
    }

    public boolean isCancelada() {
        return cancelada;
    }

    public boolean isEmProcesso() {
        return emProcesso;
    }

    public void setEmProcesso(boolean emProcesso) {
        this.emProcesso = emProcesso;
    }

    public Operador getIniciadaPor() {
        return iniciadaPor;
    }

    public void setIniciadaPor(Operador iniciadaPor) {
        this.iniciadaPor = iniciadaPor;
    }

    public Operador getFinalizadaPor() {
        return finalizadaPor;
    }

    public void setFinalizadaPor(Operador finalizadaPor) {
        this.finalizadaPor = finalizadaPor;
    }

    public List<Log> getLogs() {
        return logs;
    }

    public List<Carga> getCargas() {
        return cargas;
    }

    public List<Lote> getLotes() {
        return lotes;
    }
}
