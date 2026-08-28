package com.ramajo.logs.system.services;

import java.util.EnumMap;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * A paleta e os estilos compartilhados pelos relatórios .xlsx.
 *
 * CellStyle é um recurso do workbook (o formato tem limite de ~64k por
 * arquivo), então cada estilo nasce uma vez e é reaproveitado em todas as
 * células — nunca dentro do laço. As variantes de linha (zebra, cancelada) são
 * resolvidas pelos métodos texto/data/duracao, para não espalhar `if` de estilo
 * pelo código de escrita.
 *
 * Instanciar UMA vez por workbook.
 */
final class EstilosPlanilha {

    static final String FORMATO_DATA = "dd/mm/yyyy hh:mm:ss";
    /** Os colchetes é que impedem as horas de estourarem em 24. */
    static final String FORMATO_DURACAO = "[h]:mm:ss";
    /** Para quando a planilha separa o carimbo em duas colunas. */
    static final String FORMATO_DIA = "dd/mm/yyyy";
    static final String FORMATO_HORA = "hh:mm:ss";

    private static final byte[] AZUL_ESCURO = {0x1F, 0x4E, 0x79};
    /**
     * O azul do próprio logo-ramajo. A faixa do título usa esta cor, e não o
     * AZUL_ESCURO das demais faixas, porque o arquivo do logo é opaco: sem
     * casar as duas, o desenho aparece como um retângulo de outro azul colado
     * por cima da faixa.
     */
    private static final byte[] AZUL_LOGO = {0x03, 0x5E, (byte) 0x81};
    private static final byte[] AZUL_MEDIO = {0x2E, 0x75, (byte) 0xB6};
    private static final byte[] AZUL_CLARO = {(byte) 0xEA, (byte) 0xF1, (byte) 0xF8};
    private static final byte[] AZUL_BORDA = {(byte) 0xB4, (byte) 0xC6, (byte) 0xE7};
    private static final byte[] VERMELHO_CLARO = {(byte) 0xFD, (byte) 0xEA, (byte) 0xEA};

    private final EstilosDeLinha normal;
    private final EstilosDeLinha cancelado;

    final XSSFCellStyle titulo;
    final XSSFCellStyle rotuloResumo;
    final XSSFCellStyle valorResumo;
    final XSSFCellStyle valorResumoData;
    final XSSFCellStyle valorResumoDuracao;
    final XSSFCellStyle indicadorLegenda;
    final XSSFCellStyle indicadorValor;
    final XSSFCellStyle subtituloCarga;
    final XSSFCellStyle cabecalhoTabela;
    final XSSFCellStyle rotuloSubtotal;
    final XSSFCellStyle duracaoSubtotal;
    final XSSFCellStyle rodape;
    final XSSFCellStyle rodapeData;

    EstilosPlanilha(XSSFWorkbook wb) {
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

        titulo = criar(wb, fTitulo, cor(AZUL_LOGO), null, false, HorizontalAlignment.LEFT);
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

        normal = new EstilosDeLinha();
        cancelado = new EstilosDeLinha();
        for (Forma forma : Forma.values()) {
            // Zebra é a única diferença entre as duas variantes da linha normal.
            normal.registrar(forma,
                    criar(wb, fNormal, null, forma.formato, true, forma.alinhamento),
                    criar(wb, fNormal, claro, forma.formato, true, forma.alinhamento));
            // Cancelada ignora a zebra: o fundo avermelhado já é o sinal, e
            // alternar por cima dele só embaralharia a leitura.
            XSSFCellStyle riscada =
                    criar(wb, fRiscada, cancelada, forma.formato, true, forma.alinhamento);
            cancelado.registrar(forma, riscada, riscada);
        }
    }

    CellStyle texto(boolean zebra, boolean cancelada) {
        return estilo(Forma.TEXTO, zebra, cancelada);
    }

    CellStyle data(boolean zebra, boolean cancelada) {
        return estilo(Forma.DATA, zebra, cancelada);
    }

    CellStyle duracao(boolean zebra, boolean cancelada) {
        return estilo(Forma.DURACAO, zebra, cancelada);
    }

    CellStyle numero(boolean zebra, boolean cancelada) {
        return estilo(Forma.NUMERO, zebra, cancelada);
    }

    CellStyle dia(boolean zebra, boolean cancelada) {
        return estilo(Forma.DIA, zebra, cancelada);
    }

    CellStyle hora(boolean zebra, boolean cancelada) {
        return estilo(Forma.HORA, zebra, cancelada);
    }

    private CellStyle estilo(Forma forma, boolean zebra, boolean cancelada) {
        return (cancelada ? cancelado : normal).de(forma, zebra);
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

    /** As formas de célula de dado, cada uma com seu formato e alinhamento. */
    private enum Forma {
        TEXTO(null, HorizontalAlignment.LEFT),
        DATA(FORMATO_DATA, HorizontalAlignment.CENTER),
        DURACAO(FORMATO_DURACAO, HorizontalAlignment.CENTER),
        NUMERO(null, HorizontalAlignment.CENTER),
        DIA(FORMATO_DIA, HorizontalAlignment.CENTER),
        HORA(FORMATO_HORA, HorizontalAlignment.CENTER);

        private final String formato;
        private final HorizontalAlignment alinhamento;

        Forma(String formato, HorizontalAlignment alinhamento) {
            this.formato = formato;
            this.alinhamento = alinhamento;
        }
    }

    /**
     * Os estilos de uma linha: uma forma x zebra. Mapa, e não campos soltos,
     * para que acrescentar uma forma nova seja uma entrada no enum acima em vez
     * de mais dois parâmetros num construtor posicional.
     */
    private static final class EstilosDeLinha {

        private final EnumMap<Forma, XSSFCellStyle[]> estilos = new EnumMap<>(Forma.class);

        private void registrar(Forma forma, XSSFCellStyle normal, XSSFCellStyle zebra) {
            estilos.put(forma, new XSSFCellStyle[]{normal, zebra});
        }

        private XSSFCellStyle de(Forma forma, boolean zebra) {
            return estilos.get(forma)[zebra ? 1 : 0];
        }
    }
}
