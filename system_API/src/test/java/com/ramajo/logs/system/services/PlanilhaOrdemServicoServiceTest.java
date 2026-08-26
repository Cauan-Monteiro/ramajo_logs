package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A montagem da planilha só falha em runtime (regiões mescladas sobrepostas,
 * fórmula inválida, agrupamento de linhas fora de faixa), então o teste gera o
 * arquivo de verdade e o relê com o POI.
 */
@ExtendWith(MockitoExtension.class)
class PlanilhaOrdemServicoServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    @Mock private OrdemServicoRepository osRepo;
    @Mock private LogRepository logRepo;
    @Mock private LoteRepository loteRepo;

    @InjectMocks private PlanilhaOrdemServicoService service;

    @Test
    void geraAsTresAbasComSubtotalSomavel() throws Exception {
        OrdemServico os = ordem();
        Carga tambor = carga(10L, "TAMBOR-01", TipoCarga.TAMBOR);
        Carga cesto = carga(20L, "CESTO-04", TipoCarga.CESTO);
        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");

        List<Log> logs = List.of(
                log(os, joao, tambor, "Desengraxe", Etapa.PRE_TRATAMENTO, 0, 15, false),
                log(os, joao, tambor, "Banho ácido", Etapa.TRATAMENTO, 15, 62, false),
                // cancelada: fica visível, mas fora do subtotal
                log(os, joao, tambor, "Enxágue", Etapa.POS_TRATAMENTO, 62, 70, true),
                // em andamento: sem fim, sem duração
                log(os, joao, cesto, "Desengraxe", Etapa.PRE_TRATAMENTO, 3, -1, false));

        when(osRepo.findById(42L)).thenReturn(Optional.of(os));
        when(logRepo.buscarParaRelatorio(42L)).thenReturn(logs);
        when(loteRepo.buscarParaRelatorio(42L)).thenReturn(List.of(lote(os, (short) 1, joao)));

        byte[] bytes = service.gerar(42L);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetName(0)).isEqualTo("Relatório");
            assertThat(wb.getSheetName(1)).isEqualTo("Lotes");
            assertThat(wb.getSheetName(2)).isEqualTo("Dados");

            XSSFSheet relatorio = wb.getSheetAt(0);

            // dois blocos, na ordem cronológica de entrada (tambor entrou antes)
            List<String> subtitulos = subtitulos(relatorio);
            assertThat(subtitulos).hasSize(2);
            assertThat(subtitulos.get(0)).startsWith("CARGA: TAMBOR-01");
            assertThat(subtitulos.get(1)).startsWith("CARGA: CESTO-04");

            // o subtotal é fórmula, e a faixa somada exclui a linha cancelada
            Cell subtotal = primeiroSubtotal(relatorio);
            assertThat(subtotal.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(subtotal.getCellFormula()).matches("SUM\\(F\\d+:F\\d+\\)");

            // etapa cancelada e etapa em andamento não escrevem duração
            assertThat(celulasDeDuracaoPreenchidas(relatorio)).isEqualTo(2);

            // o logo do classpath entra como figura ancorada no topo
            assertThat(relatorio.getDrawingPatriarch().getShapes()).hasSize(1);

            // autofiltro só na aba plana
            assertThat(relatorio.getCTWorksheet().isSetAutoFilter()).isFalse();
            assertThat(wb.getSheetAt(2).getCTWorksheet().isSetAutoFilter()).isTrue();
        }
    }

    // ------------------------------------------------------------------ apoio



    private List<String> subtitulos(Sheet aba) {
        return java.util.stream.StreamSupport.stream(aba.spliterator(), false)
                .map(r -> r.getCell(0))
                .filter(c -> c != null && c.getCellType() == CellType.STRING)
                .map(Cell::getStringCellValue)
                .filter(v -> v.startsWith("CARGA: "))
                .toList();
    }

    private Cell primeiroSubtotal(Sheet aba) {
        for (Row r : aba) {
            Cell c = r.getCell(5);
            if (c != null && c.getCellType() == CellType.FORMULA) {
                return c;
            }
        }
        throw new AssertionError("nenhum subtotal encontrado");
    }

    private long celulasDeDuracaoPreenchidas(Sheet aba) {
        long total = 0;
        for (Row r : aba) {
            Cell c = r.getCell(5);
            if (c != null && c.getCellType() == CellType.NUMERIC && r.getCell(6) != null
                    && r.getCell(6).getCellType() == CellType.STRING) {
                total++;
            }
        }
        return total;
    }

    private OrdemServico ordem() throws Exception {
        OrdemServico os = new OrdemServico(99123L, new Cliente(1L, "ACME LTDA"), Posicao.OXIDACAO);
        set(os, "id", 42L);
        set(os, "iniciadaEm", T0);
        os.setFinalizadaEm(T0.plus(8, ChronoUnit.HOURS));
        return os;
    }

    private Carga carga(Long id, String nome, TipoCarga tipo) throws Exception {
        Carga c = new Carga(nome, tipo, Posicao.OXIDACAO);
        set(c, "id", id);
        c.setTagId("TAG-" + id);
        return c;
    }

    /** minutoInicio/minutoFim relativos a T0; fim negativo = etapa em andamento. */
    private Log log(OrdemServico os, Operador op, Carga carga, String processo,
                    Etapa etapa, int minutoInicio, int minutoFim, boolean cancelado)
            throws Exception {
        Log l = new Log(os, op, carga, new Processo(processo, etapa));
        set(l, "id", UUID.randomUUID());
        set(l, "iniciadoEm", T0.plus(minutoInicio, ChronoUnit.MINUTES));
        if (minutoFim >= 0) {
            l.setFinalizadoEm(T0.plus(minutoFim, ChronoUnit.MINUTES));
        }
        l.setCancelado(cancelado);
        return l;
    }

    private Lote lote(OrdemServico os, short numero, Operador op) throws Exception {
        Lote lote = new Lote(os, numero);
        set(lote, "iniciadoEm", T0);
        set(lote, "finalizadoEm", T0.plus(3, ChronoUnit.HOURS));
        set(lote, "finalizadoPor", op);
        return lote;
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
