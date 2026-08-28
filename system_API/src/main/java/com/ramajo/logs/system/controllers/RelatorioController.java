package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.services.PlanilhaPeriodoService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios que atravessam várias OSs.
 *
 * Fora de /api/ordens de propósito: `GET /api/ordens/planilha` casaria com o
 * padrão `GET /api/ordens/{id}` e o Spring tentaria converter "planilha" em
 * Long. O relatório de UMA ordem continua em OrdemServicoController, junto do
 * recurso a que pertence.
 */
@RestController
@RequestMapping("/api/relatorios")
public class RelatorioController {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final PlanilhaPeriodoService planilhaPeriodo;

    public RelatorioController(PlanilhaPeriodoService planilhaPeriodo) {
        this.planilhaPeriodo = planilhaPeriodo;
    }

    /**
     * Download em .xlsx das OSs iniciadas no intervalo, uma linha por ordem.
     * As datas vêm em ISO (yyyy-MM-dd) e são lidas como dias de calendário no
     * fuso da fábrica; ambos os extremos entram inteiros.
     *
     * Sendo GET, não mexe no estado — o RevisaoFilter não incrementa a revisão.
     */
    @GetMapping(value = "/periodo/planilha", produces = XLSX)
    public ResponseEntity<byte[]> porPeriodo(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFim) {

        byte[] xlsx = planilhaPeriodo.gerar(dataInicio, dataFim);
        ContentDisposition anexo = ContentDisposition.attachment()
                .filename("ordens-servico-" + dataInicio + "-a-" + dataFim + ".xlsx",
                        StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, anexo.toString())
                .contentType(MediaType.parseMediaType(XLSX))
                .cacheControl(CacheControl.noStore())
                .body(xlsx);
    }
}
