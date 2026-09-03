-- =====================================================================
-- V7 — Processo inicial POR POSIÇÃO.
-- Até aqui o passo que toda carga abre ao entrar numa OS era um valor fixo
-- global (app.processo-inicial-id, semeado pela V3). Isso não corresponde ao
-- chão de fábrica: cada setor tem seu próprio primeiro tanque — o
-- desengraxante do zinco não é o mesmo da oxidação. A relação passa a morar
-- aqui, uma linha por posição, configurável pela API.
-- =====================================================================

CREATE TABLE posicao_processo_inicial (
    posicao     VARCHAR(20) PRIMARY KEY,
    processo_id BIGINT      NOT NULL REFERENCES processos (id),
    CONSTRAINT ck_ppi_posicao
        CHECK (posicao IN ('OXIDACAO', 'AUTOMATICA', 'PENDURADO'))
);

-- Semeia as três posições com o processo que já era usado globalmente (o
-- 'Desengraxante(Inicio)' de id 0, da V3). O comportamento imediatamente após
-- esta migration é IDÊNTICO ao de antes dela; a partir daqui cada setor pode
-- ser trocado por PUT /api/processos-iniciais/{posicao}.
--
-- ON CONFLICT DO NOTHING pelo mesmo motivo das seeds da V5: esta migration é o
-- piso do cadastro, não a fonte da verdade.
INSERT INTO posicao_processo_inicial (posicao, processo_id) VALUES
    ('OXIDACAO',   0),
    ('AUTOMATICA', 0),
    ('PENDURADO',  0)
ON CONFLICT (posicao) DO NOTHING;
