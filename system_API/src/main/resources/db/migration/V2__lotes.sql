-- =====================================================================
-- V2 — Lotes: subdivisões de produção de uma Ordem de Serviço.
-- A OS é quebrada em N partes ("esta OS teve 3 lotes"); cada parte tem
-- seu próprio momento de fechamento. É só um indicativo: o lote NÃO se
-- liga a cargas nem a logs.
-- =====================================================================

CREATE TABLE lotes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ordem_servico_id  BIGINT      NOT NULL REFERENCES ordens_servico (id),
    numero            SMALLINT    NOT NULL,
    iniciado_em       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finalizado_em     TIMESTAMPTZ,
    finalizado_por_id BIGINT      REFERENCES operadores (id),
    CONSTRAINT ck_lotes_numero CHECK (numero >= 1),
    CONSTRAINT ck_lotes_janela
        CHECK (finalizado_em IS NULL OR finalizado_em >= iniciado_em)
);

-- Numeração própria de cada OS: lote 1, 2, 3... sem repetir.
CREATE UNIQUE INDEX ux_lotes_os_numero ON lotes (ordem_servico_id, numero);

-- No máximo UM lote aberto por OS. Índice único parcial (mesmo truque de
-- ux_os_id_externo): a regra fica garantida pelo banco, não só pelo service.
CREATE UNIQUE INDEX ux_lotes_os_aberto
    ON lotes (ordem_servico_id) WHERE finalizado_em IS NULL;

-- Backfill: toda OS já existente teve exatamente 1 lote. As finalizadas
-- herdam data/autor da própria OS; as que seguem em produção (e as
-- canceladas) ficam com o lote 1 em aberto. Assim TODA OS tem um lote —
-- o código nunca precisa tratar "OS sem lote".
INSERT INTO lotes (ordem_servico_id, numero, iniciado_em, finalizado_em, finalizado_por_id)
SELECT id, 1, iniciada_em, finalizada_em, finalizada_por_id FROM ordens_servico;
