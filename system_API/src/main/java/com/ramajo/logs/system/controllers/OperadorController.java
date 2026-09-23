package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.OperadorDtos.CriarOperadorDTO;
import com.ramajo.logs.system.dtos.OperadorDtos.OperadorDTO;
import com.ramajo.logs.system.services.OperadorService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operadores")
public class OperadorController {

    private final OperadorService service;

    public OperadorController(OperadorService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OperadorDTO> criar(@Valid @RequestBody CriarOperadorDTO dto) {
        OperadorDTO o = service.criar(dto.nome(), dto.permissao(), dto.tagId());
        return ResponseEntity
                .created(URI.create("/api/operadores/" + o.id()))
                .body(o);
    }

    @PutMapping("/{id}")
    public OperadorDTO atualizar(@PathVariable Long id, @Valid @RequestBody CriarOperadorDTO dto) {
        return service.atualizar(id, dto.nome(), dto.permissao(), dto.tagId());
    }

    @GetMapping
    public List<OperadorDTO> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public OperadorDTO buscar(@PathVariable Long id) {
        return service.buscar(id);
    }

    // Identificação por crachá (login por RFID): ausência é caso normal -> 404.
    @GetMapping("/por-tag/{tagId}")
    public ResponseEntity<OperadorDTO> porTag(@PathVariable String tagId) {
        return service.buscarPorTag(tagId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reativar")
    public OperadorDTO reativar(@PathVariable Long id) {
        return service.reativar(id);
    }

    /**
     * Sem `definitivo`, soft-delete: o operador sai do início de turno e todo o
     * histórico que ele assinou continua legível. Com `definitivo=true`, apaga a
     * linha — e aí a API recusa com 409 quem já registrou passos, ordens ou
     * lotes (ver OperadorService.excluir).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remover(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean definitivo) {
        if (definitivo) {
            service.excluir(id);
        } else {
            service.desativar(id);
        }
        return ResponseEntity.noContent().build();
    }
}
