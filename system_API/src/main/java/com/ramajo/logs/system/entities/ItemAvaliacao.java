package com.ramajo.logs.system.entities;

import com.ramajo.logs.system.exceptions.AvaliacaoInvalidaException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Um ponto da avaliação (visual, aderência, embalagem, camada).
 *
 * Na API são três estados num campo só: `null` = não avaliado, `true` =
 * avaliado sem observação, texto = avaliado com observação. `false` não
 * existe: ponto não conferido é `null`.
 *
 * No banco são duas colunas (ver V15/V16) — `avaliado` e `observacao` —, para
 * o CHECK impedir observação sem conferência. As colunas reais vêm por
 * @AttributeOverride em OrdemAvaliacao.
 */
@Embeddable
public class ItemAvaliacao {

    public static final int MAX_OBSERVACAO = 500;

    @Column(nullable = false)
    private boolean avaliado;

    @Column(length = MAX_OBSERVACAO)
    private String observacao;

    protected ItemAvaliacao() {
    }

    private ItemAvaliacao(boolean avaliado, String observacao) {
        this.avaliado = avaliado;
        this.observacao = observacao;
    }

    /** O valor como chegou no JSON: Jackson entrega null, Boolean ou String. */
    public static ItemAvaliacao de(String item, Object valor) {
        if (valor == null) {
            return new ItemAvaliacao(false, null);
        }
        if (valor instanceof Boolean b) {
            if (!b) {
                throw new AvaliacaoInvalidaException(item,
                        "false não é aceito; use null (não avaliado) ou true (avaliado).");
            }
            return new ItemAvaliacao(true, null);
        }
        if (valor instanceof String s) {
            String t = s.trim();
            if (t.length() > MAX_OBSERVACAO) {
                throw new AvaliacaoInvalidaException(item,
                        "a observação passa de " + MAX_OBSERVACAO + " caracteres.");
            }
            // Marcado com a observação em branco: avaliado, sem nada a dizer.
            return new ItemAvaliacao(true, t.isEmpty() ? null : t);
        }
        throw new AvaliacaoInvalidaException(item, "use null, true ou um texto.");
    }

    /** O inverso de {@link #de}: o que a API devolve. */
    public Object valor() {
        if (!avaliado) {
            return null;
        }
        return observacao != null ? observacao : Boolean.TRUE;
    }

    public boolean isAvaliado() {
        return avaliado;
    }

    public String getObservacao() {
        return observacao;
    }
}
