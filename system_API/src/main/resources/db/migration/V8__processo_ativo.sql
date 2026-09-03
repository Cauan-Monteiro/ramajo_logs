-- =====================================================================
-- V8 — Soft-delete de processo.
-- Excluir um processo fisicamente é impossível neste schema, e de propósito:
-- logs.processo_id é NOT NULL sem cascade, e logs é append-only por trigger
-- (trg_logs_imutavel, V1). Apagar a linha levaria junto — ou barraria — o
-- histórico que ela documenta.
--
-- Então "excluir" passa a ser arquivar: o processo sai das listas de escolha
-- (entrada de setor, abertura de passo) e continua existindo para todo registro
-- que já o usou. Mesmo mecanismo de cargas.ativo ("Sucatear").
-- =====================================================================

ALTER TABLE processos ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT TRUE;
