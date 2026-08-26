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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exportação de UMA Ordem de Serviço para .xlsx.
 *
 * O workbook é montado aqui dentro, com a transação aberta, para não depender
 * do open-in-view: quando o controller devolve os bytes já não há mais sessão.
 *
 * Todo horário vira uma célula de DATA de verdade (valor numérico + formato
 * dd/mm/yyyy hh:mm:ss), não texto — assim o Excel ordena, filtra e subtrai.
 */
@Service
@RequiredArgsConstructor
public class PlanilhaOrdemServicoService {

    private static final String FORMATO_DATA = "dd/mm/yyyy hh:mm:ss";

    private final OrdemServicoRepository osRepo;
    private final LogRepository logRepo;
    private final LoteRepository loteRepo;

    @Transactional(readOnly = true)
    public byte[] gerar(Long osId) {
        OrdemServico os = osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));

        List<Lote> lotes = loteRepo.buscarParaRelatorio(osId);
        List<Log> logs = logRepo.buscarParaRelatorio(osId);
        List<Carga> cargas = os.getCargas();

        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream saida = new ByteArrayOutputStream()) {

            Estilos estilos = new Estilos(wb);
            abaOrdem(wb, estilos, os, lotes, logs, cargas);
            abaLotes(wb, estilos, lotes);
            abaEtapas(wb, estilos, logs);
            abaCargas(wb, estilos, cargas);

            wb.write(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream não faz I/O de verdade; se estourar aqui é
            // falha de montagem do arquivo, não erro de domínio.
            throw new UncheckedIOException("Falha ao gerar a planilha da OS " + osId, e);
        }
    }

    // ------------------------------------------------------------------ abas

    private void abaOrdem(Workbook wb, Estilos e, OrdemServico os, List<Lote> lotes,
                          List<Log> logs, List<Carga> cargas) {
        Sheet aba = wb.createSheet("Ordem de Serviço");
        int[] linha = {0};

        cabecalho(aba, e, linha, "Campo", "Valor");

        texto(aba, e, linha, "ID", String.valueOf(os.getId()));
        texto(aba, e, linha, "ID externo",
                os.getIdExterno() == null ? "" : String.valueOf(os.getIdExterno()));
        texto(aba, e, linha, "Cliente", os.getCliente().getNome());
        texto(aba, e, linha, "Posição", os.getPosicao().name());
        texto(aba, e, linha, "Situação", situacaoDaOrdem(os));
        data(aba, e, linha, "Iniciada em", os.getIniciadaEm());
        data(aba, e, linha, "Finalizada em", os.getFinalizadaEm());
        texto(aba, e, linha, "Duração total",
                DataHoraBr.duracao(os.getIniciadaEm(), os.getFinalizadaEm()));
        texto(aba, e, linha, "Iniciada por", nome(os.getIniciadaPor()));
        texto(aba, e, linha, "Finalizada por", nome(os.getFinalizadaPor()));
        texto(aba, e, linha, "Total de lotes", String.valueOf(lotes.size()));
        texto(aba, e, linha, "Lotes finalizados",
                String.valueOf(lotes.stream().filter(Lote::isFinalizado).count()));
        texto(aba, e, linha, "Total de etapas", String.valueOf(logs.size()));
        texto(aba, e, linha, "Etapas em aberto", String.valueOf(logs.stream()
                .filter(l -> l.getFinalizadoEm() == null && !l.isCancelado()).count()));
        texto(aba, e, linha, "Cargas vinculadas",
                cargas.stream().map(Carga::getNome).collect(Collectors.joining(", ")));

        ajustar(aba, 2);
    }

    private void abaLotes(Workbook wb, Estilos e, List<Lote> lotes) {
        Sheet aba = wb.createSheet("Lotes");
        int[] linha = {0};
        cabecalho(aba, e, linha, "Numero", "Iniciado em", "Finalizado em", "Duração",
                "Finalizado por", "Situação");

        for (Lote lote : lotes) {
            Row r = aba.createRow(linha[0]++);
            int c = 0;
            celulaTexto(r, c++, String.valueOf(lote.getNumero()));
            celulaData(r, c++, e, lote.getIniciadoEm());
            celulaData(r, c++, e, lote.getFinalizadoEm());
            celulaTexto(r, c++, DataHoraBr.duracao(lote.getIniciadoEm(), lote.getFinalizadoEm()));
            celulaTexto(r, c++, nome(lote.getFinalizadoPor()));
            celulaTexto(r, c, lote.isFinalizado() ? "Finalizado" : "Aberto");
        }
        ajustar(aba, 6);
    }

    private void abaEtapas(Workbook wb, Estilos e, List<Log> logs) {
        Sheet aba = wb.createSheet("Etapas");
        int[] linha = {0};
        cabecalho(aba, e, linha, "ID", "Carga", "Tipo da carga", "Processo", "Etapa",
                "Responsável", "Iniciado em", "Finalizado em", "Duração", "Situação");

        for (Log log : logs) {
            Row r = aba.createRow(linha[0]++);
            int c = 0;
            celulaTexto(r, c++, log.getId().toString());
            celulaTexto(r, c++, log.getCarga().getNome());
            celulaTexto(r, c++, log.getCarga().getTipo().name());
            celulaTexto(r, c++, log.getProcesso().getDescricao());
            celulaTexto(r, c++, log.getProcesso().getEtapa().name());
            celulaTexto(r, c++, log.getResponsavel().getNome());
            celulaData(r, c++, e, log.getIniciadoEm());
            celulaData(r, c++, e, log.getFinalizadoEm());
            celulaTexto(r, c++, DataHoraBr.duracao(log.getIniciadoEm(), log.getFinalizadoEm()));
            celulaTexto(r, c, situacaoDaEtapa(log));
        }
        ajustar(aba, 10);
    }

    private void abaCargas(Workbook wb, Estilos e, List<Carga> cargas) {
        Sheet aba = wb.createSheet("Cargas");
        int[] linha = {0};
        cabecalho(aba, e, linha, "Nome", "Tipo", "Posição", "Tag", "Ativa");

        for (Carga carga : cargas) {
            Row r = aba.createRow(linha[0]++);
            int c = 0;
            celulaTexto(r, c++, carga.getNome());
            celulaTexto(r, c++, carga.getTipo().name());
            celulaTexto(r, c++, carga.getPosicao().name());
            celulaTexto(r, c++, carga.getTagId());
            celulaTexto(r, c, carga.isAtivo() ? "Sim" : "Não");
        }
        ajustar(aba, 5);
    }

    // --------------------------------------------------------------- escrita

    private void cabecalho(Sheet aba, Estilos e, int[] linha, String... titulos) {
        Row r = aba.createRow(linha[0]++);
        for (int i = 0; i < titulos.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(titulos[i]);
            c.setCellStyle(e.cabecalho);
        }
        aba.createFreezePane(0, 1);
    }

    /** Linha rótulo/valor da primeira aba. */
    private void texto(Sheet aba, Estilos e, int[] linha, String rotulo, String valor) {
        Row r = aba.createRow(linha[0]++);
        Cell c = r.createCell(0);
        c.setCellValue(rotulo);
        c.setCellStyle(e.rotulo);
        celulaTexto(r, 1, valor);
    }

    private void data(Sheet aba, Estilos e, int[] linha, String rotulo, Instant valor) {
        Row r = aba.createRow(linha[0]++);
        Cell c = r.createCell(0);
        c.setCellValue(rotulo);
        c.setCellStyle(e.rotulo);
        celulaData(r, 1, e, valor);
    }

    // Valor ausente vira célula VAZIA (não "null", não "-"): o filtro do Excel
    // depende disso para separar "sem valor" de texto qualquer.
    private void celulaTexto(Row r, int coluna, String valor) {
        if (valor == null || valor.isEmpty()) {
            return;
        }
        r.createCell(coluna).setCellValue(valor);
    }

    private void celulaData(Row r, int coluna, Estilos e, Instant valor) {
        LocalDateTime local = DataHoraBr.local(valor);
        if (local == null) {
            return;
        }
        Cell c = r.createCell(coluna);
        c.setCellValue(local);
        c.setCellStyle(e.dataHora);
    }

    private void ajustar(Sheet aba, int colunas) {
        for (int i = 0; i < colunas; i++) {
            aba.autoSizeColumn(i);
        }
    }

    // ----------------------------------------------------------------- apoio

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
     * células — nunca dentro do laço.
     */
    private static final class Estilos {
        private final CellStyle cabecalho;
        private final CellStyle rotulo;
        private final CellStyle dataHora;

        private Estilos(Workbook wb) {
            Font negrito = wb.createFont();
            negrito.setBold(true);

            cabecalho = wb.createCellStyle();
            cabecalho.setFont(negrito);
            cabecalho.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            cabecalho.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cabecalho.setBorderBottom(BorderStyle.THIN);

            rotulo = wb.createCellStyle();
            rotulo.setFont(negrito);

            dataHora = wb.createCellStyle();
            dataHora.setDataFormat(wb.createDataFormat().getFormat(FORMATO_DATA));
        }
    }
}
