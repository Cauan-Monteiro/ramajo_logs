package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.CargaDtos.CargaDTO;
import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.AplicarDesidrogenizacaoDTO;
import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.OrdemDesidrogenizacaoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.AcoplamentoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.AcoplarDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.AdicionarPosicaoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.AvaliacaoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.SalvarAvaliacaoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.CancelarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.CorrigirOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.CriarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.EntregarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.FinalizarLogDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.FinalizarLoteDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.FinalizarOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.IniciarLogDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.IniciarLogPorTagDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.LiberarCargasDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.LogDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.LoteDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemAuditoriaDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemAlteracaoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemCriadaDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemDetalheDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.OrdemResumoDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.ReaberturaDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.ReabrirOrdemDTO;
import com.ramajo.logs.system.dtos.OrdemDtos.VincularCargaDTO;
import com.ramajo.logs.system.services.AuditoriaService;
import com.ramajo.logs.system.services.DesidrogenizacaoService;
import com.ramajo.logs.system.services.OrdemAvaliacaoService;
import com.ramajo.logs.system.services.OrdemServicoService;
import com.ramajo.logs.system.services.PlanilhaOrdemServicoService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final DesidrogenizacaoService desidrogenizacaoService;
    private final OrdemAvaliacaoService avaliacaoService;
    private final AuditoriaService auditoriaService;

    public OrdemServicoController(OrdemServicoService service,
                                  PlanilhaOrdemServicoService planilhaService,
                                  DesidrogenizacaoService desidrogenizacaoService,
                                  OrdemAvaliacaoService avaliacaoService,
                                  AuditoriaService auditoriaService) {
        this.service = service;
        this.planilhaService = planilhaService;
        this.desidrogenizacaoService = desidrogenizacaoService;
        this.avaliacaoService = avaliacaoService;
        this.auditoriaService = auditoriaService;
    }

    /**
     * Nem sempre cria: se o Nº já for de uma OS daquele mesmo setor, o service
     * vincula as cargas a ela em vez de abrir outra (ver criar()). O corpo é o
     * mesmo nos dois casos — muda o status, 201 com Location para a OS nova e
     * 200 para a que já existia, porque nada foi criado.
     */
    @PostMapping
    public ResponseEntity<OrdemDetalheDTO> criar(@Valid @RequestBody CriarOrdemDTO dto) {
        OrdemCriadaDTO criada = service.criarEMapear(
                dto.clienteId(), dto.operadorId(), dto.idExterno(), dto.posicao(),
                dto.cargaIds(), acopladasPorCarga(dto.acoplamentos()));

        if (criada.vinculada()) {
            return ResponseEntity.ok(criada.ordem());
        }
        return ResponseEntity
                .created(URI.create("/api/ordens/" + criada.ordem().id()))
                .body(criada.ordem());
    }

    /**
     * Os pares (carga, OS) que chegam na criação, agrupados pela carga — que é
     * como o service os aplica. LinkedHashMap/ArrayList preservam a ordem do
     * pedido, então a mensagem de erro de um par inválido cita a mesma carga
     * que o operador viu na tela.
     */
    private static Map<Long, List<Long>> acopladasPorCarga(List<AcoplamentoDTO> acoplamentos) {
        Map<Long, List<Long>> porCarga = new LinkedHashMap<>();
        for (AcoplamentoDTO a : acoplamentos) {
            porCarga.computeIfAbsent(a.cargaId(), k -> new java.util.ArrayList<>())
                    .add(a.ordemServicoId());
        }
        return porCarga;
    }

    @GetMapping
    public List<OrdemResumoDTO> listar(
            @RequestParam(defaultValue = "false") boolean emProcesso) {
        return service.listar(emProcesso);
    }

    @GetMapping("/{id}")
    public OrdemDetalheDTO buscar(@PathVariable Long id) {
        return service.buscarDetalhe(id);
    }

    // Correção pelo ADMIN (Nº, cliente, setores). PUT porque o corpo traz os
    // três campos inteiros; cada um que muda vira linha no histórico abaixo.
    // É por aqui que um setor é REMOVIDO — o que solta as cargas dele e
    // cancela os passos abertos nelas; acrescentar tem rota própria.
    @PutMapping("/{id}")
    public OrdemDetalheDTO corrigir(@PathVariable Long id, @Valid @RequestBody CorrigirOrdemDTO dto) {
        return service.corrigir(
                id, dto.operadorId(), dto.idExterno(), dto.clienteId(), dto.posicoes(),
                dto.cargaIds(), dto.motivo());
    }

    /**
     * A OS passa a rodar TAMBÉM neste setor — a exceção das peças partidas
     * entre dois setores.
     *
     * POST e não PUT: acrescenta um elemento, não substitui o conjunto (quem
     * substitui é a correção acima). Sem gate de ADMIN de propósito — é
     * trabalho de chão, como vincular carga, e não desfaz nada. Idempotente,
     * daí 200 e não 201: repetir devolve a OS como está.
     */
    @PostMapping("/{id}/posicoes")
    public OrdemDetalheDTO adicionarPosicao(
            @PathVariable Long id, @Valid @RequestBody AdicionarPosicaoDTO dto) {
        return service.adicionarPosicaoEMapear(id, dto.posicao(), dto.operadorId());
    }

    @GetMapping("/{id}/alteracoes")
    public List<OrdemAlteracaoDTO> alteracoes(@PathVariable Long id) {
        return service.alteracoes(id);
    }

    @GetMapping("/{id}/logs")
    public List<LogDTO> historico(@PathVariable Long id) {
        return service.historico(id);
    }

    /**
     * A mesma coisa da rota acima, para VÁRIAS OS num pedido só — o que poupa a
     * Visão Geral de abrir uma conexão por ordem.
     *
     * Devolve uma lista PLANA: um passo de carona pertence a duas ou três OS, e
     * agrupá-lo por ordem o repetiria. Quem lê reexpande pelo `ordemServicoId` e
     * pelo `ordensAcopladas` de cada LogDTO.
     *
     * `logs` é segmento literal e não colide com o @GetMapping("/{id}") acima:
     * o Spring resolve o literal primeiro, como já acontece com /logs/tag.
     */
    @GetMapping("/logs")
    public List<LogDTO> historicoDeOrdens(@RequestParam List<Long> ids) {
        return auditoriaService.historicoDeOrdens(ids);
    }

    /**
     * Os campos que a Visão Geral lê de cada OS e que o resumo da listagem não
     * traz — quem abriu, quem fechou, lotes e desidrogenizações —, para várias
     * ordens de uma vez. Substitui um GET de detalhe por OS.
     */
    @GetMapping("/auditoria")
    public List<OrdemAuditoriaDTO> auditoriaDeOrdens(@RequestParam List<Long> ids) {
        return auditoriaService.auditoriaDeOrdens(ids);
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
        LogDTO log = service.vincularCargaEMapear(
                id, dto.cargaId(), dto.operadorId(), dto.ordensAcopladasIds());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/logs/" + log.id()))
                .body(log);
    }

    // passo 2: abrir
    @PostMapping("/{id}/logs")
    public ResponseEntity<LogDTO> iniciarLog(
            @PathVariable Long id, @Valid @RequestBody IniciarLogDTO dto) {
        LogDTO log = service.iniciarLogEMapear(
                id, dto.cargaId(), dto.processoId(), dto.responsavelId());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/logs/" + log.id()))
                .body(log);
    }

    // passo 2: abrir pelo leitor de tags, com a OS já escolhida na URL.
    @PostMapping("/{id}/logs/tag")
    public ResponseEntity<LogDTO> iniciarLogPorTag(
            @PathVariable Long id, @Valid @RequestBody IniciarLogPorTagDTO dto) {
        LogDTO log = service.iniciarLogPorTag(
                id, dto.cargaTagId(), dto.processoTagId(), dto.responsavelTagId());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/logs/" + log.id()))
                .body(log);
    }

    // passo 2: abrir só com as três tags — a OS vem do vínculo atual da carga.
    // É o endpoint do terminal de chão de fábrica: encostou os três crachás,
    // abriu o passo, sem ninguém digitar o número da OS.
    @PostMapping("/logs/tag")
    public ResponseEntity<LogDTO> iniciarLogPorTag(
            @Valid @RequestBody IniciarLogPorTagDTO dto) {
        LogDTO log = service.iniciarLogPorTag(
                dto.cargaTagId(), dto.processoTagId(), dto.responsavelTagId());
        return ResponseEntity
                .created(URI.create(
                        "/api/ordens/" + log.ordemServicoId() + "/logs/" + log.id()))
                .body(log);
    }

    // passo 2: fechar (fim do intervalo). A hora é a do servidor; o corpo traz
    // só quem fechou — o passo passou a registar isso (ver Log.finalizadoPor).
    @PatchMapping("/logs/{logId}/finalizar")
    public LogDTO finalizarLog(
            @PathVariable UUID logId, @Valid @RequestBody FinalizarLogDTO dto) {
        return service.finalizarLog(logId, dto.operadorId());
    }

    // As peças desta OS entraram no tanque de outra. O vínculo é com a CARGA,
    // não com o passo: vale para a etapa em curso e para todas as seguintes,
    // até a carga ser liberada. Os passos abertos da carona fecham junto — as
    // peças dela saíram da carga própria.
    //
    // Sob /api/ordens e não /api/cargas por ser a mesma decisão de produção
    // que as rotas vizinhas, servida pelo mesmo service. O padrão já estava
    // posto por /api/ordens/logs/{logId}/finalizar.
    @PostMapping("/cargas/{cargaId}/acopladas/{osId}")
    public CargaDTO acoplar(@PathVariable Long cargaId, @PathVariable Long osId,
                            @Valid @RequestBody AcoplarDTO dto) {
        return service.acoplarNaCargaEMapear(cargaId, osId, dto.operadorId());
    }

    // Correção: as peças daquela OS não estão nesta carga. Sai da carga e do
    // passo em curso; os passos já fechados guardam a composição que tiveram,
    // porque `logs` é o registro do que aconteceu.
    @DeleteMapping("/cargas/{cargaId}/acopladas/{osId}")
    public ResponseEntity<Void> desacoplar(@PathVariable Long cargaId, @PathVariable Long osId) {
        service.desacoplarDaCarga(cargaId, osId);
        return ResponseEntity.noContent().build();
    }

    // passo 2c: fim do trabalho de algumas cargas. Fecha o passo aberto de cada
    // uma e as devolve ao pool de livres; a OS segue aberta e o LOTE NÃO MUDA.
    // É a rotina "encerrar etapas" da home — virar o lote é decisão de
    // expedição parcial, na rota abaixo.
    @PostMapping("/{id}/cargas/liberar")
    public ResponseEntity<Void> liberarCargas(
            @PathVariable Long id, @Valid @RequestBody LiberarCargasDTO dto) {
        service.liberarCargas(id, dto.operadorId(), dto.cargaIds());
        return ResponseEntity.noContent().build();
    }

    // passo 2b: fim de uma PARTE da produção (expedição parcial). Fecha o lote
    // corrente e abre o seguinte; a OS segue aberta. Retorna o lote recém-aberto.
    // É o único caminho que leva uma OS ao 2º lote. Com `cargaIds` no corpo,
    // essas cargas fecham o passo e saem da OS junto com o lote.
    @PostMapping("/{id}/lotes/finalizar")
    public LoteDTO finalizarLote(
            @PathVariable Long id, @Valid @RequestBody FinalizarLoteDTO dto) {
        return service.finalizarLote(id, dto.operadorId(), dto.cargaIds());
    }

    @GetMapping("/{id}/lotes")
    public List<LoteDTO> lotes(@PathVariable Long id) {
        return service.lotes(id);
    }

    // DESIDROGENIZAÇÃO  ======================================================
    // Etapa opcional de forno, aplicada da Inspeção Final. Sub-recurso da OS
    // como lotes e cargas; o CADASTRO das receitas fica em
    // /api/desidrogenizacoes.

    @PostMapping("/{id}/desidrogenizacoes")
    public ResponseEntity<OrdemDesidrogenizacaoDTO> aplicarDesidrogenizacao(
            @PathVariable Long id, @Valid @RequestBody AplicarDesidrogenizacaoDTO dto) {
        OrdemDesidrogenizacaoDTO aplicada =
                desidrogenizacaoService.aplicar(id, dto.desidrogenizacaoId(), dto.operadorId());
        return ResponseEntity
                .created(URI.create("/api/ordens/" + id + "/desidrogenizacoes"))
                .body(aplicada);
    }

    @GetMapping("/{id}/desidrogenizacoes")
    public List<OrdemDesidrogenizacaoDTO> desidrogenizacoes(@PathVariable Long id) {
        return desidrogenizacaoService.daOrdem(id);
    }

    // passo 3: expedição total (fecha a OS e o lote corrente junto). Com
    // `avaliacao` no corpo, a inspeção final é gravada na mesma transação.
    @PostMapping("/{id}/finalizar")
    public ResponseEntity<Void> finalizar(
            @PathVariable Long id, @Valid @RequestBody FinalizarOrdemDTO dto) {
        service.finalizar(id, dto.operadorId(),
                dto.avaliacao() == null ? null : dto.avaliacao().entrada());
        return ResponseEntity.noContent().build();
    }

    // AVALIAÇÃO  =============================================================
    // A da inspeção final. Nasce na expedição (acima); estas rotas são a
    // leitura e a avaliação feita depois, pelo ADMIN no Ajustes.

    // 204 quando a OS não tem avaliação: é o estado normal de muitas, não erro.
    @GetMapping("/{id}/avaliacao")
    public ResponseEntity<AvaliacaoDTO> avaliacao(@PathVariable Long id) {
        return avaliacaoService.daOrdem(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/{id}/avaliacao")
    public AvaliacaoDTO avaliar(@PathVariable Long id, @Valid @RequestBody SalvarAvaliacaoDTO dto) {
        return avaliacaoService.avaliarComoAdminEMapear(
                id, dto.operadorId(), dto.avaliacao().entrada());
    }

    // desfaz o passo 3: a OS expedida volta a produzir num lote NOVO, sem tocar
    // nos anteriores. Retorna o lote recém-aberto, como finalizarLote, e junto
    // as cargas que a expedição soltou e ainda estão livres — sugestão para o
    // modal de vínculo, não vínculo já feito. Ver ReaberturaDTO.
    @PostMapping("/{id}/reabrir")
    public ReaberturaDTO reabrir(@PathVariable Long id, @Valid @RequestBody ReabrirOrdemDTO dto) {
        return service.reabrirEMapear(id, dto.operadorId());
    }

    // passo 4: a entrega ao cliente. A expedição tira as peças da produção;
    // esta rota regista que saíram da casa, e por quem. Só sobre OS expedida, e
    // uma vez só — reabrir a OS apaga o carimbo (ver OrdemServicoService).
    @PostMapping("/{id}/entregar")
    public ResponseEntity<Void> entregar(
            @PathVariable Long id, @Valid @RequestBody EntregarOrdemDTO dto) {
        service.entregar(id, dto.operadorId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancelar")
    public ResponseEntity<Void> cancelar(
            @PathVariable Long id, @Valid @RequestBody CancelarOrdemDTO dto) {
        service.cancelar(id, dto.operadorId());
        return ResponseEntity.noContent().build();
    }
}
