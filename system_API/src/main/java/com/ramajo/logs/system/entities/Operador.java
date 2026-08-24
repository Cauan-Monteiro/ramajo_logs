package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.enums.Permissao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Pessoa que opera o sistema.
 *
 * `tagId` (crachá/RFID) é opcional, mas único quando presente — garantido por
 * índice único parcial na migration (Postgres trata NULLs como distintos).
 */
@Entity
@Table(name = "operadores")
public class Operador {

    // IDENTITY (bigint) para tabela de referência muito joinada: mais barato de
    // indexar que UUID. Ids sequenciais são internos, não expostos em API.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nome;

    @Column(name = "tag_id", length = 64)
    private String tagId;

    // STRING e nunca ORDINAL: reordenar o enum não pode corromper dados gravados.
    // A validação de valor no banco fica num CHECK criado pela migration.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Permissao permissao;

    // Soft-delete: vira false ao desativar, sem apagar histórico. (era isActivate)
    @Column(nullable = false)
    private boolean ativo = true;

    protected Operador() {
    }

    public Operador(String nome, Permissao permissao, String tagId) {
        this.nome = nome;
        this.permissao = permissao;
        this.tagId = tagId;
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

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public Permissao getPermissao() {
        return permissao;
    }

    public void setPermissao(Permissao permissao) {
        this.permissao = permissao;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }
}
