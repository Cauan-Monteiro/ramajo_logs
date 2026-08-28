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
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
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
 *   Relatório - para ler e imprimir: identificação, indicadores e as etapas
 *               agrupadas por carga, cada bloco com subtotal e retrátil (+/-).
 *   Lotes     - o fechamento de lote a lote.
 *   Dados     - a mesma coisa em lista plana, com autofiltro, para quem quiser
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

    @Transactional(readOnly = true)
    public byte[] gerar(Long osId) {
        OrdemServico os = osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));

        List<Lote> lotes = loteRepo.buscarParaRelatorio(osId);
        List<Log> logs = logRepo.buscarParaRelatorio(osId);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream saida = new ByteArrayOutputStream()) {

            EstilosPlanilha estilos = new EstilosPlanilha(wb);
            abaRelatorio(wb, estilos, os, lotes, logs);
            abaLotes(wb, estilos, lotes);
            abaDados(wb, estilos, logs);

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
                              List<Lote> lotes, List<Log> logs) {
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
        identificacao(aba, e, linha, os);
        indicadores(aba, e, linha, lotes, logs, porCarga.size());

        for (List<Log> daCarga : porCarga.values()) {
            blocoDaCarga(aba, e, linha, daCarga);
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

    private void identificacao(Sheet aba, EstilosPlanilha e, int[] linha, OrdemServico os) {
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

        linha[0] += 6;
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

    private void blocoDaCarga(Sheet aba, EstilosPlanilha e, int[] linha, List<Log> daCarga) {
        Carga carga = daCarga.get(0).getCarga();

        Row rTitulo = aba.createRow(linha[0]);
        rTitulo.setHeightInPoints(20);
        for (int i = 0; i < COLUNAS; i++) {
            rTitulo.createCell(i).setCellStyle(e.subtituloCarga);
        }
        rTitulo.getCell(0).setCellValue(descricaoDaCarga(carga));
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

        subtotal(aba, e, linha, primeira, ultima);
        aba.groupRow(primeira, ultima);
        linha[0]++; // respiro entre blocos
    }

    private void subtotal(Sheet aba, EstilosPlanilha e, int[] linha, int primeira, int ultima) {
        Row r = aba.createRow(linha[0]++);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(i == COL_DURACAO ? e.duracaoSubtotal : e.rotuloSubtotal);
        }
        r.getCell(0).setCellValue("Subtotal da carga");
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

    /** Lista plana, com o UUID: é a aba de quem vai filtrar, pivotar ou rastrear. */
    private void abaDados(Workbook wb, EstilosPlanilha e, List<Log> logs) {
        Sheet aba = wb.createSheet("Dados");
        int[] linha = {0};
        cabecalhoTabela(aba, e, linha, "ID", "Carga", "Tipo da carga", "Posição da carga",
                "Processo", "Etapa", "Responsável", "Iniciado em", "Finalizado em",
                "Duração", "Situação");
        aba.createFreezePane(0, 1);

        boolean zebra = false;
        for (Log log : logs) {
            Row r = aba.createRow(linha[0]++);
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
            zebra = !zebra;
        }
        // Tabela contínua e sem subtotais: aqui o autofiltro não tem o que quebrar.
        aba.setAutoFilter(new CellRangeAddress(0, Math.max(linha[0] - 1, 0), 0, 10));
        ajustar(aba, 11);
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

    private String descricaoDaCarga(Carga carga) {
        StringBuilder sb = new StringBuilder("CARGA: ").append(carga.getNome())
                .append(" — ").append(carga.getTipo().name())
                .append(" / ").append(carga.getPosicao().name());
        if (carga.getTagId() != null && !carga.getTagId().isEmpty()) {
            sb.append(" — tag ").append(carga.getTagId());
        }
        return sb.toString();
    }

}
