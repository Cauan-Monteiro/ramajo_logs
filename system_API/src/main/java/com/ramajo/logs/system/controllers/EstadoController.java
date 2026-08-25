package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.EstadoDtos.RevisaoDTO;
import com.ramajo.logs.system.web.RevisaoEstado;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonda de sincronizacao dos terminais: resposta minuscula, sem tocar no banco,
 * chamada de poucos em poucos segundos por cliente.
 */
@RestController
@RequestMapping("/api/estado")
public class EstadoController {

    private final RevisaoEstado revisao;

    public EstadoController(RevisaoEstado revisao) {
        this.revisao = revisao;
    }

    @GetMapping("/revisao")
    public ResponseEntity<RevisaoDTO> revisao() {
        // no-store: um proxy ou browser cacheando isto congelaria os terminais.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RevisaoDTO(revisao.instancia(), revisao.atual()));
    }
}
