package com.ramajo.logs.system.services;

import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.util.DataHoraBr;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * A escrita de célula que os relatórios .xlsx têm em comum.
 *
 * Duas escolhas de tipagem sustentam os relatórios inteiros:
 *   - horário vira célula de DATA de verdade (valor numérico + formato
 *     dd/mm/yyyy hh:mm:ss), não texto — o Excel ordena, filtra e subtrai;
 *   - duração vira fração de dia com formato [h]:mm:ss — some, tira média, e
 *     um total acima de 24h aparece como 32:15:00 em vez de voltar ao zero.
 */
final class EscritorPlanilha {

    private static final String CAMINHO_LOGO = "/relatorio/logo-ramajo.jpeg";
    /** Folga deixada em volta da arte ao recortar a moldura, em pixels. */
    private static final int MARGEM_LOGO = 2;
    /** Espessura do anel de borda ignorado ao procurar a arte, em pixels. */
    private static final int INSET_LOGO = 6;
    /**
     * O quanto a luminância precisa se afastar da do fundo para o pixel contar
     * como arte, numa escala 0-255. Largo de propósito: o fundo do arquivo é um
     * degradê fotográfico e chega a variar ~100 sozinho, enquanto branco sobre
     * escuro (ou o contrário) passa de 180.
     */
    private static final int LIMIAR_LOGO = 100;

    private EscritorPlanilha() {
    }

    static void cabecalhoTabela(Sheet aba, EstilosPlanilha e, int[] linha, String... titulos) {
        Row r = aba.createRow(linha[0]++);
        for (int i = 0; i < titulos.length; i++) {
            Cell c = r.createCell(i);
            c.setCellValue(titulos[i]);
            c.setCellStyle(e.cabecalhoTabela);
        }
    }

    static void rotulo(Row r, EstilosPlanilha e, int coluna, String valor) {
        Cell c = r.createCell(coluna);
        c.setCellValue(valor);
        c.setCellStyle(e.rotuloResumo);
    }

    static void valorTexto(Row r, EstilosPlanilha e, int coluna, String valor) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.valorResumo);
        if (valor != null && !valor.isEmpty()) {
            c.setCellValue(valor);
        }
    }

    static void valorData(Row r, EstilosPlanilha e, int coluna, Instant valor) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.valorResumoData);
        LocalDateTime local = DataHoraBr.local(valor);
        if (local != null) {
            c.setCellValue(local);
        }
    }

    static void valorDuracao(Row r, EstilosPlanilha e, int coluna, Double valor) {
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
    static void texto(Row r, EstilosPlanilha e, int coluna, String valor,
                      boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.texto(zebra, cancelada));
        if (valor != null && !valor.isEmpty()) {
            c.setCellValue(valor);
        }
    }

    static void data(Row r, EstilosPlanilha e, int coluna, Instant valor,
                     boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.data(zebra, cancelada));
        LocalDateTime local = DataHoraBr.local(valor);
        if (local != null) {
            c.setCellValue(local);
        }
    }

    static void duracao(Row r, EstilosPlanilha e, int coluna, Double valor,
                        boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.duracao(zebra, cancelada));
        if (valor != null) {
            c.setCellValue(valor);
        }
    }

    /** Só a data, para as colunas em que dia e hora vão separados. */
    static void dia(Row r, EstilosPlanilha e, int coluna, Instant valor,
                    boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.dia(zebra, cancelada));
        LocalDate d = DataHoraBr.dia(valor);
        if (d != null) {
            c.setCellValue(d);
        }
    }

    /** A hora correspondente — fração de dia, uma hora de verdade (ver DataHoraBr). */
    static void hora(Row r, EstilosPlanilha e, int coluna, Instant valor,
                     boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.hora(zebra, cancelada));
        Double h = DataHoraBr.horaDoDia(valor);
        if (h != null) {
            c.setCellValue(h);
        }
    }

    /**
     * Contagem como NÚMERO, não texto: assim ordena, soma e entra em média.
     * Identificador (id externo, tag) continua indo por {@link #texto} — é
     * rótulo, não quantidade, e não deveria ganhar separador de milhar.
     */
    static void numero(Row r, EstilosPlanilha e, int coluna, Long valor,
                       boolean zebra, boolean cancelada) {
        Cell c = r.createCell(coluna);
        c.setCellStyle(e.numero(zebra, cancelada));
        if (valor != null) {
            c.setCellValue(valor);
        }
    }

    static void ajustar(Sheet aba, int colunas) {
        for (int i = 0; i < colunas; i++) {
            aba.autoSizeColumn(i);
        }
    }

    /** Paisagem e ajustada à largura da folha — o relatório costuma ser impresso. */
    static void imprimivel(Sheet aba) {
        aba.setFitToPage(true);
        PrintSetup impressao = aba.getPrintSetup();
        impressao.setLandscape(true);
        impressao.setFitWidth((short) 1);
        impressao.setFitHeight((short) 0);
    }

    static String nome(Operador operador) {
        return operador == null ? "" : operador.getNome();
    }

    /** Cancelada vence finalizada: uma OS cancelada depois de fechada não é "Finalizada". */
    static String situacaoDaOrdem(OrdemServico os) {
        if (os.isCancelada()) {
            return "Cancelada";
        }
        return os.isFinalizada() ? "Finalizada" : "Em processo";
    }

    static String situacaoDaEtapa(Log log) {
        if (log.isCancelado()) {
            return "Cancelada";
        }
        return log.getFinalizadoEm() == null ? "Em andamento" : "Concluída";
    }

    /**
     * O logo é opcional de propósito: se o arquivo não estiver no classpath (ou
     * não for legível), o relatório sai só com o título em vez de derrubar a rota.
     *
     * O arquivo entra decodificado, recortado e REENCODADO como PNG. Reencodar
     * resolve de graça uma armadilha do classpath: o logo-ramajo.jpeg é na
     * verdade um JPEG com extensão trocada, e o Excel só desenha a figura se o
     * tipo declarado bater com o conteúdo dos bytes.
     *
     * @param larguraAlvo largura desejada do desenho, em pixels
     * @param alturaLinha altura da linha do título, em pontos
     */
    static void inserirLogo(XSSFWorkbook wb, Sheet aba, int linha,
                            int larguraAlvo, double alturaLinha) {
        try (InputStream entrada = EscritorPlanilha.class.getResourceAsStream(CAMINHO_LOGO)) {
            if (entrada == null) {
                return;
            }
            BufferedImage imagem = ImageIO.read(new ByteArrayInputStream(entrada.readAllBytes()));
            if (imagem == null || imagem.getWidth() == 0 || imagem.getHeight() == 0) {
                return;
            }
            imagem = semMoldura(imagem);

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(imagem, "png", png)) {
                return;
            }
            int indice = wb.addPicture(png.toByteArray(), Workbook.PICTURE_TYPE_PNG);
            Drawing<?> desenho = aba.createDrawingPatriarch();
            ClientAnchor ancora = wb.getCreationHelper().createClientAnchor();
            ancora.setCol1(0);
            ancora.setRow1(linha);
            Picture figura = desenho.createPicture(ancora, indice);
            // resize(escala) parte do tamanho nativo em pixels; a escala é a
            // maior que cabe na largura pedida e na altura da linha do título.
            double alvoAltura = alturaLinha * 96.0 / 72.0;
            figura.resize(Math.min((double) larguraAlvo / imagem.getWidth(),
                    alvoAltura / imagem.getHeight()));
        } catch (IOException | RuntimeException ignorado) {
            // relatório sem logo continua sendo um relatório válido
        }
    }

    /**
     * Enquadra a arte, cortando o padding em volta.
     *
     * O logo-ramajo tem ~37% de cada eixo em padding: sem recortar, a arte
     * renderiza a ~63% de uma célula que já é pequena, e não dá para ler.
     *
     * "Arte" é o que se afasta MUITO da luminância do fundo — não o que é claro,
     * nem o que é uma cor diferente. Cada alternativa falha aqui: procurar pixel
     * claro só serviria a arte clara sobre fundo escuro, e comparar cor a cor
     * não funciona porque o fundo é um degradê fotográfico que varia bem mais
     * que qualquer tolerância honesta de canal. Luminância contra um limiar
     * largo pega branco sobre escuro e preto sobre claro, e ignora o ruído do
     * degradê. Logo de baixo contraste não é recortado — e aí o pior que
     * acontece é continuar como está hoje.
     *
     * O anel de INSET pixels na borda fica fora da conta nos dois papéis: é dele
     * que sai a luminância de referência, e seus pixels não contam como arte —
     * o arquivo tem uma linha de 1px mais clara na moldura que, sem isso,
     * sozinha já esticaria a caixa até a borda. Se a arte encostar no anel, a
     * caixa se abre até a borda, para não decepar um logo que já venha justo.
     */
    private static BufferedImage semMoldura(BufferedImage imagem) {
        int largura = imagem.getWidth();
        int altura = imagem.getHeight();
        int inset = Math.min(INSET_LOGO, Math.min(largura, altura) / 4);
        int referencia = luminanciaDoAnel(imagem, inset);

        int esquerda = largura;
        int direita = -1;
        int topo = altura;
        int base = -1;
        for (int y = inset; y < altura - inset; y++) {
            for (int x = inset; x < largura - inset; x++) {
                if (Math.abs(luminancia(imagem.getRGB(x, y)) - referencia) <= LIMIAR_LOGO) {
                    continue;
                }
                esquerda = Math.min(esquerda, x);
                direita = Math.max(direita, x);
                topo = Math.min(topo, y);
                base = Math.max(base, y);
            }
        }
        if (direita < 0) {
            return imagem;   // nada se destaca do fundo: não há arte a enquadrar
        }

        // Folga em volta da arte; encostou no anel, abre até a borda.
        esquerda = esquerda <= inset ? 0 : Math.max(0, esquerda - MARGEM_LOGO);
        topo = topo <= inset ? 0 : Math.max(0, topo - MARGEM_LOGO);
        direita = direita >= largura - 1 - inset
                ? largura - 1 : Math.min(largura - 1, direita + MARGEM_LOGO);
        base = base >= altura - 1 - inset
                ? altura - 1 : Math.min(altura - 1, base + MARGEM_LOGO);

        int novaLargura = direita - esquerda + 1;
        int novaAltura = base - topo + 1;
        if (novaLargura < largura / 10 || novaAltura < altura / 10) {
            return imagem;
        }
        return imagem.getSubimage(esquerda, topo, novaLargura, novaAltura);
    }

    /** A luminância média da moldura, tomada como a do fundo. */
    private static int luminanciaDoAnel(BufferedImage img, int inset) {
        long soma = 0;
        int lidos = 0;
        for (int x = inset; x < img.getWidth() - inset; x++) {
            soma += luminancia(img.getRGB(x, inset));
            soma += luminancia(img.getRGB(x, img.getHeight() - 1 - inset));
            lidos += 2;
        }
        for (int y = inset; y < img.getHeight() - inset; y++) {
            soma += luminancia(img.getRGB(inset, y));
            soma += luminancia(img.getRGB(img.getWidth() - 1 - inset, y));
            lidos += 2;
        }
        return lidos == 0 ? 0 : (int) (soma / lidos);
    }

    private static int luminancia(int rgb) {
        return ((rgb >> 16 & 0xFF) * 30 + (rgb >> 8 & 0xFF) * 59 + (rgb & 0xFF) * 11) / 100;
    }
}
