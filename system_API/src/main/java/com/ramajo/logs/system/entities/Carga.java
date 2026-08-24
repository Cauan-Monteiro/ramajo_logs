package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
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
import jakarta.persistence.Transient;

/**
 * Dispositivo físico reutilizável (tambor, trave ou cesto) que carrega peças.
 *
 * Ponto-chave da modelagem: em vez de um booleano `emUso` (que só diz
 * "ocupada", não "por quem"), o vínculo atual é um FK ANULÁVEL para a OS.
 *   - ordemAtual == null  -> livre, pode ser designada
 *   - ordemAtual != null  -> em uso PELA OS apontada
 * A invariante "uma carga pertence a no máximo uma OS por vez" cai de graça:
 * é uma coluna escalar numa linha. Liberar a carga = setar ordemAtual = null.
 */
@Entity
@Table(name = "cargas")
public class Carga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoCarga tipo;

    // Setor onde a carga está fisicamente (uma única posição).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Posicao posicao;

    // Soft-delete: sucateada/retirada some do pool sem apagar o histórico de
    // logs que ela gerou.
    @Column(nullable = false)
    private boolean ativo = true;

    // Vínculo ATUAL com a OS. LAZY para não puxar a OS em toda leitura de carga.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordem_atual_id")
    private OrdemServico ordemAtual;

    @Column(name = "tag_id", length = 64)
    private String tagId;

    protected Carga() {
    }

    public Carga(String nome, TipoCarga tipo, Posicao posicao) {
        this.nome = nome;
        this.tipo = tipo;
        this.posicao = posicao;
    }

    /** Ativa e sem OS: pode ser designada a uma nova ordem de serviço. */
    @Transient
    public boolean isDisponivel() {
        return ativo && ordemAtual == null;
    }

    /** Equivalente ao antigo `emUso`, agora derivado do vínculo. */
    @Transient
    public boolean isEmUso() {
        return ordemAtual != null;
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

    public TipoCarga getTipo() {
        return tipo;
    }

    public void setTipo(TipoCarga tipo) {
        this.tipo = tipo;
    }

    public Posicao getPosicao() {
        return posicao;
    }

    public void setPosicao(Posicao posicao) {
        this.posicao = posicao;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public OrdemServico getOrdemAtual() {
        return ordemAtual;
    }

    public void setOrdemAtual(OrdemServico ordemAtual) {
        this.ordemAtual = ordemAtual;
    }

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }
}
