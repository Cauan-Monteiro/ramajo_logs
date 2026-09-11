package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Desidrogenizacao;
import com.ramajo.logs.system.entities.ItemAvaliacao;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemAlteracao;
import com.ramajo.logs.system.entities.OrdemAvaliacao;
import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.enums.CampoAlterado;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OrdemAlteracaoRepository;
import com.ramajo.logs.system.repositories.OrdemAvaliacaoRepository;
import com.ramajo.logs.system.repositories.OrdemDesidrogenizacaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
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
    @Mock private OrdemDesidrogenizacaoRepository desidroRepo;
    // Sem stub nos testes que não tratam de correção: o mock devolve lista
    // vazia, que é o caso de quase toda OS.
    @Mock private OrdemAlteracaoRepository alteracaoRepo;
    // Idem: sem stub, Optional vazio — a OS expedida sem avaliar.
    @Mock private OrdemAvaliacaoRepository avaliacaoRepo;

    @InjectMocks private PlanilhaOrdemServicoService service;

    @Test
    void geraAsQuatroAbasComSubtotalSomavel() throws Exception {
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
        when(desidroRepo.buscarDaOrdem(42L)).thenReturn(List.of(desidro(os, joao, 120)));

        byte[] bytes = service.gerar(42L);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(6);
            assertThat(wb.getSheetName(0)).isEqualTo("Relatório");
            assertThat(wb.getSheetName(1)).isEqualTo("Lotes");
            assertThat(wb.getSheetName(2)).isEqualTo("Desidrogenizações");
            assertThat(wb.getSheetName(3)).isEqualTo("Avaliação");
            assertThat(wb.getSheetName(4)).isEqualTo("Dados");
            assertThat(wb.getSheetName(5)).isEqualTo("Alterações");

            // a aba do forno traz a aplicação, com a duração somável do Excel
            Sheet forno = wb.getSheetAt(2);
            assertThat(forno.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Têmpera");
            assertThat(forno.getRow(1).getCell(1).getNumericCellValue())
                    .isCloseTo(120 / 1440d, within(1e-9));

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
            assertThat(wb.getSheetAt(4).getCTWorksheet().isSetAutoFilter()).isTrue();
        }
    }

    /**
     * A OS carona: as peças dela rodaram na carga de outra ordem. A etapa tem
     * de aparecer no relatório dela — era esta a lacuna — mas cercada: bloco
     * próprio, subtotal com nome próprio, e fora dos indicadores, que continuam
     * medindo só o que ESTA OS executou.
     */
    @Test
    void etapaAcopladaGanhaBlocoESubtotalProprios() throws Exception {
        OrdemServico os = ordem();
        OrdemServico titular = ordemDe(99L, 77001L);
        Carga tambor = carga(10L, "TAMBOR-01", TipoCarga.TAMBOR);
        Carga alheia = carga(30L, "CESTO-09", TipoCarga.CESTO);
        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");

        when(osRepo.findById(42L)).thenReturn(Optional.of(os));
        when(logRepo.buscarParaRelatorio(42L)).thenReturn(List.of(
                log(os, joao, tambor, "Desengraxe", Etapa.PRE_TRATAMENTO, 0, 15, false)));
        // O passo é da OS 99; esta ordem só pegou carona nele.
        when(logRepo.buscarAcopladasParaRelatorio(42L)).thenReturn(List.of(
                log(titular, joao, alheia, "Banho ácido", Etapa.TRATAMENTO, 20, 50, false)));
        when(loteRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(desidroRepo.buscarDaOrdem(42L)).thenReturn(List.of());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.gerar(42L)))) {
            XSSFSheet relatorio = wb.getSheetAt(0);

            // O bloco próprio continua sendo um só: o acoplado NÃO se disfarça
            // de carga desta OS.
            assertThat(subtitulos(relatorio)).hasSize(1);
            assertThat(textos(relatorio, "ETAPA ACOPLADA")).hasSize(1);
            assertThat(textos(relatorio, "ETAPA ACOPLADA").get(0))
                    .contains("OS 99").contains("CESTO-09");

            // Dois subtotais, e o do acoplado diz para não somar no total da OS.
            assertThat(textos(relatorio, "Subtotal da carga")).hasSize(1);
            assertThat(textos(relatorio, "Subtotal acoplado")).hasSize(1);

            // A aba plana traz os dois passos, separados pela última coluna.
            Sheet dados = wb.getSheet("Dados");
            assertThat(dados.getRow(0).getCell(11).getStringCellValue()).isEqualTo("Acoplada à OS");
            assertThat(dados.getLastRowNum()).isEqualTo(2);
            assertThat(dados.getRow(1).getCell(11).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(dados.getRow(2).getCell(11).getStringCellValue()).isEqualTo("99");
        }
    }

    /**
     * A aba do forno existe mesmo sem forno nenhum: uma aba em falta faria
     * duvidar se o relatório saiu completo.
     */
    @Test
    void semDesidrogenizacaoAAbaDoFornoAindaAssimExiste() throws Exception {
        OrdemServico os = ordem();
        when(osRepo.findById(42L)).thenReturn(Optional.of(os));
        when(logRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(loteRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(desidroRepo.buscarDaOrdem(42L)).thenReturn(List.of());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.gerar(42L)))) {
            Sheet forno = wb.getSheet("Desidrogenizações");
            assertThat(forno).isNotNull();
            assertThat(forno.getRow(1).getCell(0).getStringCellValue())
                    .startsWith("Nenhuma desidrogenização");
        }
    }

    /**
     * A correção do ADMIN chega à planilha como foi gravada — texto de antes e
     * de depois, motivo e quem fez —; sem correção, a aba diz que não houve.
     */
    @Test
    void abaDeAlteracoesTrazAsCorrecoesOuDizQueNaoHouve() throws Exception {
        OrdemServico os = ordem();
        Operador admin = new Operador("Ana", Permissao.ADMIN, "A1");
        OrdemAlteracao numero = new OrdemAlteracao(os, CampoAlterado.ID_EXTERNO,
                "99123", "99124", "Nº digitado errado", admin);
        set(numero, "alteradaEm", T0);

        when(osRepo.findById(42L)).thenReturn(Optional.of(os));
        when(logRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(loteRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(desidroRepo.buscarDaOrdem(42L)).thenReturn(List.of());
        when(alteracaoRepo.buscarDaOrdem(42L)).thenReturn(List.of(numero));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.gerar(42L)))) {
            Sheet aba = wb.getSheet("Alterações");
            assertThat(aba.getRow(0).getCell(4).getStringCellValue()).isEqualTo("Motivo");
            assertThat(aba.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Nº da OS");
            assertThat(aba.getRow(1).getCell(2).getStringCellValue()).isEqualTo("99123");
            assertThat(aba.getRow(1).getCell(3).getStringCellValue()).isEqualTo("99124");
            assertThat(aba.getRow(1).getCell(4).getStringCellValue()).isEqualTo("Nº digitado errado");
            assertThat(aba.getRow(1).getCell(5).getStringCellValue()).isEqualTo("Ana");
        }

        when(alteracaoRepo.buscarDaOrdem(42L)).thenReturn(List.of());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.gerar(42L)))) {
            assertThat(wb.getSheet("Alterações").getRow(1).getCell(0).getStringCellValue())
                    .startsWith("Nenhuma alteração");
        }
    }

    /** Cada ponto com a situação em palavras; sem avaliação, a aba diz isso. */
    @Test
    void abaDeAvaliacaoTrazOsPontosOuDizQueNaoHouve() throws Exception {
        OrdemServico os = ordem();
        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");
        OrdemAvaliacao avaliacao = new OrdemAvaliacao(os, new OrdemAvaliacao.Dados(
                ItemAvaliacao.de("visual", true),
                ItemAvaliacao.de("aderencia", "descascando na borda"),
                ItemAvaliacao.de("embalagem", null),
                ItemAvaliacao.de("camada", true),
                "lote conferido"), joao);
        set(avaliacao, "avaliadaEm", T0);

        when(osRepo.findById(42L)).thenReturn(Optional.of(os));
        when(logRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(loteRepo.buscarParaRelatorio(42L)).thenReturn(List.of());
        when(desidroRepo.buscarDaOrdem(42L)).thenReturn(List.of());
        when(avaliacaoRepo.buscarDaOrdem(42L)).thenReturn(Optional.of(avaliacao));

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.gerar(42L)))) {
            Sheet aba = wb.getSheet("Avaliação");
            assertThat(aba.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Observação");
            assertThat(aba.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Avaliado");
            assertThat(aba.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Avaliado");
            assertThat(aba.getRow(2).getCell(2).getStringCellValue()).isEqualTo("descascando na borda");
            assertThat(aba.getRow(3).getCell(1).getStringCellValue()).isEqualTo("Não avaliado");
            assertThat(textos(aba, "Verificado")).hasSize(1);
            assertThat(textos(aba, "Avaliada por")).hasSize(1);
        }

        when(avaliacaoRepo.buscarDaOrdem(42L)).thenReturn(Optional.empty());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(service.gerar(42L)))) {
            assertThat(wb.getSheet("Avaliação").getRow(1).getCell(0).getStringCellValue())
                    .startsWith("Sem avaliação");
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

    /** Textos da coluna A que começam pelo prefixo — títulos de bloco e rótulos. */
    private List<String> textos(Sheet aba, String prefixo) {
        return java.util.stream.StreamSupport.stream(aba.spliterator(), false)
                .map(r -> r.getCell(0))
                .filter(c -> c != null && c.getCellType() == CellType.STRING)
                .map(Cell::getStringCellValue)
                .filter(v -> v.startsWith(prefixo))
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
        return ordemDe(42L, 99123L);
    }

    private OrdemServico ordemDe(Long id, Long idExterno) throws Exception {
        OrdemServico os = new OrdemServico(idExterno, new Cliente(1L, "ACME LTDA"), Posicao.OXIDACAO);
        set(os, "id", id);
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

    private OrdemDesidrogenizacao desidro(OrdemServico os, Operador op, int duracaoMin)
            throws Exception {
        OrdemDesidrogenizacao d = new OrdemDesidrogenizacao(
                os, new Desidrogenizacao("Têmpera", duracaoMin), op, new BigDecimal("190"));
        // Carimbos do banco (clock_timestamp e a trigger do término): no teste
        // entram por reflexão, como os das demais entidades.
        set(d, "iniciadaEm", T0);
        set(d, "finalizadaEm", T0.plus(duracaoMin, ChronoUnit.MINUTES));
        return d;
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
