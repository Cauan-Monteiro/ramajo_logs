package com.ramajo.logs.system.entities;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Ordem de Serviço: o agregado que amarra o trabalho.
 *
 * Criada neste app (id gerado pelo Postgres); `idExterno` guarda o número
 * correspondente no sistema principal, para conciliação.
 *
 * Roda por UMA posição (setor) no caso normal, e excepcionalmente por mais de
 * uma — ver `posicoes`. Em qualquer dos casos é UMA ordem: um conjunto de
 * lotes, uma avaliação, uma expedição, uma entrega.
 */
@Entity
@Table(name = "ordens_servico")
public class OrdemServico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Número no sistema principal. NÃO identifica uma OS sozinho: desde a V20 é
    // único POR SETOR — a mesma ordem partida entre dois setores vira duas OS
    // com este mesmo número (e o mesmo cliente). Quem garante a regra é
    // OrdemServicoService.criar/corrigir; o banco só indexa, não trava.
    @Setter
    @Column(name = "id_externo")
    private Long idExterno;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id")
    private Cliente cliente;

    /**
     * Os setores em que esta OS está AUTORIZADA a rodar — não onde o trabalho
     * acontece. Onde ele acontece é a carga que diz (`Carga.posicao`), e é
     * dela que o service lê para escolher o processo inicial, autorizar o
     * processo de um passo e validar um acoplamento.
     *
     * Quase sempre tem um elemento só. O caso de dois é a exceção que motivou
     * a V19: as peças de uma mesma OS partidas entre dois setores, produzindo
     * em paralelo. Mesmo assim a OS expede e entrega UMA vez — a produção é
     * que é independente, não o fecho.
     *
     * Mesmo molde de Processo.posicoes. @BatchSize pela mesma razão de `lotes`:
     * listarParaResumo devolve o histórico inteiro, e sem ele seria um SELECT
     * por ordem.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "ordem_posicoes",
            joinColumns = @JoinColumn(name = "ordem_servico_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "posicao", nullable = false, length = 20)
    @BatchSize(size = 100)
    private Set<Posicao> posicoes = new HashSet<>();

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

    // A entrega ao cliente: o passo DEPOIS da expedição. Carimbo posto pelo
    // service (Instant.now()) e não pelo default do Postgres — é um UPDATE de
    // transição, como finalizadaEm, não o INSERT de uma linha nova.
    //
    // Estado da expedição corrente: reabrir() limpa os dois, pelo mesmo motivo
    // que descarta a avaliação.
    @Setter
    @Column(name = "entregue_em")
    private Instant entregueEm;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entregue_por_id")
    private Operador entreguePor;

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

    // Desidrogenizações aplicadas a esta OS, na ordem em que rodaram. Pode ser
    // vazia: é uma etapa opcional. @BatchSize pelo mesmo motivo dos lotes.
    @OneToMany(mappedBy = "ordemServico", fetch = FetchType.LAZY)
    @OrderBy("iniciadaEm ASC, id ASC")
    @BatchSize(size = 100)
    private List<OrdemDesidrogenizacao> desidrogenizacoes = new ArrayList<>();

    /**
     * As cargas que a EXPEDIÇÃO TOTAL soltou, guardadas para a reabertura as
     * poder sugerir de volta.
     *
     * É estado efémero, não histórico: `finalizar` escreve a lista, `reabrir`
     * consome-a e limpa-a. Existe porque `finalizar` zera `Carga.ordemAtual` e
     * não deixa registo do vínculo desfeito — e `logs` não serve de substituto,
     * porque diz que a carga passou pela OS, não que estava lá no instante da
     * expedição (uma carga com o passo já finalizado à mão não teria como ser
     * reconhecida).
     *
     * Só ids, sem @ManyToMany, pela mesma razão de Carga.ordensAcopladas: a
     * pergunta é sempre "quais cargas esta OS soltou", e é uma leitura só.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "os_cargas_expedidas",
            joinColumns = @JoinColumn(name = "ordem_servico_id"))
    @Column(name = "carga_id", nullable = false)
    private Set<Long> cargasExpedidas = new HashSet<>();

    protected OrdemServico() {
    }

    /**
     * A OS nasce sempre num setor só. O segundo, quando é preciso, entra
     * depois com a ordem já em produção (OrdemServicoService.adicionarPosicao)
     * — é assim que a exceção aparece no chão de fábrica: não se sabe dela na
     * abertura.
     */
    public OrdemServico(Long idExterno, Cliente cliente, Posicao posicao) {
        this.idExterno = idExterno;
        this.cliente = cliente;
        this.posicoes.add(posicao);
    }

    @Transient
    public boolean isFinalizada() {
        return finalizadaEm != null;
    }

    @Transient
    public boolean isEntregue() {
        return entregueEm != null;
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

    public Set<Posicao> getPosicoes() {
        return posicoes;
    }

    /** Esta OS está autorizada a rodar neste setor? */
    @Transient
    public boolean rodaEm(Posicao posicao) {
        return posicoes.contains(posicao);
    }

    /**
     * As posições na ordem canônica do enum — a mesma que o front usa para
     * rotular e que o histórico de alterações grava. Uma coleção sem ordem
     * daria texto diferente a cada leitura, e o histórico ficaria a registar
     * mudanças que não houve.
     */
    @Transient
    public List<Posicao> getPosicoesOrdenadas() {
        return posicoes.stream().sorted(Comparator.comparing(Enum::ordinal)).toList();
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

    public Instant getEntregueEm() {
        return entregueEm;
    }

    public Operador getEntreguePor() {
        return entreguePor;
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

    public List<OrdemDesidrogenizacao> getDesidrogenizacoes() {
        return desidrogenizacoes;
    }

    public Set<Long> getCargasExpedidas() {
        return cargasExpedidas;
    }
}
