package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.ProcessoDtos.CriarProcessoDTO;
import com.ramajo.logs.system.dtos.ProcessoDtos.DefinirPosicoesDTO;
import com.ramajo.logs.system.dtos.ProcessoDtos.ProcessoDTO;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.services.ProcessoService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/processos")
public class ProcessoController {

    private final ProcessoService service;

    public ProcessoController(ProcessoService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProcessoDTO> criar(@Valid @RequestBody CriarProcessoDTO dto) {
        Processo p = service.criar(dto.descricao(), dto.etapa(), dto.tagId(), dto.posicoes());
        return ResponseEntity
                .created(URI.create("/api/processos/" + p.getId()))
                .body(ProcessoDTO.from(p));
    }

    @PutMapping("/{id}")
    public ProcessoDTO atualizar(@PathVariable Long id, @Valid @RequestBody CriarProcessoDTO dto) {
        return ProcessoDTO.from(
                service.atualizar(id, dto.descricao(), dto.etapa(), dto.tagId(), dto.posicoes()));
    }

    @GetMapping
    public List<ProcessoDTO> listar() {
        return service.listar().stream().map(ProcessoDTO::from).toList();
    }

    @GetMapping("/{id}")
    public ProcessoDTO buscar(@PathVariable Long id) {
        return ProcessoDTO.from(service.buscar(id));
    }

    // Substitui o conjunto de setores onde o processo pode ocorrer.
    @PutMapping("/{id}/posicoes")
    public ProcessoDTO definirPosicoes(
            @PathVariable Long id, @Valid @RequestBody DefinirPosicoesDTO dto) {
        return ProcessoDTO.from(service.definirPosicoes(id, dto.posicoes()));
    }

    /**
     * Arquivamento (soft-delete), não remoção física — igual ao DELETE de
     * CargaController. O histórico que aponta para o processo fica intacto.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> arquivar(@PathVariable Long id) {
        service.arquivar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reativar")
    public ProcessoDTO reativar(@PathVariable Long id) {
        return ProcessoDTO.from(service.reativar(id));
    }
}
