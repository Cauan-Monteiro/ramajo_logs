-- =====================================================================
-- V9 — Cliente genérico (id 0).
--
-- Este INSERT nasceu dentro do V8, depois de o V8 já ter sido aplicado. O
-- Flyway recusa validar um migration alterado após a aplicação, então ele vem
-- para cá: aqui roda nos bancos que já existem e continua rodando nos novos.
-- `clientes.id` vem de fora (sem IDENTITY, ver V1), daí o id explícito.
-- =====================================================================

INSERT INTO clientes (id, nome) VALUES
    (   0, 'CLIENTE GENÉRICO')
ON CONFLICT (id) DO NOTHING;
