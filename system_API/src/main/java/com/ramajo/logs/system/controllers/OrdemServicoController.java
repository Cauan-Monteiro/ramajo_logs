package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.OrdemDtos.CancelarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.CriarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.FinalizarLoteDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.FinalizarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.IniciarLogDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.IniciarLogPorTagDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.LogDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.LoteDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemDetalheDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemResumoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.VincularCargaDTO;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.services.OrdemServicoService;
import com.ramajo.logs.system.services.PlanilhaOrdemServicoService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fluxo principal: cada endpoint é uma transição do processo (criar OS ->
 * vincular carga -> abrir/fechar passo -> finalizar/cancelar). O controller é
 * fino: valida a entrada, chama o service e mapeia a saída para DTO.
 */
@RestController
@RequestMapping("/api/ordens")
public class OrdemServicoController {

    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OrdemServicoService service;
    private final PlanilhaOrdemServicoService planilhaService;

    public OrdemServicoController(OrdemServicoService service,
                                  PlanilhaOrdemServicoService planilhaService) {
        this.service = service;
        this.planilhaService = planilhaService;
    }

    @PostMapping
    public ResponseEntity<OrdemDetalheDTO> criar(@Valid @RequestBody CriarOrdemDTO dto) {
        OrdemServicoService.OrdemCriada criada = service.criar(
                dto.clienteId(), dto.operadorId(), dto.idExterno(), dto.posicao(),
                dto.cargaIds());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + criada.ordem().getId()))
                .body(OrdemDetalheDTO.from(criada.ordem(), criada.logsIniciados()));
    }

    @GetMapping
    public List<OrdemResumoDTO> listar(
            @RequestParam(defaultValue = "false") boolean emProcesso) {
        List<OrdemServico> lista =
                emProcesso ? service.listarEmProcesso() : service.listarTodas();
        return lista.stream().map(OrdemResumoDTO::from).toList();
    }

    @GetMapping("/{id}")
    public OrdemDetalheDTO buscar(@PathVariable Long id) {
        return OrdemDetalheDTO.from(service.buscar(id));
    }

    @GetMapping("/{id}/logs")
    public List<LogDTO> historico(@PathVariable Long id) {
        return service.historico(id).stream().map(LogDTO::from).toList();
    }

    /**
     * Download da OS inteira em uma planilha .xlsx (abas: Ordem de Serviço,
     * Lotes, Etapas e Cargas), com os horários já convertidos para o fuso da
     * fábrica. Sendo GET, não mexe no estado — o RevisaoFilter não incrementa
     * a revisão, e é isso que se quer: exportar não é uma transição.
     */
    @GetMapping(value = "/{id}/planilha", produces = XLSX)
    public ResponseEntity<byte[]> planilha(@PathVariable Long id) {
        byte[] xlsx = planilhaService.gerar(id);
        ContentDisposition anexo = ContentDisposition.attachment()
                .filename("ordem-servico-" + id + ".xlsx", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, anexo.toString())
                .contentType(MediaType.parseMediaType(XLSX))
                .cacheControl(CacheControl.noStore())
                .body(xlsx);
    }

    // passo 1: vincular a carga já abre o passo inicial dela, igual à criação
    // da OS — por isso a resposta é o passo, e não um 204 vazio.
    @PostMapping("/{id}/cargas")
    public ResponseEntity<LogDTO> vincularCarga(
            @PathVariable Long id, @Valid @RequestBody VincularCargaDTO dto) {
        Log log = service.vincularCarga(id, dto.cargaId(), dto.operadorId());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/logs/" + log.getId()))
                .body(LogDTO.from(log));
    }

    // passo 2: abrir
    @PostMapping("/{id}/logs")
    public ResponseEntity<LogDTO> iniciarLog(
            @PathVariable Long id, @Valid @RequestBody IniciarLogDTO dto) {
        Log log = service.iniciarLog(
                id, dto.cargaId(), dto.processoId(), dto.responsavelId());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/logs/" + log.getId()))
                .body(LogDTO.from(log));
    }

    // passo 2: abrir pelo leitor de tags, com a OS já escolhida na URL.
    @PostMapping("/{id}/logs/tag")
    public ResponseEntity<LogDTO> iniciarLogPorTag(
            @PathVariable Long id, @Valid @RequestBody IniciarLogPorTagDTO dto) {
        Log log = service.iniciarLogPorTag(
                id, dto.cargaTagId(), dto.processoTagId(), dto.responsavelTagId());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/logs/" + log.getId()))
                .body(LogDTO.from(log));
    }

    // passo 2: abrir só com as três tags — a OS vem do vínculo atual da carga.
    // É o endpoint do terminal de chão de fábrica: encostou os três crachás,
    // abriu o passo, sem ninguém digitar o número da OS.
    @PostMapping("/logs/tag")
    public ResponseEntity<LogDTO> iniciarLogPorTag(
            @Valid @RequestBody IniciarLogPorTagDTO dto) {
        Log log = service.iniciarLogPorTag(
                dto.cargaTagId(), dto.processoTagId(), dto.responsavelTagId());
        Long osId = log.getOrdemServico().getId();
        return ResponseEntity
                .created(URI.create("/api/ordens/" + osId + "/logs/" + log.getId()))
                .body(LogDTO.from(log));
    }

    // passo 2: fechar (fim do intervalo). Sem corpo: o service usa Instant.now().
    @PatchMapping("/logs/{logId}/finalizar")
    public LogDTO finalizarLog(@PathVariable UUID logId) {
        return LogDTO.from(service.finalizarLog(logId));
    }

    // passo 2b: fim de uma PARTE da produção. Fecha o lote corrente e abre o
    // seguinte; a OS segue aberta. Retorna o lote recém-aberto. Com `cargaIds`
    // no corpo é expedição parcial: essas cargas fecham o passo e saem da OS.
    @PostMapping("/{id}/lotes/finalizar")
    public LoteDTO finalizarLote(
            @PathVariable Long id, @Valid @RequestBody FinalizarLoteDTO dto) {
        Lote proximo = service.finalizarLote(id, dto.operadorId(), dto.cargaIds());
        return LoteDTO.from(proximo);
    }

    @GetMapping("/{id}/lotes")
    public List<LoteDTO> lotes(@PathVariable Long id) {
        return service.lotes(id).stream().map(LoteDTO::from).toList();
    }

    // passo 3: expedição total (fecha a OS e o lote corrente junto)
    @PostMapping("/{id}/finalizar")
    public ResponseEntity<Void> finalizar(
            @PathVariable Long id, @Valid @RequestBody FinalizarOrdemDTO dto) {
        service.finalizar(id, dto.operadorId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancelar")
    public ResponseEntity<Void> cancelar(
            @PathVariable Long id, @Valid @RequestBody CancelarOrdemDTO dto) {
        service.cancelar(id, dto.operadorId());
        return ResponseEntity.noContent().build();
    }
}
