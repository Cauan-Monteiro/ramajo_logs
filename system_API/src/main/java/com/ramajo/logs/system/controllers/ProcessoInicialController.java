package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.ProcessoInicialDtos.DefinirProcessoInicialDTO;
import com.ramajo.logs.system.dtos.ProcessoInicialDtos.ProcessoInicialDTO;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.services.ProcessoInicialService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sem POST nem DELETE: o conjunto de posições é fixo (enum Posicao), só o
 * processo de cada uma muda. Mesma lógica do PUT-only de ClienteController.
 */
@RestController
@RequestMapping("/api/processos-iniciais")
public class ProcessoInicialController {

    private final ProcessoInicialService service;

    public ProcessoInicialController(ProcessoInicialService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProcessoInicialDTO> listar() {
        return service.listar().stream().map(ProcessoInicialDTO::from).toList();
    }

    @PutMapping("/{posicao}")
    public ProcessoInicialDTO definir(
            @PathVariable Posicao posicao, @Valid @RequestBody DefinirProcessoInicialDTO dto) {
        return ProcessoInicialDTO.from(service.definir(posicao, dto.processoId()));
    }
}
