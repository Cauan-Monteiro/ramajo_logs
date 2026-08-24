package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.ClienteDtos.ClienteDTO;
import com.ramajo.logs.system.dtos.ClienteDtos.SincronizarClienteDTO;
import com.ramajo.logs.system.services.ClienteService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService service;

    public ClienteController(ClienteService service) {
        this.service = service;
    }

    // Cliente vem do sistema externo com id próprio: upsert idempotente (PUT),
    // não POST. Chamar de novo com o mesmo id só atualiza o nome.
    @PutMapping("/{id}")
    public ClienteDTO sincronizar(
            @PathVariable Long id, @Valid @RequestBody SincronizarClienteDTO dto) {
        return ClienteDTO.from(service.sincronizar(id, dto.nome()));
    }

    @GetMapping
    public List<ClienteDTO> listar() {
        return service.listar().stream().map(ClienteDTO::from).toList();
    }

    @GetMapping("/{id}")
    public ClienteDTO buscar(@PathVariable Long id) {
        return ClienteDTO.from(service.buscar(id));
    }
}
