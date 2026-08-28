package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.PeriodoInvalidoException;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.util.DataHoraBr;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.assertj.core.data.Offset;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A montagem da planilha só falha em runtime (regiões mescladas sobrepostas,
 * fórmula inválida, faixa fora do limite), então o teste gera o arquivo de
 * verdade e o relê com o POI.
 */
@ExtendWith(MockitoExtension.class)
class PlanilhaPeriodoServiceTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 8, 1);
    private static final LocalDate FIM = LocalDate.of(2026, 8, 31);
    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    /**
     * Tempo no Excel é fração de dia, então toda comparação é de double. A
     * planilha soma as etapas uma a uma; reproduzir a mesma ordem de soma no
     * teste só amarraria o teste à implementação — a tolerância resolve.
     */
    private static final Offset<Double> PRECISAO = within(1e-12);

    private static double horas(double h) {
        return h * 3600 / 86_400d;
    }

    /** Índices na tabela de OSs (ver CABECALHO do service). */
    private static final int COL_ID_EXTERNO = 0;
    private static final int COL_FINALIZADA_EM = 6;
    private static final int COL_DURACAO = 8;
    private static final int COL_TRABALHADO = 9;
    private static final int COL_CARGAS = 10;
    private static final int COL_PROCESSOS = 11;
    private static final int COL_ETAPAS = 12;
    private static final int COL_CONCLUIDAS = 13;
    private static final int COL_EM_ABERTO = 14;
    private static final int COL_CANCELADAS = 15;
    private static final int COL_PRE = 16;
    private static final int COL_TRATAMENTO = 17;
    private static final int COL_POS = 18;

    /** Índices na aba Etapas (ver CABECALHO_ETAPAS). */
    private static final int ET_OS = 0;
    private static final int ET_OS_INICIO_DATA = 2;
    private static final int ET_OS_INICIO_HORA = 3;
    private static final int ET_OS_FIM_DATA = 4;
    private static final int ET_OS_FIM_HORA = 5;
    private static final int ET_ETAPA = 9;
    private static final int ET_FIM = 12;
    private static final int ET_DURACAO = 13;
    private static final int ET_SITUACAO = 14;

    @Mock private OrdemServicoRepository osRepo;
    @Mock private LogRepository logRepo;

    @InjectMocks private PlanilhaPeriodoService service;

    @Test
    void resumeAsEtapasDeCadaOrdemEDetalhaNaAbaEtapas() throws Exception {
        OrdemServico finalizada = ordem(10L, 0, 8, false);
        OrdemServico emProcesso = ordem(11L, 30, -1, false);
        OrdemServico cancelada = ordem(12L, 60, 65, true);

        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");
        Carga tambor = carga(1L, "TAMBOR-01", TipoCarga.TAMBOR);
        Carga cesto = carga(2L, "CESTO-04", TipoCarga.CESTO);
        Processo desengraxe = processo(1L, "Desengraxe", Etapa.PRE_TRATAMENTO);
        Processo banho = processo(2L, "Banho ácido", Etapa.TRATAMENTO);
        Processo enxague = processo(3L, "Enxágue", Etapa.POS_TRATAMENTO);

        List<Log> etapas = List.of(
                log(finalizada, joao, tambor, desengraxe, 0, 2, false),   // 2h no pré
                log(finalizada, joao, tambor, banho, 2, 3, false),        // 1h no tratamento
                log(finalizada, joao, cesto, enxague, 3, 4, true),        // cancelada
                log(finalizada, joao, cesto, desengraxe, 4, -1, false),   // em aberto
                // numa OS que ainda não fechou: as colunas de "OS finalizada"
                // da aba Etapas precisam sair vazias
                log(emProcesso, joao, tambor, desengraxe, 30, 31, false));

        when(osRepo.buscarParaRelatorioPorPeriodo(
                DataHoraBr.inicioDoDia(INICIO), DataHoraBr.inicioDoDiaSeguinte(FIM)))
                .thenReturn(List.of(finalizada, emProcesso, cancelada));
        when(logRepo.buscarParaRelatorioDeOrdens(List.of(10L, 11L, 12L))).thenReturn(etapas);

        byte[] bytes = service.gerar(INICIO, FIM);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetName(0)).isEqualTo("Relatório");
            assertThat(wb.getSheetName(1)).isEqualTo("Dados");
            assertThat(wb.getSheetName(2)).isEqualTo("Etapas");

            XSSFSheet relatorio = wb.getSheetAt(0);
            List<Row> linhas = linhasDeDados(relatorio);
            assertThat(linhas).hasSize(3);
            // a OS é identificada pelo id externo; o id interno não aparece
            assertThat(linhas).extracting(r -> r.getCell(COL_ID_EXTERNO).getStringCellValue())
                    .containsExactly("90010", "90011", "90012");

            // --- os números de etapa da OS que teve movimento
            Row comEtapas = linhas.get(0);
            assertThat(numero(comEtapas, COL_CARGAS)).isEqualTo(2);      // tambor e cesto
            assertThat(numero(comEtapas, COL_PROCESSOS)).isEqualTo(3);
            assertThat(numero(comEtapas, COL_ETAPAS)).isEqualTo(4);
            assertThat(numero(comEtapas, COL_CONCLUIDAS)).isEqualTo(2);
            assertThat(numero(comEtapas, COL_EM_ABERTO)).isEqualTo(1);
            assertThat(numero(comEtapas, COL_CANCELADAS)).isEqualTo(1);

            // tempo trabalhado = só as concluídas, quebrado por etapa do fluxo
            assertThat(comEtapas.getCell(COL_TRABALHADO).getNumericCellValue())
                    .isCloseTo(horas(3), PRECISAO);
            assertThat(comEtapas.getCell(COL_PRE).getNumericCellValue())
                    .isCloseTo(horas(2), PRECISAO);
            assertThat(comEtapas.getCell(COL_TRATAMENTO).getNumericCellValue())
                    .isCloseTo(horas(1), PRECISAO);
            // a única etapa de pós foi cancelada: sem medição, célula vazia
            assertThat(comEtapas.getCell(COL_POS).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(comEtapas.getCell(COL_DURACAO).getNumericCellValue())
                    .isCloseTo(horas(8), PRECISAO);

            // --- OS em processo: sem fim, sem duração, contadores zerados
            Row aberta = linhas.get(1);
            assertThat(aberta.getCell(COL_FINALIZADA_EM).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(aberta.getCell(COL_DURACAO).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(numero(aberta, COL_ETAPAS)).isEqualTo(1);

            // --- OS cancelada: sem tempo, mas os contadores continuam
            Row riscada = linhas.get(2);
            assertThat(riscada.getCell(COL_DURACAO).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(riscada.getCell(COL_TRABALHADO).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(riscada.getCell(COL_ETAPAS).getCellType()).isEqualTo(CellType.NUMERIC);

            // --- as somas do resumo são fórmulas sobre a faixa real das linhas
            int primeira = linhas.get(0).getRowNum() + 1;
            int ultima = linhas.get(2).getRowNum() + 1;
            List<Cell> somas = formulas(relatorio);
            assertThat(somas).hasSize(2);
            assertThat(somas.get(0).getCellFormula())
                    .isEqualTo("SUM(I" + primeira + ":I" + ultima + ")");
            assertThat(somas.get(1).getCellFormula())
                    .isEqualTo("SUM(J" + primeira + ":J" + ultima + ")");

            // o logo do classpath entra como figura ancorada no topo
            assertThat(relatorio.getDrawingPatriarch().getShapes()).hasSize(1);
            logoEntraRecortadoEComoPng(wb);

            // autofiltro nas abas planas, não na formatada
            assertThat(relatorio.getCTWorksheet().isSetAutoFilter()).isFalse();
            assertThat(wb.getSheetAt(1).getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(wb.getSheetAt(2).getCTWorksheet().isSetAutoFilter()).isTrue();

            // --- a aba Etapas traz os cinco passos, cabeçalho + 5 linhas
            XSSFSheet abaEtapas = wb.getSheetAt(2);
            assertThat(abaEtapas.getLastRowNum()).isEqualTo(5);
            assertThat(cabecalho(abaEtapas, 0)).isEqualTo("N° da OS");
            assertThat(abaEtapas.getRow(1).getCell(ET_OS).getStringCellValue()).isEqualTo("90010");
            assertThat(abaEtapas.getRow(1).getCell(ET_ETAPA).getStringCellValue())
                    .isEqualTo(Etapa.PRE_TRATAMENTO.name());

            // os carimbos da OS, com dia e hora em colunas separadas. T0 é
            // 10:00Z, que no fuso da fábrica (UTC-3) é 07:00 do dia 01/08.
            Row primeiraEtapa = abaEtapas.getRow(1);
            assertThat(primeiraEtapa.getCell(ET_OS_INICIO_DATA).getLocalDateTimeCellValue()
                    .toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(primeiraEtapa.getCell(ET_OS_INICIO_HORA).getNumericCellValue())
                    .isCloseTo(horas(7), PRECISAO);
            // fim = T0 + 8h = 15:00 local
            assertThat(primeiraEtapa.getCell(ET_OS_FIM_HORA).getNumericCellValue())
                    .isCloseTo(horas(15), PRECISAO);

            // etapa cancelada aparece, mas sem duração
            assertThat(abaEtapas.getRow(3).getCell(ET_SITUACAO).getStringCellValue())
                    .isEqualTo("Cancelada");
            assertThat(abaEtapas.getRow(3).getCell(ET_DURACAO).getCellType())
                    .isEqualTo(CellType.BLANK);
            // etapa em andamento: sem fim e sem duração
            assertThat(abaEtapas.getRow(4).getCell(ET_FIM).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(abaEtapas.getRow(4).getCell(ET_DURACAO).getCellType())
                    .isEqualTo(CellType.BLANK);

            // a etapa da OS ainda em processo: as duas colunas de "OS
            // finalizada" ficam vazias, e as de "OS iniciada" não
            Row deOsAberta = abaEtapas.getRow(5);
            assertThat(deOsAberta.getCell(ET_OS).getStringCellValue()).isEqualTo("90011");
            assertThat(deOsAberta.getCell(ET_OS_INICIO_DATA).getCellType())
                    .isEqualTo(CellType.NUMERIC);
            assertThat(deOsAberta.getCell(ET_OS_FIM_DATA).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(deOsAberta.getCell(ET_OS_FIM_HORA).getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    @Test
    void periodoSemOrdensGeraArquivoValidoESemConsultarAsEtapas() throws Exception {
        when(osRepo.buscarParaRelatorioPorPeriodo(
                DataHoraBr.inicioDoDia(INICIO), DataHoraBr.inicioDoDiaSeguinte(FIM)))
                .thenReturn(List.of());

        byte[] bytes = service.gerar(INICIO, FIM);

        // `in ()` é SQL inválido: sem OSs, a busca das etapas não pode nem ser chamada
        verify(logRepo, never()).buscarParaRelatorioDeOrdens(anyCollection());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(linhasDeDados(wb.getSheetAt(0))).isEmpty();
            // sem faixa para somar, o resumo não ganha fórmula nenhuma
            assertThat(formulas(wb.getSheetAt(0))).isEmpty();
            // aba de etapas só com o cabeçalho
            assertThat(wb.getSheetAt(2).getLastRowNum()).isZero();
        }
    }

    @Test
    void recusaPeriodoQueNaoFecha() {
        assertThatThrownBy(() -> service.gerar(FIM, INICIO))
                .isInstanceOf(PeriodoInvalidoException.class)
                .hasMessageContaining("anterior");
    }

    // ------------------------------------------------------------------ apoio

    /**
     * O arquivo do logo tem ~37% de cada eixo em padding e é um JPEG com
     * extensão .png. Os dois problemas se resolvem na hora de desenhar, e é o
     * que se afirma aqui: a figura embarcada é menor que o arquivo de origem
     * (foi recortada) e está declarada como PNG (foi reencodada).
     */
    private void logoEntraRecortadoEComoPng(XSSFWorkbook wb) throws Exception {
        assertThat(wb.getAllPictures()).hasSize(1);
        XSSFPictureData figura = (XSSFPictureData) wb.getAllPictures().get(0);
        assertThat(figura.getPictureType()).isEqualTo(Workbook.PICTURE_TYPE_PNG);

        BufferedImage desenhada = ImageIO.read(new ByteArrayInputStream(figura.getData()));
        BufferedImage original;
        try (InputStream in = getClass().getResourceAsStream("/relatorio/logo-ramajo.jpeg")) {
            original = ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
        }
        assertThat(desenhada.getWidth()).isLessThan(original.getWidth());
        assertThat(desenhada.getHeight()).isLessThan(original.getHeight());
    }

    private String cabecalho(Sheet aba, int coluna) {
        return aba.getRow(0).getCell(coluna).getStringCellValue();
    }

    private long numero(Row r, int coluna) {
        assertThat(r.getCell(coluna).getCellType()).isEqualTo(CellType.NUMERIC);
        return (long) r.getCell(coluna).getNumericCellValue();
    }

    /**
     * As linhas da tabela: as que vêm DEPOIS do cabeçalho e trazem um id na
     * coluna A. O corte pelo cabeçalho é necessário — a faixa de indicadores,
     * acima, também tem número na coluna A.
     */
    private List<Row> linhasDeDados(Sheet aba) {
        int cabecalho = -1;
        for (Row r : aba) {
            Cell c = r.getCell(0);
            if (c != null && c.getCellType() == CellType.STRING
                    && "N° da OS".equals(c.getStringCellValue())) {
                cabecalho = r.getRowNum();
                break;
            }
        }
        assertThat(cabecalho).as("linha de cabeçalho da tabela").isNotNegative();
        final int inicio = cabecalho;
        return java.util.stream.StreamSupport.stream(aba.spliterator(), false)
                .filter(r -> r.getRowNum() > inicio)
                .filter(r -> {
                    Cell c = r.getCell(0);
                    return c != null && c.getCellType() == CellType.STRING
                            && c.getStringCellValue().matches("\\d+");
                })
                .toList();
    }

    private List<Cell> formulas(Sheet aba) {
        return java.util.stream.StreamSupport.stream(aba.spliterator(), false)
                .flatMap(r -> java.util.stream.StreamSupport.stream(r.spliterator(), false))
                .filter(c -> c.getCellType() == CellType.FORMULA)
                .toList();
    }

    /** horaInicio/horaFim relativas a T0; fim negativo = OS ainda em processo. */
    private OrdemServico ordem(Long id, int horaInicio, int horaFim, boolean cancelada)
            throws Exception {
        OrdemServico os = new OrdemServico(
                90000L + id, new Cliente(1L, "ACME LTDA"), Posicao.OXIDACAO);
        set(os, "id", id);
        set(os, "iniciadaEm", T0.plus(horaInicio, ChronoUnit.HOURS));
        set(os, "iniciadaPor", new Operador("João", Permissao.FUNCIONARIO, "T1"));
        if (horaFim >= 0) {
            os.setFinalizadaEm(T0.plus(horaFim, ChronoUnit.HOURS));
            os.setFinalizadaPor(new Operador("Maria", Permissao.FUNCIONARIO, "T2"));
        }
        os.setCancelada(cancelada);
        return os;
    }

    private Carga carga(Long id, String nome, TipoCarga tipo) throws Exception {
        Carga c = new Carga(nome, tipo, Posicao.OXIDACAO);
        set(c, "id", id);
        return c;
    }

    private Processo processo(Long id, String descricao, Etapa etapa) throws Exception {
        Processo p = new Processo(descricao, etapa);
        set(p, "id", id);
        return p;
    }

    /** horaInicio/horaFim relativas a T0; fim negativo = etapa em andamento. */
    private Log log(OrdemServico os, Operador op, Carga carga, Processo processo,
                    int horaInicio, int horaFim, boolean cancelado) throws Exception {
        Log l = new Log(os, op, carga, processo);
        set(l, "id", UUID.randomUUID());
        set(l, "iniciadoEm", T0.plus(horaInicio, ChronoUnit.HOURS));
        if (horaFim >= 0) {
            l.setFinalizadoEm(T0.plus(horaFim, ChronoUnit.HOURS));
        }
        l.setCancelado(cancelado);
        return l;
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
