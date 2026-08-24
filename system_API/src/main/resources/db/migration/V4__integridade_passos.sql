-- =====================================================================
-- V4 — Integridade dos passos.
-- Uma carga está fisicamente num lugar só: pode ter no máximo UM passo em
-- aberto. Antes de gravar a regra num índice, o legado precisa caber nela.
-- =====================================================================

-- trg_logs_imutavel é BEFORE UPDATE OR DELETE e existe justamente para
-- impedir, em runtime, o UPDATE que faremos aqui. Desligar é deliberado e
-- vale só pela duração do backfill.
ALTER TABLE logs DISABLE TRIGGER trg_logs_imutavel;

-- (a) Passos que ficaram abertos numa OS já encerrada: até esta versão,
-- finalizar()/cancelar() liberavam a carga mas não fechavam o passo. Fecham
-- na data de encerramento da própria OS. Para OS cancelada (que não tem
-- finalizada_em) o fallback é o próprio início — duração zero é honesto,
-- inventar uma data de término não é.
UPDATE logs l
SET finalizado_em = COALESCE(o.finalizada_em, l.iniciado_em)
FROM ordens_servico o
WHERE o.id = l.ordem_servico_id
  AND l.finalizado_em IS NULL
  AND (o.finalizada_em IS NOT NULL OR o.cancelada);

-- (b) Duplicados: mantém aberto só o mais recente de cada carga. O id é
-- UUIDv7, ordenável por tempo, então n.id > l.id significa "começou depois".
-- Os anteriores fecham com duração zero — não há informação no banco para
-- inferir quando terminaram de verdade.
UPDATE logs l
SET finalizado_em = l.iniciado_em
WHERE l.finalizado_em IS NULL
  AND EXISTS (SELECT 1
              FROM logs n
              WHERE n.carga_id = l.carga_id
                AND n.finalizado_em IS NULL
                AND n.id > l.id);

ALTER TABLE logs ENABLE TRIGGER trg_logs_imutavel;

-- A garantia real da regra. Índice PARCIAL: passos fechados não contam, então
-- a carga acumula quantos passos encerrados quiser — só não pode ter dois
-- abertos. O código antecipa o erro com 409, mas quem garante é isto.
CREATE UNIQUE INDEX ux_logs_carga_aberto
    ON logs (carga_id) WHERE finalizado_em IS NULL;
