package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.CargaDtos.CargaDTO;
import com.ramajo.logs.system.dtos.CargaDtos.CriarCargaDTO;
import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.services.CargaService;
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

@RestController
@RequestMapping("/api/cargas")
public class CargaController {

    private final CargaService service;

    public CargaController(CargaService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CargaDTO> criar(@Valid @RequestBody CriarCargaDTO dto) {
        Carga c = service.criar(dto.nome(), dto.tipo(), dto.posicao(), dto.tagId());
        return ResponseEntity
                .created(URI.create("/api/cargas/" + c.getId()))
                .body(CargaDTO.from(c));
    }

    @PutMapping("/{id}")
    public CargaDTO atualizar(@PathVariable Long id, @Valid @RequestBody CriarCargaDTO dto) {
        return CargaDTO.from(
                service.atualizar(id, dto.nome(), dto.tipo(), dto.posicao(), dto.tagId()));
    }

    @GetMapping
    public List<CargaDTO> listar(@RequestParam(defaultValue = "false") boolean disponiveis) {
        List<Carga> lista = disponiveis ? service.listarDisponiveis() : service.listar();
        return lista.stream().map(CargaDTO::from).toList();
    }

    @GetMapping("/{id}")
    public CargaDTO buscar(@PathVariable Long id) {
        return CargaDTO.from(service.buscar(id));
    }

    // Leitura de etiqueta/RFID: tag desconhecida é caso normal -> 404 limpo.
    @GetMapping("/por-tag/{tagId}")
    public ResponseEntity<CargaDTO> porTag(@PathVariable String tagId) {
        return service.buscarPorTag(tagId)
                .map(c -> ResponseEntity.ok(CargaDTO.from(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}") // desativação (soft-delete), não remoção física
    public ResponseEntity<Void> desativar(@PathVariable Long id) {
        service.desativar(id);
        return ResponseEntity.noContent().build();
    }
}
