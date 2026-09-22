-- As duas FKs de `logs` que ficaram sem índice.
--
-- A V21 indexou finalizado_por_id pela mesma razão que vale aqui: a recusa de
-- exclusão definitiva em OperadorService.excluir conta os passos de um operador
-- (LogRepository.countByResponsavelId), e sem índice isso varre `logs` inteiro.
-- O mesmo para processo_id, que ProcessoService consulta ao arquivar.
--
-- Nota honesta sobre o alcance: estes índices NÃO aceleram a leitura de tela.
-- Quem lê um passo navega do log para a PK de processos/operadores, e PK já é
-- índice. O que estava lento na Visão Geral era o N+1 de lazy loading, resolvido
-- nos fetch joins de LogRepository.buscarHistorico — não a falta destes índices.
-- Eles entram porque uma FK sem índice é dívida de qualquer forma, e porque o
-- DELETE de operador/processo precisa deles.
CREATE INDEX ix_logs_processo ON logs (processo_id);
CREATE INDEX ix_logs_responsavel ON logs (responsavel_id);
