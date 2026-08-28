package com.ramajo.logs.system.services;

import static com.ramajo.logs.system.services.EscritorPlanilha.ajustar;
import static com.ramajo.logs.system.services.EscritorPlanilha.cabecalhoTabela;
import static com.ramajo.logs.system.services.EscritorPlanilha.data;
import static com.ramajo.logs.system.services.EscritorPlanilha.dia;
import static com.ramajo.logs.system.services.EscritorPlanilha.duracao;
import static com.ramajo.logs.system.services.EscritorPlanilha.hora;
import static com.ramajo.logs.system.services.EscritorPlanilha.imprimivel;
import static com.ramajo.logs.system.services.EscritorPlanilha.inserirLogo;
import static com.ramajo.logs.system.services.EscritorPlanilha.nome;
import static com.ramajo.logs.system.services.EscritorPlanilha.numero;
import static com.ramajo.logs.system.services.EscritorPlanilha.rotulo;
import static com.ramajo.logs.system.services.EscritorPlanilha.situacaoDaEtapa;
import static com.ramajo.logs.system.services.EscritorPlanilha.situacaoDaOrdem;
import static com.ramajo.logs.system.services.EscritorPlanilha.texto;
import static com.ramajo.logs.system.services.EscritorPlanilha.valorData;
import static com.ramajo.logs.system.services.EscritorPlanilha.valorTexto;

import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.exceptions.PeriodoInvalidoException;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.util.DataHoraBr;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exportação das Ordens de Serviço ABERTAS num período, uma linha por OS.
 *
 * Irmão de {@link PlanilhaOrdemServicoService}: mesmos estilos, mesma tipagem de
 * célula (data de verdade, duração somável), outro recorte. Lá o assunto é uma
 * OS por dentro, aqui é a fila de OSs de um intervalo, vista de cima.
 *
 * A janela é [início do dia de dataInicio, início do dia seguinte a dataFim) no
 * fuso da fábrica — fim exclusivo, para o último dia entrar inteiro sem
 * depender da precisão do timestamp.
 *
 * Três abas:
 *   Relatório - resumo do período no topo e a tabela de OSs abaixo, para ler e
 *               imprimir.
 *   Dados     - a mesma tabela crua, com autofiltro, para filtrar ou pivotar.
 *   Etapas    - uma linha por passo de todas as OSs do período, de onde saem os
 *               números resumidos nas outras duas.
 *
 * A OS é identificada pelo "N° da OS", que é o id externo — o número do sistema
 * principal. O id interno não aparece em aba nenhuma.
 *
 * Três medidas de tempo, que respondem perguntas diferentes:
 *   Duração total    - relógio de parede entre abrir e fechar a OS (inclui fila,
 *                      espera, turno da noite).
 *   Tempo trabalhado - a soma das etapas efetivamente concluídas nela.
 *   Pré/Tratamento/Pós - esse mesmo tempo, quebrado por etapa do fluxo.
 */
@Service
@RequiredArgsConstructor
public class PlanilhaPeriodoService {

    private static final int COLUNAS = 19;
    private static final int COL_DURACAO_TOTAL = 8;    // I
    private static final int COL_TRABALHADO = 9;       // J
    private static final int[] LARGURAS = {
            14, 32, 14, 14, 21, 20, 21, 20, 14, 17, 9, 11, 9, 12, 11, 12, 16, 14, 16};
    private static final double ALTURA_TITULO = 48;
    /** Mesma largura de logo nos dois relatórios, independente da coluna A. */
    private static final int LARGURA_LOGO = 224;
    /**
     * Onde começa o texto do título. Aqui a coluna A tem só 98px (N° da OS), e
     * o logo tem 224 — ele transborda para a B, então o texto começa na C. Como
     * a linha do título é uma faixa de cor única, o transbordo não aparece.
     */
    private static final int COL_TITULO = 2;

    private static final String[] CABECALHO = {
            "N° da OS", "Cliente", "Posição", "Situação", "Iniciada em", "Iniciada por",
            "Finalizada em", "Finalizada por", "Duração total", "Tempo trabalhado",
            "Cargas", "Processos", "Etapas", "Concluídas", "Em aberto", "Canceladas",
            "Pré-tratamento", "Tratamento", "Pós-tratamento"};

    private static final int COLUNAS_ETAPAS = 15;
    private static final String[] CABECALHO_ETAPAS = {
            "N° da OS", "Cliente",
            "OS iniciada em (data)", "OS iniciada em (hora)",
            "OS finalizada em (data)", "OS finalizada em (hora)",
            "Carga", "Tipo da carga", "Processo", "Etapa", "Responsável",
            "Iniciado em", "Finalizado em", "Duração", "Situação"};

    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Postgres não aceita lista de parâmetros sem fim num `in`; um período longo
     * pode passar de mil OSs, então a busca das etapas vai em blocos.
     */
    private static final int LOTE_DE_IDS = 1000;

    private final OrdemServicoRepository osRepo;
    private final LogRepository logRepo;

    @Transactional(readOnly = true)
    public byte[] gerar(LocalDate dataInicio, LocalDate dataFim) {
        if (dataFim.isBefore(dataInicio)) {
            throw new PeriodoInvalidoException(dataInicio, dataFim);
        }

        List<OrdemServico> ordens = osRepo.buscarParaRelatorioPorPeriodo(
                DataHoraBr.inicioDoDia(dataInicio), DataHoraBr.inicioDoDiaSeguinte(dataFim));
        List<Log> etapas = etapasDas(ordens);
        Map<Long, List<Log>> porOrdem = agruparPorOrdem(etapas);
        Map<Long, ResumoDeEtapas> resumos = resumirPorOrdem(ordens, porOrdem);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream saida = new ByteArrayOutputStream()) {

            EstilosPlanilha estilos = new EstilosPlanilha(wb);
            abaRelatorio(wb, estilos, dataInicio, dataFim, ordens, resumos, etapas);
            abaDados(wb, estilos, ordens, resumos);
            abaEtapas(wb, estilos, etapas);

            // As somas do resumo são fórmulas sem valor em cache; sem isto o
            // LibreOffice abre mostrando célula vazia até alguém editar.
            wb.setForceFormulaRecalculation(true);

            wb.write(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream não faz I/O de verdade; se estourar aqui é
            // falha de montagem do arquivo, não erro de domínio.
            throw new UncheckedIOException(
                    "Falha ao gerar a planilha do período " + dataInicio + " a " + dataFim, e);
        }
    }

    // ----------------------------------------------------------------- dados

    private List<Log> etapasDas(List<OrdemServico> ordens) {
        if (ordens.isEmpty()) {
            return List.of();   // `in ()` é SQL inválido — nem chega a consultar
        }
        List<Long> ids = ordens.stream().map(OrdemServico::getId).toList();
        List<Log> etapas = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += LOTE_DE_IDS) {
            etapas.addAll(logRepo.buscarParaRelatorioDeOrdens(
                    ids.subList(i, Math.min(i + LOTE_DE_IDS, ids.size()))));
        }
        return etapas;
    }

    private Map<Long, List<Log>> agruparPorOrdem(List<Log> etapas) {
        Map<Long, List<Log>> porOrdem = new LinkedHashMap<>();
        for (Log log : etapas) {
            porOrdem.computeIfAbsent(log.getOrdemServico().getId(), id -> new ArrayList<>())
                    .add(log);
        }
        return porOrdem;
    }

    /**
     * Os números de etapa de cada OS, apurados uma vez sobre os mesmos logs que
     * alimentam a aba Etapas.
     *
     * Tempo sem etapa concluída fica NULO, não zero: célula vazia diz "nada
     * apurado", enquanto um zero afirmaria que se trabalhou zero segundo ali —
     * e entraria nas somas e médias como se fosse medição.
     */
    private Map<Long, ResumoDeEtapas> resumirPorOrdem(List<OrdemServico> ordens,
                                                      Map<Long, List<Log>> porOrdem) {
        Map<Long, ResumoDeEtapas> resumos = new HashMap<>();
        for (OrdemServico os : ordens) {
            List<Log> daOrdem = porOrdem.getOrDefault(os.getId(), List.of());
            resumos.put(os.getId(), new ResumoDeEtapas(
                    distintos(daOrdem, l -> l.getCarga().getId()),
                    distintos(daOrdem, l -> l.getProcesso().getId()),
                    daOrdem.size(),
                    daOrdem.stream().filter(PlanilhaPeriodoService::concluida).count(),
                    daOrdem.stream()
                            .filter(l -> !l.isCancelado() && l.getFinalizadoEm() == null).count(),
                    daOrdem.stream().filter(Log::isCancelado).count(),
                    somaDe(daOrdem, l -> true),
                    somaDe(daOrdem, etapaEh(Etapa.PRE_TRATAMENTO)),
                    somaDe(daOrdem, etapaEh(Etapa.TRATAMENTO)),
                    somaDe(daOrdem, etapaEh(Etapa.POS_TRATAMENTO))));
        }
        return resumos;
    }

    private static boolean concluida(Log log) {
        return !log.isCancelado() && log.getFinalizadoEm() != null;
    }

    private Predicate<Log> etapaEh(Etapa etapa) {
        return log -> log.getProcesso().getEtapa() == etapa;
    }

    private long distintos(List<Log> logs, java.util.function.Function<Log, Long> chave) {
        Set<Long> vistos = new HashSet<>();
        for (Log log : logs) {
            vistos.add(chave.apply(log));
        }
        return vistos.size();
    }

    /** Soma as etapas CONCLUÍDAS que passam no filtro; nenhuma delas devolve null. */
    private Double somaDe(List<Log> logs, Predicate<Log> filtro) {
        Double total = null;
        for (Log log : logs) {
            if (!concluida(log) || !filtro.test(log)) {
                continue;
            }
            Double d = DataHoraBr.duracaoNumerica(log.getIniciadoEm(), log.getFinalizadoEm());
            if (d != null) {
                total = total == null ? d : total + d;
            }
        }
        return total;
    }

    /** Os números de etapa de uma OS, do jeito que a linha dela precisa. */
    private record ResumoDeEtapas(long cargas, long processos, long total, long concluidas,
                                  long emAberto, long canceladas, Double trabalhado,
                                  Double pre, Double tratamento, Double pos) {
    }

    // ------------------------------------------------------------- relatório

    private void abaRelatorio(XSSFWorkbook wb, EstilosPlanilha e, LocalDate inicio,
                              LocalDate fim, List<OrdemServico> ordens,
                              Map<Long, ResumoDeEtapas> resumos, List<Log> etapas) {
        Sheet aba = wb.createSheet("Relatório");
        for (int i = 0; i < COLUNAS; i++) {
            aba.setColumnWidth(i, LARGURAS[i] * 256);
        }

        int[] linha = {0};
        titulo(wb, aba, e, linha);
        // As somas do resumo só podem ser escritas depois da tabela, quando se
        // sabe qual faixa somar — mas o resumo fica ACIMA dela. As células
        // nascem aqui vazias e voltam a ser preenchidas no fim.
        Somas somas = resumo(aba, e, linha, inicio, fim, etapas);
        indicadores(aba, e, linha, ordens);

        cabecalhoTabela(aba, e, linha, CABECALHO);
        // Congela tudo que está acima das linhas de dados: rolando uma lista
        // longa, o cabeçalho e o resumo continuam à vista.
        aba.createFreezePane(0, linha[0]);

        int primeira = linha[0];
        boolean zebra = false;
        for (OrdemServico os : ordens) {
            escreverOrdem(aba.createRow(linha[0]++), e, os, resumos.get(os.getId()), zebra);
            zebra = !zebra;
        }
        int ultima = linha[0] - 1;

        // Fórmula, não valor pronto: se alguém corrigir uma linha na mão, o
        // resumo acompanha. Período vazio não tem faixa para somar.
        if (ultima >= primeira) {
            somas.duracaoTotal().setCellFormula(soma("I", primeira, ultima));
            somas.trabalhado().setCellFormula(soma("J", primeira, ultima));
        }

        rodape(aba, e, linha, inicio, fim);
        imprimivel(aba);
    }

    private String soma(String coluna, int primeira, int ultima) {
        return "SUM(" + coluna + (primeira + 1) + ":" + coluna + (ultima + 1) + ")";
    }

    private void titulo(XSSFWorkbook wb, Sheet aba, EstilosPlanilha e, int[] linha) {
        Row r = aba.createRow(linha[0]);
        r.setHeightInPoints((float) ALTURA_TITULO);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(e.titulo);
        }
        // As primeiras colunas ficam reservadas ao logo; o texto começa depois
        // delas para os dois não disputarem o mesmo espaço.
        r.getCell(COL_TITULO).setCellValue("RELATÓRIO DE ORDENS DE SERVIÇO POR PERÍODO");
        aba.addMergedRegion(
                new CellRangeAddress(linha[0], linha[0], COL_TITULO, COLUNAS - 1));

        inserirLogo(wb, aba, linha[0], LARGURA_LOGO, ALTURA_TITULO);
        linha[0] += 2;
    }

    /** As duas células de soma do resumo, preenchidas depois que a tabela existe. */
    private record Somas(Cell duracaoTotal, Cell trabalhado) {
    }

    /** O bloco de identificação do recorte. */
    private Somas resumo(Sheet aba, EstilosPlanilha e, int[] linha,
                         LocalDate inicio, LocalDate fim, List<Log> etapas) {
        Row r1 = aba.createRow(linha[0]);
        rotulo(r1, e, 0, "Período");
        // Texto, não data: o valor é um intervalo, e uma célula de data teria de
        // escolher uma das duas pontas.
        valorTexto(r1, e, 1, inicio.format(BR) + " a " + fim.format(BR));
        rotulo(r1, e, 4, "Emitido em");
        valorData(r1, e, 5, Instant.now());
        mesclarValores(aba, linha[0]);

        Row r2 = aba.createRow(linha[0] + 1);
        rotulo(r2, e, 0, "Duração total (soma)");
        Cell somaDuracao = r2.createCell(1);
        somaDuracao.setCellStyle(e.valorResumoDuracao);
        rotulo(r2, e, 4, "Tempo trabalhado (soma)");
        Cell somaTrabalhado = r2.createCell(5);
        somaTrabalhado.setCellStyle(e.valorResumoDuracao);
        mesclarValores(aba, linha[0] + 1);

        long concluidas = etapas.stream().filter(PlanilhaPeriodoService::concluida).count();
        long emAberto = etapas.stream()
                .filter(l -> !l.isCancelado() && l.getFinalizadoEm() == null).count();
        Row r3 = aba.createRow(linha[0] + 2);
        rotulo(r3, e, 0, "Etapas no período");
        valorTexto(r3, e, 1, etapas.size() + " (" + concluidas + " concluídas, "
                + emAberto + " em aberto)");
        rotulo(r3, e, 4, "Cargas no período");
        valorTexto(r3, e, 5, String.valueOf(distintos(etapas, l -> l.getCarga().getId())));
        mesclarValores(aba, linha[0] + 2);

        linha[0] += 4;
        return new Somas(somaDuracao, somaTrabalhado);
    }

    private void indicadores(Sheet aba, EstilosPlanilha e, int[] linha, List<OrdemServico> ordens) {
        long canceladas = ordens.stream().filter(OrdemServico::isCancelada).count();
        long finalizadas = ordens.stream()
                .filter(os -> !os.isCancelada() && os.isFinalizada()).count();
        long emProcesso = ordens.size() - canceladas - finalizadas;

        String[] legendas = {"OS NO PERÍODO", "FINALIZADAS", "EM PROCESSO", "CANCELADAS"};
        String[] valores = {
                String.valueOf(ordens.size()),
                String.valueOf(finalizadas),
                String.valueOf(emProcesso),
                String.valueOf(canceladas)
        };
        // Quatro indicadores em dezenove colunas: três de cinco colunas e o
        // último na sobra de quatro.
        int[][] faixas = {{0, 4}, {5, 9}, {10, 14}, {15, 18}};

        Row rLegenda = aba.createRow(linha[0]);
        Row rValor = aba.createRow(linha[0] + 1);
        rValor.setHeightInPoints(26);
        for (int i = 0; i < COLUNAS; i++) {
            rLegenda.createCell(i).setCellStyle(e.indicadorLegenda);
            rValor.createCell(i).setCellStyle(e.indicadorValor);
        }
        for (int[] faixa : faixas) {
            aba.addMergedRegion(new CellRangeAddress(linha[0], linha[0], faixa[0], faixa[1]));
            aba.addMergedRegion(new CellRangeAddress(linha[0] + 1, linha[0] + 1, faixa[0], faixa[1]));
        }
        for (int i = 0; i < faixas.length; i++) {
            rLegenda.getCell(faixas[i][0]).setCellValue(legendas[i]);
            rValor.getCell(faixas[i][0]).setCellValue(valores[i]);
        }
        linha[0] += 3;
    }

    private void escreverOrdem(Row r, EstilosPlanilha e, OrdemServico os,
                               ResumoDeEtapas resumo, boolean zebra) {
        boolean cancelada = os.isCancelada();
        // Identificador vai como texto: é rótulo, não quantidade.
        texto(r, e, 0, os.getIdExterno() == null ? null : String.valueOf(os.getIdExterno()),
                zebra, cancelada);
        texto(r, e, 1, os.getCliente().getNome(), zebra, cancelada);
        texto(r, e, 2, os.getPosicao().name(), zebra, cancelada);
        texto(r, e, 3, situacaoDaOrdem(os), zebra, cancelada);
        data(r, e, 4, os.getIniciadaEm(), zebra, cancelada);
        texto(r, e, 5, nome(os.getIniciadaPor()), zebra, cancelada);
        data(r, e, 6, os.getFinalizadaEm(), zebra, cancelada);
        texto(r, e, 7, nome(os.getFinalizadaPor()), zebra, cancelada);
        // OS cancelada não escreve tempo: célula vazia é ignorada pelas somas do
        // resumo, enquanto um zero entraria na conta e puxaria as médias. As
        // CONTAGENS continuam sendo escritas — o trabalho existiu, mesmo que a
        // ordem tenha sido invalidada depois.
        duracao(r, e, COL_DURACAO_TOTAL,
                cancelada ? null : DataHoraBr.duracaoNumerica(os.getIniciadaEm(), os.getFinalizadaEm()),
                zebra, cancelada);
        duracao(r, e, COL_TRABALHADO, cancelada ? null : resumo.trabalhado(), zebra, cancelada);
        numero(r, e, 10, resumo.cargas(), zebra, cancelada);
        numero(r, e, 11, resumo.processos(), zebra, cancelada);
        numero(r, e, 12, resumo.total(), zebra, cancelada);
        numero(r, e, 13, resumo.concluidas(), zebra, cancelada);
        numero(r, e, 14, resumo.emAberto(), zebra, cancelada);
        numero(r, e, 15, resumo.canceladas(), zebra, cancelada);
        duracao(r, e, 16, cancelada ? null : resumo.pre(), zebra, cancelada);
        duracao(r, e, 17, cancelada ? null : resumo.tratamento(), zebra, cancelada);
        duracao(r, e, 18, cancelada ? null : resumo.pos(), zebra, cancelada);
    }

    private void rodape(Sheet aba, EstilosPlanilha e, int[] linha,
                        LocalDate inicio, LocalDate fim) {
        // Uma linha de respiro entre a tabela e o rodapé.
        Row r = aba.createRow(linha[0] + 1);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(e.rodape);
        }
        r.getCell(0).setCellValue("Ramajo Logs — ordens iniciadas de " + inicio.format(BR)
                + " a " + fim.format(BR) + " — gerado automaticamente");
        aba.addMergedRegion(new CellRangeAddress(linha[0] + 1, linha[0] + 1, 0, COLUNAS - 1));
        linha[0] += 2;
    }

    /** Os valores do resumo ocupam B:D e F:I; o resto da linha fica em branco. */
    private void mesclarValores(Sheet aba, int linha) {
        Row r = aba.getRow(linha);
        for (int coluna = 0; coluna < COLUNAS; coluna++) {
            if (r.getCell(coluna) == null) {
                r.createCell(coluna);
            }
        }
        aba.addMergedRegion(new CellRangeAddress(linha, linha, 1, 3));
        aba.addMergedRegion(new CellRangeAddress(linha, linha, 5, 8));
    }

    // ------------------------------------------------------------ abas planas

    /** A mesma tabela sem resumo nem mesclagem: é a aba de quem vai filtrar ou pivotar. */
    private void abaDados(XSSFWorkbook wb, EstilosPlanilha e, List<OrdemServico> ordens,
                          Map<Long, ResumoDeEtapas> resumos) {
        Sheet aba = wb.createSheet("Dados");
        int[] linha = {0};
        cabecalhoTabela(aba, e, linha, CABECALHO);
        // Duas colunas congeladas: numa tabela desta largura, rolar para as
        // colunas de tempo deixaria as linhas sem identificação.
        aba.createFreezePane(2, 1);

        boolean zebra = false;
        for (OrdemServico os : ordens) {
            escreverOrdem(aba.createRow(linha[0]++), e, os, resumos.get(os.getId()), zebra);
            zebra = !zebra;
        }
        // Tabela contínua e sem resumo: aqui o autofiltro não tem o que quebrar.
        aba.setAutoFilter(new CellRangeAddress(0, Math.max(linha[0] - 1, 0), 0, COLUNAS - 1));
        ajustar(aba, COLUNAS);
    }

    /**
     * Uma linha por passo, de todas as OSs do período — é daqui que saem os
     * contadores e os tempos por etapa das outras abas, e é aqui que se confere
     * um número que não bateu.
     */
    private void abaEtapas(XSSFWorkbook wb, EstilosPlanilha e, List<Log> etapas) {
        Sheet aba = wb.createSheet("Etapas");
        int[] linha = {0};
        cabecalhoTabela(aba, e, linha, CABECALHO_ETAPAS);
        aba.createFreezePane(2, 1);

        boolean zebra = false;
        for (Log log : etapas) {
            boolean cancelada = log.isCancelado();
            OrdemServico os = log.getOrdemServico();
            Row r = aba.createRow(linha[0]++);
            texto(r, e, 0, os.getIdExterno() == null ? null : String.valueOf(os.getIdExterno()),
                    zebra, cancelada);
            texto(r, e, 1, os.getCliente().getNome(), zebra, cancelada);
            // Os carimbos da OS vão em dia e hora separados; os da etapa (11 e
            // 12) continuam inteiros, num campo só.
            dia(r, e, 2, os.getIniciadaEm(), zebra, cancelada);
            hora(r, e, 3, os.getIniciadaEm(), zebra, cancelada);
            dia(r, e, 4, os.getFinalizadaEm(), zebra, cancelada);
            hora(r, e, 5, os.getFinalizadaEm(), zebra, cancelada);
            texto(r, e, 6, log.getCarga().getNome(), zebra, cancelada);
            texto(r, e, 7, log.getCarga().getTipo().name(), zebra, cancelada);
            texto(r, e, 8, log.getProcesso().getDescricao(), zebra, cancelada);
            texto(r, e, 9, log.getProcesso().getEtapa().name(), zebra, cancelada);
            texto(r, e, 10, log.getResponsavel().getNome(), zebra, cancelada);
            data(r, e, 11, log.getIniciadoEm(), zebra, cancelada);
            data(r, e, 12, log.getFinalizadoEm(), zebra, cancelada);
            // Etapa cancelada não escreve duração, pelo mesmo motivo da OS.
            duracao(r, e, 13,
                    cancelada ? null : DataHoraBr.duracaoNumerica(log.getIniciadoEm(), log.getFinalizadoEm()),
                    zebra, cancelada);
            texto(r, e, 14, situacaoDaEtapa(log), zebra, cancelada);
            zebra = !zebra;
        }
        aba.setAutoFilter(new CellRangeAddress(0, Math.max(linha[0] - 1, 0), 0, COLUNAS_ETAPAS - 1));
        ajustar(aba, COLUNAS_ETAPAS);
    }
}
