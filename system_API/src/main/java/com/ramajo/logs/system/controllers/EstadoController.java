package com.ramajo.logs.system.controllers;

import com.ramajo.logs.system.dtos.EstadoDtos.RevisaoDTO;
import com.ramajo.logs.system.web.EstadoStream;
import com.ramajo.logs.system.web.RevisaoEstado;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Sincronizacao dos terminais. O caminho normal e /stream: o servidor avisa
 * quando o estado muda. /revisao continua de pe como sonda avulsa -- e o que o
 * cliente usa ao voltar para a aba e como rede de seguranca se o SSE cair.
 */
@RestController
@RequestMapping("/api/estado")
public class EstadoController {

    private final RevisaoEstado revisao;
    private final EstadoStream stream;

    public EstadoController(RevisaoEstado revisao, EstadoStream stream) {
        this.revisao = revisao;
        this.stream = stream;
    }

    @GetMapping("/revisao")
    public ResponseEntity<RevisaoDTO> revisao() {
        // no-store: um proxy ou browser cacheando isto congelaria os terminais.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RevisaoDTO(revisao.instancia(), revisao.atual()));
    }

    /**
     * Conexao longa, uma por terminal: recebe a marca atual de imediato e
     * depois um evento `revisao` a cada mudanca de estado.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse res) {
        // X-Accel-Buffering: o nginx honra este header e desliga o buffer desta
        // resposta. O nginx.conf ja o faz por location; isto cobre o dia em que
        // a API estiver atras de outro proxy.
        res.setHeader("X-Accel-Buffering", "no");
        res.setHeader("Cache-Control", "no-store");
        return stream.registrar();
    }
}
