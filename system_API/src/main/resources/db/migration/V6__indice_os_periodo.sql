-- =====================================================================
-- V6 — Índice para o relatório por período.
-- O relatório varre ordens_servico por iniciada_em (janela [início, fim)).
-- Os índices da V1 cobrem id_externo, cliente_id e em_processo; nenhum deles
-- serve a um range scan por data, e sem este a exportação vira seq scan que
-- cresce com o histórico inteiro, não com o tamanho do período pedido.
-- =====================================================================

CREATE INDEX ix_os_iniciada_em ON ordens_servico (iniciada_em);
