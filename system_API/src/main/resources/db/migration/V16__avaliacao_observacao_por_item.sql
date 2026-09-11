-- =====================================================================
-- V16 — O texto de cada ponto da avaliação é OBSERVAÇÃO, não problema.
--
-- Na V15 o texto significava "conferido e com problema". A tela passou a
-- tratar cada ponto como marcação simples (marcado = avaliado) com uma
-- observação própria opcional — que pode ser só um registo, não um defeito.
-- O nome da coluna acompanha, para quem ler o banco não tirar a conclusão
-- errada. Os CHECKs da V15 seguem a coluna renomeada sozinhos.
-- =====================================================================

ALTER TABLE ordem_avaliacoes RENAME COLUMN visual_problema    TO visual_observacao;
ALTER TABLE ordem_avaliacoes RENAME COLUMN aderencia_problema TO aderencia_observacao;
ALTER TABLE ordem_avaliacoes RENAME COLUMN embalagem_problema TO embalagem_observacao;
ALTER TABLE ordem_avaliacoes RENAME COLUMN camada_problema    TO camada_observacao;
