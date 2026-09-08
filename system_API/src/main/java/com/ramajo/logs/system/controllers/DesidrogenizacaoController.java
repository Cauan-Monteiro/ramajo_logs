package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.ConfigDesidrogenizacaoDTO;
import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.CriarDesidrogenizacaoDTO;
import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.DefinirTemperaturaDTO;
import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.DesidroEmAndamentoDTO;
import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.DesidrogenizacaoDTO;
import com.ramajo.logs.system.entities.Desidrogenizacao;
import com.ramajo.logs.system.services.DesidrogenizacaoService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cadastro das receitas de desidrogenização e a temperatura do forno.
 *
 * A APLICAÇÃO de uma receita numa OS não está aqui: é sub-recurso da ordem,
 * em /api/ordens/{id}/desidrogenizacoes, junto de lotes e cargas.
 */
@RestController
@RequestMapping("/api/desidrogenizacoes")
public class DesidrogenizacaoController {

    private final DesidrogenizacaoService service;

    public DesidrogenizacaoController(DesidrogenizacaoService service) {
        this.service = service;
    }

    @GetMapping
    public List<DesidrogenizacaoDTO> listar(
            @RequestParam(defaultValue = "false") boolean arquivadas) {
        return service.listar(arquivadas).stream().map(DesidrogenizacaoDTO::from).toList();
    }

    @PostMapping
    public ResponseEntity<DesidrogenizacaoDTO> criar(
            @Valid @RequestBody CriarDesidrogenizacaoDTO dto) {
        Desidrogenizacao d = service.criar(dto.nome(), dto.duracaoMin(), dto.observacao());
        return ResponseEntity
                .created(URI.create("/api/desidrogenizacoes/" + d.getId()))
                .body(DesidrogenizacaoDTO.from(d));
    }

    /**
     * As desidrogenizações das OS em produção, para o indicativo do Dashboard.
     * Caminho literal declarado antes do /{id}: o Spring prefere o literal ao
     * template, como já acontece com /temperatura.
     */
    @GetMapping("/em-andamento")
    public List<DesidroEmAndamentoDTO> emAndamento() {
        return service.emAndamento().stream().map(DesidroEmAndamentoDTO::from).toList();
    }

    @GetMapping("/{id}")
    public DesidrogenizacaoDTO buscar(@PathVariable Long id) {
        return DesidrogenizacaoDTO.from(service.buscar(id));
    }

    @PutMapping("/{id}")
    public DesidrogenizacaoDTO atualizar(
            @PathVariable Long id, @Valid @RequestBody CriarDesidrogenizacaoDTO dto) {
        return DesidrogenizacaoDTO.from(
                service.atualizar(id, dto.nome(), dto.duracaoMin(), dto.observacao()));
    }

    /** Arquivamento (soft-delete), não remoção física — igual ao DELETE de ProcessoController. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> arquivar(@PathVariable Long id) {
        service.arquivar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reativar")
    public DesidrogenizacaoDTO reativar(@PathVariable Long id) {
        return DesidrogenizacaoDTO.from(service.reativar(id));
    }

    // TEMPERATURA  ===========================================================
    // Sem POST nem DELETE: existe exatamente uma configuração, e só o valor
    // muda. Mesma lógica do PUT-only de ProcessoInicialController.

    @GetMapping("/temperatura")
    public ConfigDesidrogenizacaoDTO temperatura() {
        return ConfigDesidrogenizacaoDTO.from(service.configuracao());
    }

    @PutMapping("/temperatura")
    public ConfigDesidrogenizacaoDTO definirTemperatura(
            @Valid @RequestBody DefinirTemperaturaDTO dto) {
        return ConfigDesidrogenizacaoDTO.from(service.definirTemperatura(dto.temperatura()));
    }
}
