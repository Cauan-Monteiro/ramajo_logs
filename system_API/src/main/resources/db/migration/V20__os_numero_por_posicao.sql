-- =====================================================================
-- V20 — O Nº da OS (id_externo) passa a ser único POR POSIÇÃO, não mais
-- absoluto.
--
-- A V19 admitiu que a mesma ordem pode ser partida entre dois setores.
-- Faltava a consequência na CRIAÇÃO: o chão de fábrica traz o Nº 42 com
-- peças em Oxidação e peças em Automática, e isso não é uma OS só — são
-- duas ordens paralelas que só partilham o número do ERP. A regra nova,
-- em OrdemServicoService.criar():
--
--   Nº existe na MESMA posição  -> vincula as cargas àquela OS;
--   Nº existe em OUTRA posição  -> nasce uma OS nova naquela posição;
--   Nº não existe               -> nasce uma OS nova, como sempre.
--
-- ux_os_id_externo (V1) impedia o caso do meio: era UNIQUE na coluna
-- inteira. Vira índice comum — continua a servir a busca por Nº, que é
-- para o que o `criar` o usa, mas deixa de recusar o par legítimo.
--
-- A unicidade REAL ("um Nº por posição") não desce para o banco: desde a
-- V19 as posições vivem em ordem_posicoes, uma linha por setor, e um
-- índice em ordens_servico não alcança uma tabela filha. Quem garante é
-- o service, dentro da mesma transação que cria a OS. Ao contrário de
-- ux_lotes_os_aberto (V2), aqui a rede do banco não existe — é decisão
-- consciente, não esquecimento.
-- =====================================================================

DROP INDEX ux_os_id_externo;

CREATE INDEX ix_os_id_externo ON ordens_servico (id_externo) WHERE id_externo IS NOT NULL;
