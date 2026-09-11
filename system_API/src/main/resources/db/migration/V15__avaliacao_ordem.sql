-- =====================================================================
-- V15 — Avaliação da OS na inspeção final.
--
-- Antes de expedir, o inspetor confere quatro pontos (visual, aderência,
-- embalagem, camada), deixa uma observação geral e marca se deu a
-- verificação por concluída. Opcional: dá para expedir sem avaliar, e o
-- ADMIN avalia depois pelo Ajustes.
--
-- UMA por OS (UNIQUE): avaliar de novo SUBSTITUI a linha — é estado da
-- expedição corrente, não histórico. Por isso a reabertura a apaga: a
-- avaliação descrevia uma produção que foi desfeita.
--
-- Cada ponto tem três estados na API (null | true | "texto do problema")
-- e duas colunas aqui: `_avaliado` diz se foi conferido; `_problema`, o
-- que se achou. Problema sem conferência não existe (CHECK).
-- =====================================================================

CREATE TABLE ordem_avaliacoes (
    id                  BIGSERIAL    PRIMARY KEY,
    ordem_servico_id    BIGINT       NOT NULL UNIQUE REFERENCES ordens_servico (id),
    is_verificado       BOOLEAN      NOT NULL,

    visual_avaliado     BOOLEAN      NOT NULL DEFAULT FALSE,
    visual_problema     VARCHAR(500),
    aderencia_avaliado  BOOLEAN      NOT NULL DEFAULT FALSE,
    aderencia_problema  VARCHAR(500),
    embalagem_avaliado  BOOLEAN      NOT NULL DEFAULT FALSE,
    embalagem_problema  VARCHAR(500),
    camada_avaliado     BOOLEAN      NOT NULL DEFAULT FALSE,
    camada_problema     VARCHAR(500),

    observacao          VARCHAR(500),
    avaliada_por_id     BIGINT       NOT NULL REFERENCES operadores (id),
    avaliada_em         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_avaliacao_visual    CHECK (visual_problema    IS NULL OR visual_avaliado),
    CONSTRAINT ck_avaliacao_aderencia CHECK (aderencia_problema IS NULL OR aderencia_avaliado),
    CONSTRAINT ck_avaliacao_embalagem CHECK (embalagem_problema IS NULL OR embalagem_avaliado),
    CONSTRAINT ck_avaliacao_camada    CHECK (camada_problema    IS NULL OR camada_avaliado)
);

-- Substituir é UPDATE na mesma linha; o carimbo acompanha, e pelo relógio do
-- POSTGRES, como no INSERT — o Java não escreve a data.
CREATE OR REPLACE FUNCTION ordem_avaliacoes_carimba()
RETURNS TRIGGER AS $$
BEGIN
    NEW.avaliada_em := clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ordem_avaliacoes_carimbo
    BEFORE UPDATE ON ordem_avaliacoes
    FOR EACH ROW EXECUTE FUNCTION ordem_avaliacoes_carimba();
