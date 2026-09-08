package com.ramajo.logs.system.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

import lombok.Setter;

/**
 * A temperatura do forno de desidrogenização — a MESMA para todas.
 *
 * Mora numa tabela própria de linha única em vez de virar coluna do catálogo
 * justamente porque é um valor só: com N linhas em `desidrogenizacoes`, duas
 * poderiam divergir sem que nada no banco reclamasse.
 *
 * A chave é a constante 1, com CHECK (id = 1) na migration — mesmo espírito da
 * PK-enum de ProcessoInicial: quem garante que não existe uma segunda
 * configuração é o banco, não o service.
 */
@Entity
@Table(name = "config_desidrogenizacao")
public class ConfigDesidrogenizacao {

    /** A única linha. Semeada pela V10; o service só faz UPDATE. */
    public static final Short ID = 1;

    @Id
    private Short id;

    @Setter
    @Column(nullable = false)
    private BigDecimal temperatura;

    protected ConfigDesidrogenizacao() {
    }

    public Short getId() {
        return id;
    }

    public BigDecimal getTemperatura() {
        return temperatura;
    }
}
