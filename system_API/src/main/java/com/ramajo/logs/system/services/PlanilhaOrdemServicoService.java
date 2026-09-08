package com.ramajo.logs.system.services;

import static com.ramajo.logs.system.services.EscritorPlanilha.ajustar;
import static com.ramajo.logs.system.services.EscritorPlanilha.cabecalhoTabela;
import static com.ramajo.logs.system.services.EscritorPlanilha.data;
import static com.ramajo.logs.system.services.EscritorPlanilha.duracao;
import static com.ramajo.logs.system.services.EscritorPlanilha.imprimivel;
import static com.ramajo.logs.system.services.EscritorPlanilha.inserirLogo;
import static com.ramajo.logs.system.services.EscritorPlanilha.nome;
import static com.ramajo.logs.system.services.EscritorPlanilha.rotulo;
import static com.ramajo.logs.system.services.EscritorPlanilha.situacaoDaEtapa;
import static com.ramajo.logs.system.services.EscritorPlanilha.situacaoDaOrdem;
import static com.ramajo.logs.system.services.EscritorPlanilha.texto;
import static com.ramajo.logs.system.services.EscritorPlanilha.valorData;
import static com.ramajo.logs.system.services.EscritorPlanilha.valorDuracao;
import static com.ramajo.logs.system.services.EscritorPlanilha.valorTexto;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OrdemDesidrogenizacaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.util.DataHoraBr;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exportação de UMA Ordem de Serviço para .xlsx.
 *
 * O workbook é montado aqui dentro, com a transação aberta, para não depender
 * do open-in-view: quando o controller devolve os bytes já não há mais sessão.
 *
 * São três abas com públicos diferentes:
 *   Relatório         - para ler e imprimir: identificação, indicadores e as etapas
 *                       agrupadas por carga, cada bloco com subtotal e retrátil (+/-).
 *   Lotes             - o fechamento de lote a lote.
 *   Desidrogenizações - o forno: uma linha por aplicação. Fica em aba própria,
 *                       e não entre as etapas, porque não é uma delas — não tem
 *                       carga nem processo, e corre no nível da OS inteira.
 *   Dados             - a mesma coisa em lista plana, com autofiltro, para quem quiser
 *               filtrar ou pivotar. O autofiltro vive só aqui: numa aba com
 *               blocos e subtotais ele esconderia os subtítulos junto.
 *
 * Os estilos e a escrita de célula (data de verdade, duração somável) moram em
 * {@link EstilosPlanilha} e {@link EscritorPlanilha}, compartilhados com os
 * demais relatórios.
 */
@Service
@RequiredArgsConstructor
public class PlanilhaOrdemServicoService {

    /** Colunas da aba Relatório: Processo, Etapa, Responsável, Início, Fim, Duração, Situação. */
    private static final int COLUNAS = 7;
    private static final int COL_DURACAO = 5;
    private static final int[] LARGURAS = {32, 18, 24, 21, 21, 12, 15};
    private static final double ALTURA_TITULO = 48;
    /** Mesma largura de logo nos dois relatórios, independente da coluna A. */
    private static final int LARGURA_LOGO = 224;
    /** Onde começa o texto do título: a coluna A é do logo. */
    private static final int COL_TITULO = 1;

    private final OrdemServicoRepository osRepo;
    private final LogRepository logRepo;
    private final LoteRepository loteRepo;
    private final OrdemDesidrogenizacaoRepository desidroRepo;

    @Transactional(readOnly = true)
    public byte[] gerar(Long osId) {
        OrdemServico os = osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));

        List<Lote> lotes = loteRepo.buscarParaRelatorio(osId);
        List<Log> logs = logRepo.buscarParaRelatorio(osId);
        // Passos em que ESTA OS pegou carona na carga de outra. Vêm numa lista
        // separada e assim permanecem: os indicadores continuam apurados só
        // sobre `logs`, senão a etapa compartilhada contaria duas vezes na
        // fábrica — uma na titular e outra aqui.
        List<Log> acopladas = logRepo.buscarAcopladasParaRelatorio(osId);
        List<OrdemDesidrogenizacao> desidros = desidroRepo.buscarDaOrdem(osId);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream saida = new ByteArrayOutputStream()) {

            EstilosPlanilha estilos = new EstilosPlanilha(wb);
            abaRelatorio(wb, estilos, os, lotes, logs, acopladas, desidros);
            abaLotes(wb, estilos, lotes);
            abaDesidrogenizacoes(wb, estilos, desidros);
            abaDados(wb, estilos, logs, acopladas);

            // Os subtotais são fórmulas sem valor em cache; sem isto o
            // LibreOffice abre mostrando célula vazia até alguém editar.
            wb.setForceFormulaRecalculation(true);

            wb.write(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream não faz I/O de verdade; se estourar aqui é
            // falha de montagem do arquivo, não erro de domínio.
            throw new UncheckedIOException("Falha ao gerar a planilha da OS " + osId, e);
        }
    }

    // ------------------------------------------------------------- relatório

    private void abaRelatorio(XSSFWorkbook wb, EstilosPlanilha e, OrdemServico os,
                              List<Lote> lotes, List<Log> logs, List<Log> acopladas,
                              List<OrdemDesidrogenizacao> desidros) {
        Sheet aba = wb.createSheet("Relatório");
        for (int i = 0; i < COLUNAS; i++) {
            aba.setColumnWidth(i, LARGURAS[i] * 256);
        }
        // Em blocos agrupados, o botão +/- pertence à linha de subtotal, que
        // fica abaixo dos dados.
        aba.setRowSumsBelow(true);

        int[] linha = {0};
        Map<Long, List<Log>> porCarga = agruparPorCarga(logs);

        titulo(wb, aba, e, linha);
        identificacao(aba, e, linha, os, acopladas, desidros);
        indicadores(aba, e, linha, lotes, logs, porCarga.size());

        for (List<Log> daCarga : porCarga.values()) {
            blocoDaCarga(aba, e, linha, daCarga, null);
        }

        // Depois dos blocos próprios, e nunca misturado com eles: a carga é de
        // outra OS, e o tempo tem subtotal próprio para ficar visível sem ser
        // somado ao trabalho desta ordem.
        for (List<Log> daCarga : agruparPorCarga(acopladas).values()) {
            blocoDaCarga(aba, e, linha, daCarga, daCarga.get(0).getOrdemServico());
        }

        rodape(aba, e, linha, os);
        imprimivel(aba);
    }

    /**
     * O agrupamento sai dos LOGS, não de {@code os.getCargas()}: aquela coleção
     * é o vínculo ATUAL (Carga.ordemAtual), então uma carga já liberada sumiria
     * do relatório mesmo tendo trabalhado na ordem.
     *
     * A query já vem ordenada por iniciadoEm; o LinkedHashMap preserva a ordem
     * de primeira aparição, e é daí que sai a ordem cronológica de entrada dos
     * blocos — sem nenhuma ordenação extra.
     */
    private Map<Long, List<Log>> agruparPorCarga(List<Log> logs) {
        Map<Long, List<Log>> porCarga = new LinkedHashMap<>();
        for (Log log : logs) {
            porCarga.computeIfAbsent(log.getCarga().getId(), id -> new ArrayList<>())
                    .add(log);
        }
        return porCarga;
    }

    private void titulo(XSSFWorkbook wb, Sheet aba, EstilosPlanilha e, int[] linha) {
        Row r = aba.createRow(linha[0]);
        r.setHeightInPoints((float) ALTURA_TITULO);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(e.titulo);
        }
        // As primeiras colunas ficam reservadas ao logo; o texto começa depois
        // delas para os dois não disputarem o mesmo espaço.
        r.getCell(COL_TITULO).setCellValue("RELATÓRIO DE ORDEM DE SERVIÇO");
        aba.addMergedRegion(
                new CellRangeAddress(linha[0], linha[0], COL_TITULO, COLUNAS - 1));

        inserirLogo(wb, aba, linha[0], LARGURA_LOGO, ALTURA_TITULO);
        linha[0] += 2;
    }

    private void identificacao(Sheet aba, EstilosPlanilha e, int[] linha, OrdemServico os,
                               List<Log> acopladas, List<OrdemDesidrogenizacao> desidros) {
        parTexto(aba, e, linha[0], "OS", String.valueOf(os.getId()),
                "Situação", situacaoDaOrdem(os));
        parTexto(aba, e, linha[0] + 1, "Cliente", os.getCliente().getNome(),
                "ID externo", os.getIdExterno() == null ? "" : String.valueOf(os.getIdExterno()));

        Row r = aba.createRow(linha[0] + 2);
        rotulo(r, e, 0, "Posição");
        valorTexto(r, e, 1, os.getPosicao().name());
        rotulo(r, e, 3, "Duração total");
        valorDuracao(r, e, 4, DataHoraBr.duracaoNumerica(os.getIniciadaEm(), os.getFinalizadaEm()));
        mesclarValores(aba, linha[0] + 2);

        Row r4 = aba.createRow(linha[0] + 3);
        rotulo(r4, e, 0, "Iniciada em");
        valorData(r4, e, 1, os.getIniciadaEm());
        rotulo(r4, e, 3, "Iniciada por");
        valorTexto(r4, e, 4, nome(os.getIniciadaPor()));
        mesclarValores(aba, linha[0] + 3);

        Row r5 = aba.createRow(linha[0] + 4);
        rotulo(r5, e, 0, "Finalizada em");
        valorData(r5, e, 1, os.getFinalizadaEm());
        rotulo(r5, e, 3, "Finalizada por");
        valorTexto(r5, e, 4, nome(os.getFinalizadaPor()));
        mesclarValores(aba, linha[0] + 4);

        // O forno é uma quarta medida de tempo, ao lado da duração total: corre
        // no nível da OS, sem carga e sem etapa, e por isso não entra no
        // subtotal de nenhum bloco lá em baixo.
        Row r6 = aba.createRow(linha[0] + 5);
        rotulo(r6, e, 0, "Desidrogenizações");
        valorTexto(r6, e, 1, String.valueOf(desidros.size()));
        rotulo(r6, e, 3, "Tempo em forno");
        valorDuracao(r6, e, 4, tempoEmForno(desidros));
        mesclarValores(aba, linha[0] + 5);

        // Quinta medida, na mesma prateleira do forno: tempo em que as peças
        // desta OS estiveram numa carga de outra. Fica aqui, e não entre os
        // indicadores, justamente por NÃO ser produção desta ordem — os
        // indicadores contam o que ela executou.
        Row r7 = aba.createRow(linha[0] + 6);
        rotulo(r7, e, 0, "Etapas acopladas");
        valorTexto(r7, e, 1, String.valueOf(acopladas.size()));
        rotulo(r7, e, 3, "Tempo acoplado");
        valorDuracao(r7, e, 4, tempoAcoplado(acopladas));
        mesclarValores(aba, linha[0] + 6);

        linha[0] += 8;
    }

    /**
     * A soma das etapas acopladas CONCLUÍDAS, como fração de dia. Nula e não
     * zero quando não houve nenhuma — a mesma regra do tempo em forno: célula
     * vazia diz "não houve", um zero afirmaria duração zero.
     */
    private Double tempoAcoplado(List<Log> acopladas) {
        Double total = null;
        for (Log log : acopladas) {
            if (log.isCancelado()) {
                continue;
            }
            Double parcela =
                    DataHoraBr.duracaoNumerica(log.getIniciadoEm(), log.getFinalizadoEm());
            if (parcela != null) {
                total = total == null ? parcela : total + parcela;
            }
        }
        return total;
    }

    /**
     * A soma das desidrogenizações, como fração de dia. Nenhuma devolve null e
     * não zero: célula vazia diz "não houve", enquanto um zero afirmaria que o
     * forno rodou por zero segundo — a mesma regra do relatório por período.
     */
    private Double tempoEmForno(List<OrdemDesidrogenizacao> desidros) {
        if (desidros.isEmpty()) {
            return null;
        }
        double total = 0d;
        for (OrdemDesidrogenizacao d : desidros) {
            Double parcela = DataHoraBr.duracaoNumerica(d.getIniciadaEm(), d.getFinalizadaEm());
            if (parcela != null) {
                total += parcela;
            }
        }
        return total;
    }

    private void indicadores(Sheet aba, EstilosPlanilha e, int[] linha, List<Lote> lotes,
                             List<Log> logs, int totalCargas) {
        long lotesFinalizados = lotes.stream().filter(Lote::isFinalizado).count();
        long emAberto = logs.stream()
                .filter(l -> l.getFinalizadoEm() == null && !l.isCancelado()).count();

        String[] legendas = {"LOTES", "ETAPAS", "EM ABERTO", "CARGAS"};
        String[] valores = {
                lotesFinalizados + " / " + lotes.size(),
                String.valueOf(logs.size()),
                String.valueOf(emAberto),
                String.valueOf(totalCargas)
        };
        // Quatro indicadores em sete colunas: os três primeiros ocupam duas
        // colunas cada, o último fica na sobra.
        int[][] faixas = {{0, 1}, {2, 3}, {4, 5}, {6, 6}};

        Row rLegenda = aba.createRow(linha[0]);
        Row rValor = aba.createRow(linha[0] + 1);
        rValor.setHeightInPoints(26);
        for (int i = 0; i < COLUNAS; i++) {
            rLegenda.createCell(i).setCellStyle(e.indicadorLegenda);
            rValor.createCell(i).setCellStyle(e.indicadorValor);
        }
        for (int i = 0; i < faixas.length; i++) {
            rLegenda.getCell(faixas[i][0]).setCellValue(legendas[i]);
            rValor.getCell(faixas[i][0]).setCellValue(valores[i]);
            if (faixas[i][0] != faixas[i][1]) {
                aba.addMergedRegion(new CellRangeAddress(linha[0], linha[0], faixas[i][0], faixas[i][1]));
                aba.addMergedRegion(new CellRangeAddress(linha[0] + 1, linha[0] + 1, faixas[i][0], faixas[i][1]));
            }
        }
        linha[0] += 3;
    }

    /**
     * Um bloco de etapas. `titular` nulo = carga desta OS; preenchido = a carga
     * era de outra ordem e estas peças foram junto.
     */
    private void blocoDaCarga(Sheet aba, EstilosPlanilha e, int[] linha, List<Log> daCarga,
                              OrdemServico titular) {
        Carga carga = daCarga.get(0).getCarga();

        Row rTitulo = aba.createRow(linha[0]);
        rTitulo.setHeightInPoints(20);
        for (int i = 0; i < COLUNAS; i++) {
            rTitulo.createCell(i).setCellStyle(e.subtituloCarga);
        }
        rTitulo.getCell(0).setCellValue(descricaoDaCarga(carga, titular));
        aba.addMergedRegion(new CellRangeAddress(linha[0], linha[0], 0, COLUNAS - 1));
        linha[0]++;

        cabecalhoTabela(aba, e, linha, "Processo", "Etapa", "Responsável",
                "Início", "Fim", "Duração", "Situação");

        int primeira = linha[0];
        boolean zebra = false;
        for (Log log : daCarga) {
            boolean cancelada = log.isCancelado();
            Row r = aba.createRow(linha[0]++);
            texto(r, e, 0, log.getProcesso().getDescricao(), zebra, cancelada);
            texto(r, e, 1, log.getProcesso().getEtapa().name(), zebra, cancelada);
            texto(r, e, 2, log.getResponsavel().getNome(), zebra, cancelada);
            data(r, e, 3, log.getIniciadoEm(), zebra, cancelada);
            data(r, e, 4, log.getFinalizadoEm(), zebra, cancelada);
            // Etapa cancelada não escreve duração: célula vazia é ignorada pelo
            // SOMA do subtotal, enquanto um zero entraria na conta.
            duracao(r, e, COL_DURACAO,
                    cancelada ? null : DataHoraBr.duracaoNumerica(log.getIniciadoEm(), log.getFinalizadoEm()),
                    zebra, cancelada);
            texto(r, e, 6, situacaoDaEtapa(log), zebra, cancelada);
            zebra = !zebra;
        }
        int ultima = linha[0] - 1;

        subtotal(aba, e, linha, primeira, ultima, titular != null);
        aba.groupRow(primeira, ultima);
        linha[0]++; // respiro entre blocos
    }

    private void subtotal(Sheet aba, EstilosPlanilha e, int[] linha, int primeira, int ultima,
                          boolean acoplado) {
        Row r = aba.createRow(linha[0]++);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(i == COL_DURACAO ? e.duracaoSubtotal : e.rotuloSubtotal);
        }
        // O rótulo é o cerco: o tempo acoplado aparece, mas dito com todas as
        // letras que não se soma ao das cargas próprias.
        r.getCell(0).setCellValue(acoplado
                ? "Subtotal acoplado (não entra no total da OS)"
                : "Subtotal da carga");
        aba.addMergedRegion(new CellRangeAddress(linha[0] - 1, linha[0] - 1, 0, COL_DURACAO - 1));
        // Fórmula, não valor pronto: se alguém corrigir uma linha na mão, o
        // subtotal acompanha.
        r.getCell(COL_DURACAO).setCellFormula(
                "SUM(F" + (primeira + 1) + ":F" + (ultima + 1) + ")");
    }

    private void rodape(Sheet aba, EstilosPlanilha e, int[] linha, OrdemServico os) {
        Row r = aba.createRow(linha[0]);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(e.rodape);
        }
        r.getCell(0).setCellValue("Emitido em");
        Cell quando = r.getCell(1);
        quando.setCellValue(DataHoraBr.local(Instant.now()));
        quando.setCellStyle(e.rodapeData);
        r.getCell(2).setCellValue("Ramajo Logs — OS " + os.getId() + " — gerado automaticamente");
        aba.addMergedRegion(new CellRangeAddress(linha[0], linha[0], 2, COLUNAS - 1));
        linha[0]++;
    }

    // ------------------------------------------------------------ outras abas

    private void abaLotes(Workbook wb, EstilosPlanilha e, List<Lote> lotes) {
        Sheet aba = wb.createSheet("Lotes");
        int[] linha = {0};
        cabecalhoTabela(aba, e, linha, "Número", "Iniciado em", "Finalizado em",
                "Duração", "Finalizado por", "Situação");
        aba.createFreezePane(0, 1);

        boolean zebra = false;
        for (Lote lote : lotes) {
            Row r = aba.createRow(linha[0]++);
            texto(r, e, 0, String.valueOf(lote.getNumero()), zebra, false);
            data(r, e, 1, lote.getIniciadoEm(), zebra, false);
            data(r, e, 2, lote.getFinalizadoEm(), zebra, false);
            duracao(r, e, 3,
                    DataHoraBr.duracaoNumerica(lote.getIniciadoEm(), lote.getFinalizadoEm()),
                    zebra, false);
            texto(r, e, 4, nome(lote.getFinalizadoPor()), zebra, false);
            texto(r, e, 5, lote.isFinalizado() ? "Finalizado" : "Aberto", zebra, false);
            zebra = !zebra;
        }
        ajustar(aba, 6);
    }

    /**
     * O forno, aplicação a aplicação. `Duração` e `Temperatura` são o que rodou —
     * cópias feitas no momento da aplicação —, não o cadastro de hoje: editar a
     * receita amanhã não pode reescrever o histórico de ontem.
     */
    private void abaDesidrogenizacoes(Workbook wb, EstilosPlanilha e,
                                      List<OrdemDesidrogenizacao> desidros) {
        Sheet aba = wb.createSheet("Desidrogenizações");
        int[] linha = {0};
        cabecalhoTabela(aba, e, linha, "Nome", "Duração", "Temperatura (°C)",
                "Iniciada em", "Finalizada em", "Aplicada por");
        aba.createFreezePane(0, 1);

        boolean zebra = false;
        for (OrdemDesidrogenizacao d : desidros) {
            Row r = aba.createRow(linha[0]++);
            texto(r, e, 0, d.getDesidrogenizacao().getNome(), zebra, false);
            duracao(r, e, 1,
                    DataHoraBr.duracaoNumerica(d.getIniciadaEm(), d.getFinalizadaEm()),
                    zebra, false);
            texto(r, e, 2, d.getTemperatura().toPlainString(), zebra, false);
            data(r, e, 3, d.getIniciadaEm(), zebra, false);
            data(r, e, 4, d.getFinalizadaEm(), zebra, false);
            texto(r, e, 5, nome(d.getAplicadaPor()), zebra, false);
            zebra = !zebra;
        }
        // A aba existe mesmo vazia: uma aba em falta faria duvidar se o
        // relatório saiu completo, em vez de dizer que não houve forno.
        if (desidros.isEmpty()) {
            Row r = aba.createRow(linha[0]++);
            texto(r, e, 0, "Nenhuma desidrogenização aplicada a esta OS.", false, false);
        }
        ajustar(aba, 6);
    }

    /**
     * Lista plana, com o UUID: é a aba de quem vai filtrar, pivotar ou rastrear.
     *
     * Traz os passos próprios e os acoplados na mesma tabela, separados pela
     * última coluna — é o que permite filtrar por um ou por outro. A coluna vai
     * no FIM porque tudo aqui se lê por índice.
     */
    private void abaDados(Workbook wb, EstilosPlanilha e, List<Log> logs, List<Log> acopladas) {
        Sheet aba = wb.createSheet("Dados");
        int[] linha = {0};
        cabecalhoTabela(aba, e, linha, "ID", "Carga", "Tipo da carga", "Posição da carga",
                "Processo", "Etapa", "Responsável", "Iniciado em", "Finalizado em",
                "Duração", "Situação", "Acoplada à OS");
        aba.createFreezePane(0, 1);

        boolean zebra = false;
        for (Log log : logs) {
            escreverDado(aba.createRow(linha[0]++), e, log, null, zebra);
            zebra = !zebra;
        }
        for (Log log : acopladas) {
            escreverDado(aba.createRow(linha[0]++), e, log, log.getOrdemServico(), zebra);
            zebra = !zebra;
        }
        // Tabela contínua e sem subtotais: aqui o autofiltro não tem o que quebrar.
        aba.setAutoFilter(new CellRangeAddress(0, Math.max(linha[0] - 1, 0), 0, 11));
        ajustar(aba, 12);
    }

    /** `titular` nulo = passo próprio; preenchido = carga de outra OS. */
    private void escreverDado(Row r, EstilosPlanilha e, Log log, OrdemServico titular,
                              boolean zebra) {
        texto(r, e, 0, log.getId().toString(), zebra, false);
        texto(r, e, 1, log.getCarga().getNome(), zebra, false);
        texto(r, e, 2, log.getCarga().getTipo().name(), zebra, false);
        texto(r, e, 3, log.getCarga().getPosicao().name(), zebra, false);
        texto(r, e, 4, log.getProcesso().getDescricao(), zebra, false);
        texto(r, e, 5, log.getProcesso().getEtapa().name(), zebra, false);
        texto(r, e, 6, log.getResponsavel().getNome(), zebra, false);
        data(r, e, 7, log.getIniciadoEm(), zebra, false);
        data(r, e, 8, log.getFinalizadoEm(), zebra, false);
        duracao(r, e, 9,
                DataHoraBr.duracaoNumerica(log.getIniciadoEm(), log.getFinalizadoEm()),
                zebra, false);
        texto(r, e, 10, situacaoDaEtapa(log), zebra, false);
        // Vazia no passo próprio: é o valor que o filtro usa para separar os dois.
        texto(r, e, 11, titular == null ? null : String.valueOf(titular.getId()), zebra, false);
    }

    // --------------------------------------------------------------- escrita

    private void parTexto(Sheet aba, EstilosPlanilha e, int linha, String rot1, String val1,
                          String rot2, String val2) {
        Row r = aba.createRow(linha);
        rotulo(r, e, 0, rot1);
        valorTexto(r, e, 1, val1);
        rotulo(r, e, 3, rot2);
        valorTexto(r, e, 4, val2);
        mesclarValores(aba, linha);
    }

    /** Os valores da identificação ocupam B:C e E:G. */
    private void mesclarValores(Sheet aba, int linha) {
        for (int coluna = 2; coluna < COLUNAS; coluna++) {
            if (aba.getRow(linha).getCell(coluna) == null) {
                aba.getRow(linha).createCell(coluna);
            }
        }
        aba.addMergedRegion(new CellRangeAddress(linha, linha, 1, 2));
        aba.addMergedRegion(new CellRangeAddress(linha, linha, 4, COLUNAS - 1));
    }

    // ----------------------------------------------------------------- apoio

    /**
     * O prefixo distingue os dois tipos de bloco à vista, sem depender de cor.
     * Um bloco acoplado NÃO começa com "CARGA: " de propósito: é o que separa,
     * na leitura e nos testes, o que a OS executou do que ela pegou carona.
     */
    private String descricaoDaCarga(Carga carga, OrdemServico titular) {
        if (titular != null) {
            return "ETAPA ACOPLADA — OS " + titular.getId()
                    + " · carga " + carga.getNome()
                    + " — " + carga.getTipo().name() + " / " + carga.getPosicao().name();
        }
        StringBuilder sb = new StringBuilder("CARGA: ").append(carga.getNome())
                .append(" — ").append(carga.getTipo().name())
                .append(" / ").append(carga.getPosicao().name());
        if (carga.getTagId() != null && !carga.getTagId().isEmpty()) {
            sb.append(" — tag ").append(carga.getTagId());
        }
        return sb.toString();
    }

}
