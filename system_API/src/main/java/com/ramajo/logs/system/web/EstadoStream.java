package com.ramajo.logs.system.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Canal SSE por onde os terminais sao avisados de que a RevisaoEstado mudou.
 *
 * Substitui a sonda de 4 em 4 segundos: em vez de cada terminal perguntar ~900
 * vezes por hora se algo mudou, mantem uma conexao aberta e recebe um aviso
 * quando muda de facto. O aviso continua a ser so a marca -- quem recarrega
 * (e o que recarrega) e o cliente, exatamente como antes.
 *
 * SseEmitter usa o servlet assincrono: a thread do Tomcat e devolvida ao pool
 * depois do controller retornar. Cada terminal segura uma conexao, nao uma
 * thread.
 */
@Component
public class EstadoStream {

    /** Conexao reciclada de tempos a tempos; o EventSource reconecta sozinho. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** Dica de reconexao enviada ao browser (o `retry:` do protocolo). */
    private static final long RECONEXAO_MS = 3000L;

    private final RevisaoEstado revisao;
    private final List<SseEmitter> assinantes = new CopyOnWriteArrayList<>();

    /** Houve escrita desde a ultima publicacao? Lido pelo flush agendado. */
    private final AtomicBoolean sujo = new AtomicBoolean(false);

    public EstadoStream(RevisaoEstado revisao) {
        this.revisao = revisao;
    }

    /**
     * Abre uma conexao e manda de imediato a marca atual: e isto que
     * ressincroniza um terminal a cada (re)conexao, sem precisar de
     * Last-Event-ID nem de um GET extra a seguir.
     */
    public SseEmitter registrar() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        // Remover sempre nos tres caminhos de fim: completar, expirar e falhar.
        emitter.onCompletion(() -> assinantes.remove(emitter));
        emitter.onTimeout(() -> assinantes.remove(emitter));
        emitter.onError(e -> assinantes.remove(emitter));
        try {
            emitter.send(evento().reconnectTime(RECONEXAO_MS));
        } catch (IOException e) {
            // Cliente desistiu antes do primeiro byte: nao chega a entrar na lista.
            emitter.completeWithError(e);
            return emitter;
        }
        assinantes.add(emitter);
        return emitter;
    }

    /** Chamado pelo RevisaoFilter depois de toda escrita bem-sucedida. */
    public void marcarSujo() {
        sujo.set(true);
    }

    /**
     * Publica no maximo uma vez por janela. Duas razoes: tira a escrita para N
     * clientes do caminho da resposta da mutacao, e junta rajadas -- uma
     * expedicao parcial faz varias escritas seguidas e nao ha ganho nenhum em
     * mandar um aviso por cada uma, ja que todas levam a mesma recarga.
     */
    @Scheduled(fixedDelay = 250)
    public void publicarPendente() {
        if (!sujo.getAndSet(false)) return;
        difundir(evento());
    }

    /**
     * Comentario periodico. E o que impede nginx, firewall ou NAT de matarem
     * uma conexao ociosa, e o que faz o send falhar num socket ja morto -- sem
     * isto um terminal desligado ficaria na lista ate ao timeout.
     */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        difundir(SseEmitter.event().comment("hb"));
    }

    private SseEmitter.SseEventBuilder evento() {
        // JSON a mao: dois campos, e traze-los por aqui pouparia um ObjectMapper
        // so para isto. `instancia` e um UUID, entao nao ha o que escapar.
        String dados = "{\"instancia\":\"" + revisao.instancia()
                + "\",\"revisao\":" + revisao.atual() + "}";
        return SseEmitter.event().name("revisao").data(dados);
    }

    private void difundir(SseEmitter.SseEventBuilder evento) {
        for (SseEmitter emitter : assinantes) {
            try {
                emitter.send(evento);
            } catch (IOException | IllegalStateException e) {
                // Conexao morta ou ja fechada: sair da lista agora, sem esperar
                // o callback. completeWithError dispara o onError, que remove.
                assinantes.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }
}
