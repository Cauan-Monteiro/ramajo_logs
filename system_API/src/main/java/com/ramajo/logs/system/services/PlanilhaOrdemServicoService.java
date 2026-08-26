package com.ramajo.logs.system.services;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.util.DataHoraBr;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
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
 * Duas escolhas de tipagem sustentam tudo isso:
 *   - horário vira célula de DATA de verdade (valor numérico + formato
 *     dd/mm/yyyy hh:mm:ss), não texto — o Excel ordena, filtra e subtrai;
 *   - duração vira fração de dia com formato [h]:mm:ss — some, tira média, e
 *     um total acima de 24h aparece como 32:15:00 em vez de voltar ao zero.
 */
@Service
@RequiredArgsConstructor
public class PlanilhaOrdemServicoService {

    private static final String FORMATO_DATA = "dd/mm/yyyy hh:mm:ss";
    /** Os colchetes é que impedem as horas de estourarem em 24. */
    private static final String FORMATO_DURACAO = "[h]:mm:ss";

    private static final String CAMINHO_LOGO = "/relatorio/logo-ramajo.png";

    /** Colunas da aba Relatório: Processo, Etapa, Responsável, Início, Fim, Duração, Situação. */
    private static final int COLUNAS = 7;
    private static final int COL_DURACAO = 5;
    private static final int[] LARGURAS = {32, 18, 24, 21, 21, 12, 15};

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

            Estilos estilos = new Estilos(wb);
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

    private void abaRelatorio(XSSFWorkbook wb, Estilos e, OrdemServico os,
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
            porCarga.computeIfAbsent(log.getCarga().getId(), id -> new java.util.ArrayList<>())
                    .add(log);
        }
        return porCarga;
    }

    private void titulo(XSSFWorkbook wb, Sheet aba, Estilos e, int[] linha) {
        Row r = aba.createRow(linha[0]);
        r.setHeightInPoints(48);
        for (int i = 0; i < COLUNAS; i++) {
            r.createCell(i).setCellStyle(e.titulo);
        }
        // A coluna A fica reservada ao logo; o texto começa em B para os dois
        // não disputarem a mesma célula.
        r.getCell(1).setCellValue("RELATÓRIO DE ORDEM DE SERVIÇO");
        aba.addMergedRegion(new CellRangeAddress(linha[0], linha[0], 1, COLUNAS - 1));

        inserirLogo(wb, aba, linha[0]);
        linha[0] += 2;
    }

    private void identificacao(Sheet aba, Estilos e, int[] linha, OrdemServico os) {
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

    private void indicadores(Sheet aba, Estilos e, int[] linha, List<Lote> lotes,
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

    private void blocoDaCarga(Sheet aba, Estilos e, int[] linha, List<Log> daCarga) {
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

    private void subtotal(Sheet aba, Estilos e, int[] linha, int primeira, int ultima) {
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

    private void rodape(Sheet aba, Estilos e, int[] linha, OrdemServico os) {
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

    private void abaLotes(Workbook wb, Estilos e, List<Lote> lotes) {
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
    private void abaDados(Workbook wb, Estilos e, List<Log> logs) {
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

    private void cabecalhoTabela(Sheet aba, Estilos e, int[] linha, String... titulos) {
        Row r = aba.createRow(linha[0]++);
        for (int i = 0; i < titulos.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(titulos[i]);
            c.setCellStyle(e.cabecalhoTabela);
        }
    }

    private void parTexto(Sheet aba, Estilos e, int linha, String rot1, String val1,
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

    private void rotulo(Row r, Estilos e, int coluna, String valor) {
        Cell c = r.createCell(coluna);
        c.setCellValue(valor);
        c.setCellStyle(e.rotuloResumo);
    }

    private void valorTexto(Row r, Estilos e, int coluna, String valor) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.valorResumo);
        if (valor != null && !valor.isEmpty()) {
            c.setCellValue(valor);
        }
    }

    private void valorData(Row r, Estilos e, int coluna, Instant valor) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.valorResumoData);
        LocalDateTime local = DataHoraBr.local(valor);
        if (local != null) {
            c.setCellValue(local);
        }
    }

    private void valorDuracao(Row r, Estilos e, int coluna, Double valor) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.valorResumoDuracao);
        if (valor != null) {
            c.setCellValue(valor);
        }
    }

    // Valor ausente vira célula VAZIA (não "null", não "-"): o filtro do Excel
    // depende disso para separar "sem valor" de texto qualquer, e o SOMA dos
    // subtotais depende para não contar etapa em aberto como zero. A célula em
    // si é criada mesmo assim, senão ela perde borda e zebra.
    private void texto(Row r, Estilos e, int coluna, String valor, boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.texto(zebra, cancelada));
        if (valor != null && !valor.isEmpty()) {
            c.setCellValue(valor);
        }
    }

    private void data(Row r, Estilos e, int coluna, Instant valor, boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.data(zebra, cancelada));
        LocalDateTime local = DataHoraBr.local(valor);
        if (local != null) {
            c.setCellValue(local);
        }
    }

    private void duracao(Row r, Estilos e, int coluna, Double valor, boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.duracao(zebra, cancelada));
        if (valor != null) {
            c.setCellValue(valor);
        }
    }

    private void ajustar(Sheet aba, int colunas) {
        for (int i = 0; i < colunas; i++) {
            aba.autoSizeColumn(i);
        }
    }

    /** Paisagem e ajustada à largura da folha — o relatório costuma ser impresso. */
    private void imprimivel(Sheet aba) {
        aba.setFitToPage(true);
        PrintSetup impressao = aba.getPrintSetup();
        impressao.setLandscape(true);
        impressao.setFitWidth((short) 1);
        impressao.setFitHeight((short) 0);
    }

    /**
     * O logo é opcional de propósito: se o PNG não estiver no classpath (ou não
     * for legível), o relatório sai só com o título em vez de derrubar a rota.
     */
    private void inserirLogo(XSSFWorkbook wb, Sheet aba, int linha) {
        try (InputStream entrada = getClass().getResourceAsStream(CAMINHO_LOGO)) {
            if (entrada == null) {
                return;
            }
            byte[] bytes = entrada.readAllBytes();
            BufferedImage imagem = ImageIO.read(new ByteArrayInputStream(bytes));
            if (imagem == null || imagem.getWidth() == 0 || imagem.getHeight() == 0) {
                return;
            }
            int tipo = tipoDaImagem(bytes);
            if (tipo == 0) {
                return;
            }
            int indice = wb.addPicture(bytes, tipo);
            Drawing<?> desenho = aba.createDrawingPatriarch();
            ClientAnchor ancora = wb.getCreationHelper().createClientAnchor();
            ancora.setCol1(0);
            ancora.setRow1(linha);
            Picture figura = desenho.createPicture(ancora, indice);
            // resize(escala) parte do tamanho nativo em pixels; a escala é a que
            // couber na célula A1 (largura da coluna x altura da linha do título).
            double alvoLargura = LARGURAS[0] * 7.0;
            double alvoAltura = 48 * 96.0 / 72.0;
            figura.resize(Math.min(alvoLargura / imagem.getWidth(),
                    alvoAltura / imagem.getHeight()));
        } catch (IOException | RuntimeException ignorado) {
            // relatório sem logo continua sendo um relatório válido
        }
    }

    /**
     * O formato sai dos bytes, não da extensão: um JPEG salvo como .png é comum
     * e o Excel só desenha a figura se o tipo declarado bater com o conteúdo.
     * Formato desconhecido devolve 0 e o logo é omitido.
     */
    private int tipoDaImagem(byte[] bytes) {
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return Workbook.PICTURE_TYPE_PNG;
        }
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        return 0;
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

    private String situacaoDaOrdem(OrdemServico os) {
        if (os.isCancelada()) {
            return "Cancelada";
        }
        return os.isFinalizada() ? "Finalizada" : "Em processo";
    }

    private String situacaoDaEtapa(Log log) {
        if (log.isCancelado()) {
            return "Cancelada";
        }
        return log.getFinalizadoEm() == null ? "Em andamento" : "Concluída";
    }

    private String nome(Operador operador) {
        return operador == null ? "" : operador.getNome();
    }

    /**
     * CellStyle é um recurso do workbook (o formato tem limite de ~64k por
     * arquivo), então cada estilo nasce uma vez e é reaproveitado em todas as
     * células — nunca dentro do laço. As variantes de linha (zebra, cancelada)
     * são resolvidas pelos métodos texto/data/duracao, para não espalhar `if`
     * de estilo pelo código de escrita.
     */
    private static final class Estilos {

        private static final byte[] AZUL_ESCURO = {0x1F, 0x4E, 0x79};
        private static final byte[] AZUL_MEDIO = {0x2E, 0x75, (byte) 0xB6};
        private static final byte[] AZUL_CLARO = {(byte) 0xEA, (byte) 0xF1, (byte) 0xF8};
        private static final byte[] AZUL_BORDA = {(byte) 0xB4, (byte) 0xC6, (byte) 0xE7};
        private static final byte[] VERMELHO_CLARO = {(byte) 0xFD, (byte) 0xEA, (byte) 0xEA};

        private final CellStyleTrio normal;
        private final CellStyleTrio cancelado;

        private final XSSFCellStyle titulo;
        private final XSSFCellStyle rotuloResumo;
        private final XSSFCellStyle valorResumo;
        private final XSSFCellStyle valorResumoData;
        private final XSSFCellStyle valorResumoDuracao;
        private final XSSFCellStyle indicadorLegenda;
        private final XSSFCellStyle indicadorValor;
        private final XSSFCellStyle subtituloCarga;
        private final XSSFCellStyle cabecalhoTabela;
        private final XSSFCellStyle rotuloSubtotal;
        private final XSSFCellStyle duracaoSubtotal;
        private final XSSFCellStyle rodape;
        private final XSSFCellStyle rodapeData;

        private Estilos(XSSFWorkbook wb) {
            XSSFColor escuro = cor(AZUL_ESCURO);
            XSSFColor medio = cor(AZUL_MEDIO);
            XSSFColor claro = cor(AZUL_CLARO);
            XSSFColor cancelada = cor(VERMELHO_CLARO);

            XSSFFont fBranca = fonte(wb, 11, true, null);
            fBranca.setColor(cor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
            XSSFFont fTitulo = fonte(wb, 16, true, null);
            fTitulo.setColor(cor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
            XSSFFont fRotulo = fonte(wb, 11, true, escuro);
            XSSFFont fNormal = fonte(wb, 11, false, null);
            XSSFFont fIndicador = fonte(wb, 16, true, escuro);
            XSSFFont fRiscada = fonte(wb, 11, false, null);
            fRiscada.setStrikeout(true);
            XSSFFont fRodape = fonte(wb, 9, false, null);
            fRodape.setItalic(true);

            titulo = criar(wb, fTitulo, escuro, null, false, HorizontalAlignment.LEFT);
            titulo.setVerticalAlignment(VerticalAlignment.CENTER);

            rotuloResumo = criar(wb, fRotulo, null, null, false, HorizontalAlignment.LEFT);
            valorResumo = criar(wb, fNormal, null, null, false, HorizontalAlignment.LEFT);
            valorResumoData = criar(wb, fNormal, null, FORMATO_DATA, false, HorizontalAlignment.LEFT);
            valorResumoDuracao = criar(wb, fNormal, null, FORMATO_DURACAO, false, HorizontalAlignment.LEFT);

            indicadorLegenda = criar(wb, fBranca, medio, null, true, HorizontalAlignment.CENTER);
            indicadorValor = criar(wb, fIndicador, claro, null, true, HorizontalAlignment.CENTER);
            indicadorValor.setVerticalAlignment(VerticalAlignment.CENTER);

            subtituloCarga = criar(wb, fBranca, medio, null, false, HorizontalAlignment.LEFT);
            subtituloCarga.setVerticalAlignment(VerticalAlignment.CENTER);

            cabecalhoTabela = criar(wb, fBranca, escuro, null, true, HorizontalAlignment.CENTER);

            rotuloSubtotal = criar(wb, fRotulo, null, null, true, HorizontalAlignment.RIGHT);
            duracaoSubtotal = criar(wb, fRotulo, null, FORMATO_DURACAO, true, HorizontalAlignment.CENTER);

            rodape = criar(wb, fRodape, null, null, false, HorizontalAlignment.LEFT);
            rodapeData = criar(wb, fRodape, null, FORMATO_DATA, false, HorizontalAlignment.LEFT);

            normal = new CellStyleTrio(
                    criar(wb, fNormal, null, null, true, HorizontalAlignment.LEFT),
                    criar(wb, fNormal, claro, null, true, HorizontalAlignment.LEFT),
                    criar(wb, fNormal, null, FORMATO_DATA, true, HorizontalAlignment.CENTER),
                    criar(wb, fNormal, claro, FORMATO_DATA, true, HorizontalAlignment.CENTER),
                    criar(wb, fNormal, null, FORMATO_DURACAO, true, HorizontalAlignment.CENTER),
                    criar(wb, fNormal, claro, FORMATO_DURACAO, true, HorizontalAlignment.CENTER));

            // Cancelada ignora a zebra: o fundo avermelhado já é o sinal, e
            // alternar por cima dele só embaralharia a leitura.
            XSSFCellStyle cTexto = criar(wb, fRiscada, cancelada, null, true, HorizontalAlignment.LEFT);
            XSSFCellStyle cData = criar(wb, fRiscada, cancelada, FORMATO_DATA, true, HorizontalAlignment.CENTER);
            XSSFCellStyle cDuracao = criar(wb, fRiscada, cancelada, FORMATO_DURACAO, true, HorizontalAlignment.CENTER);
            cancelado = new CellStyleTrio(cTexto, cTexto, cData, cData, cDuracao, cDuracao);
        }

        private CellStyle texto(boolean zebra, boolean cancelada) {
            return (cancelada ? cancelado : normal).texto(zebra);
        }

        private CellStyle data(boolean zebra, boolean cancelada) {
            return (cancelada ? cancelado : normal).data(zebra);
        }

        private CellStyle duracao(boolean zebra, boolean cancelada) {
            return (cancelada ? cancelado : normal).duracao(zebra);
        }

        private static XSSFColor cor(byte[] rgb) {
            return new XSSFColor(rgb, null);
        }

        private static XSSFFont fonte(XSSFWorkbook wb, int tamanho, boolean negrito, XSSFColor cor) {
            XSSFFont f = wb.createFont();
            f.setFontHeightInPoints((short) tamanho);
            f.setBold(negrito);
            if (cor != null) {
                f.setColor(cor);
            }
            return f;
        }

        private static XSSFCellStyle criar(XSSFWorkbook wb, Font fonte, XSSFColor fundo,
                                           String formato, boolean bordas,
                                           HorizontalAlignment alinhamento) {
            XSSFCellStyle estilo = wb.createCellStyle();
            estilo.setFont(fonte);
            estilo.setAlignment(alinhamento);
            if (fundo != null) {
                estilo.setFillForegroundColor(fundo);
                estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (formato != null) {
                estilo.setDataFormat(wb.createDataFormat().getFormat(formato));
            }
            if (bordas) {
                XSSFColor linha = cor(AZUL_BORDA);
                estilo.setBorderTop(BorderStyle.THIN);
                estilo.setBorderBottom(BorderStyle.THIN);
                estilo.setBorderLeft(BorderStyle.THIN);
                estilo.setBorderRight(BorderStyle.THIN);
                estilo.setTopBorderColor(linha);
                estilo.setBottomBorderColor(linha);
                estilo.setLeftBorderColor(linha);
                estilo.setRightBorderColor(linha);
            }
            return estilo;
        }

    }

    /** As três formas de célula de dado (texto, data, duração) x zebra. */
    private record CellStyleTrio(XSSFCellStyle texto, XSSFCellStyle textoZebra,
                                 XSSFCellStyle data, XSSFCellStyle dataZebra,
                                 XSSFCellStyle duracao, XSSFCellStyle duracaoZebra) {

        private XSSFCellStyle texto(boolean zebra) {
            return zebra ? textoZebra : texto;
        }

        private XSSFCellStyle data(boolean zebra) {
            return zebra ? dataZebra : data;
        }

        private XSSFCellStyle duracao(boolean zebra) {
            return zebra ? duracaoZebra : duracao;
        }
    }
}
