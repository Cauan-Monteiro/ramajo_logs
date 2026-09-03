package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.enums.Posicao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * O processo em que toda carga entra ao ser vinculada a uma OS, por setor.
 *
 * A chave é a própria Posicao (não há id sintético): o conjunto de setores é
 * fixo e pequeno, então existe no máximo UMA linha por posição — a PK garante
 * isso no banco, sem unique extra e sem risco de duas configurações
 * concorrentes para o mesmo setor.
 */
@Entity
@Table(name = "posicao_processo_inicial")
public class ProcessoInicial {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "posicao", nullable = false, length = 20)
    private Posicao posicao;

    // LAZY como as demais associações do domínio; quem precisa da descrição
    // (o DTO) toca o proxy dentro da sessão.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "processo_id", nullable = false)
    private Processo processo;

    protected ProcessoInicial() {
    }

    public ProcessoInicial(Posicao posicao, Processo processo) {
        this.posicao = posicao;
        this.processo = processo;
    }

    public Posicao getPosicao() {
        return posicao;
    }

    public Processo getProcesso() {
        return processo;
    }

    public void setProcesso(Processo processo) {
        this.processo = processo;
    }
}
