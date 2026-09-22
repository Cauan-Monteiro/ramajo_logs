package com.ramajo.logs.system.services;

import com.ramajo.logs.system.dtos.OrdemDtos.OrdemAuditoriaDTO;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OrdemDesidrogenizacaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As leituras em LOTE da Visão Geral.
 *
 * A aba montava-se com dois GETs por OS — um de detalhe e um de histórico —
 * disparados de uma vez para toda ordem dos últimos 30 dias. Passava de cem
 * requisições numa base pequena, e o browser só abre seis conexões por origem:
 * a fila atrasava até o canal SSE. Aqui o mesmo conjunto sai em duas consultas
 * por bloco de ids.
 *
 * Fica fora do OrdemServicoService de propósito: aquele é o serviço das
 * transições (abrir passo, expedir, reabrir), e isto é só leitura.
 */
@Service
@RequiredArgsConstructor
public class AuditoriaService {

    /**
     * Postgres não aceita lista de parâmetros sem fim num `in`. Mesmo teto do
     * PlanilhaPeriodoService, que já varre centenas de OS por período.
     */
    private static final int LOTE_DE_IDS = 1000;

    private final OrdemServicoRepository osRepo;
    private final LogRepository logRepo;
    private final LoteRepository loteRepo;
    private final OrdemDesidrogenizacaoRepository desidroRepo;

    /**
     * O histórico de várias OS de uma vez, com a MESMA composição da rota
     * singular: os passos próprios da ordem mais aqueles em que ela pegou
     * carona na carga de outra (V11).
     *
     * A lista sai PLANA, e não agrupada por OS. Um passo de carona pertence a
     * duas ou três ordens ao mesmo tempo, e um mapa o repetiria uma vez por
     * ordem; quem recebe reexpande pelo `ordemServicoId` e pelo
     * `ordensAcopladas` que o LogDTO já carrega — exactamente o que o front já
     * faz para distinguir passo próprio de carona.
     *
     * As duas consultas não podem virar uma: `buscarParaRelatorioDeOrdens` fica
     * na OS titular (é o que impede um evento físico de ser contado duas vezes
     * nos relatórios) e `buscarAcopladasDeOrdens` traz o outro lado. Usar só a
     * primeira deixaria cair silenciosamente todo passo de carona, e o swimlane
     * da Visão Geral perderia as barras das ordens que andam de boleia.
     */
    @Transactional(readOnly = true)
    public List<Log> historicoDeOrdens(Collection<Long> ids) {
        List<Long> lista = List.copyOf(ids);
        if (lista.isEmpty()) {
            return List.of();   // `in ()` é SQL inválido — nem chega a consultar
        }

        // Por id: um passo de carona volta nas duas consultas, e volta uma vez
        // por bloco em que alguma das suas ordens caia.
        Map<UUID, Log> unicos = new LinkedHashMap<>();
        for (int i = 0; i < lista.size(); i += LOTE_DE_IDS) {
            List<Long> bloco = lista.subList(i, Math.min(i + LOTE_DE_IDS, lista.size()));
            for (Log l : logRepo.buscarParaRelatorioDeOrdens(bloco)) {
                unicos.putIfAbsent(l.getId(), l);
            }
            for (Log l : logRepo.buscarAcopladasDeOrdens(bloco)) {
                unicos.putIfAbsent(l.getId(), l);
            }
        }

        // A mesma ordem da rota singular. Quem reexpande por OS percorre esta
        // lista uma vez, e cada grupo sai já ordenado.
        List<Log> todos = new ArrayList<>(unicos.values());
        todos.sort(Comparator.comparing(Log::getIniciadoEm).thenComparing(Log::getId));
        return todos;
    }

    /**
     * O que a Visão Geral precisa de cada OS e o resumo não traz: quem abriu,
     * quem fechou, os lotes e as desidrogenizações.
     *
     * Três consultas por bloco, em vez das oito relações LAZY que o
     * OrdemDetalheDTO dispara por ordem.
     */
    @Transactional(readOnly = true)
    public List<OrdemAuditoriaDTO> auditoriaDeOrdens(Collection<Long> ids) {
        List<Long> lista = List.copyOf(ids);
        if (lista.isEmpty()) {
            return List.of();
        }

        List<OrdemAuditoriaDTO> saida = new ArrayList<>();
        for (int i = 0; i < lista.size(); i += LOTE_DE_IDS) {
            List<Long> bloco = lista.subList(i, Math.min(i + LOTE_DE_IDS, lista.size()));

            Map<Long, List<Lote>> lotes = new LinkedHashMap<>();
            for (Lote lo : loteRepo.buscarDeOrdens(bloco)) {
                lotes.computeIfAbsent(lo.getOrdemServico().getId(), k -> new ArrayList<>()).add(lo);
            }

            Map<Long, List<OrdemDesidrogenizacao>> desidros = new LinkedHashMap<>();
            for (OrdemDesidrogenizacao od : desidroRepo.buscarDeOrdens(bloco)) {
                desidros.computeIfAbsent(od.getOrdemServico().getId(), k -> new ArrayList<>())
                        .add(od);
            }

            for (OrdemServico os : osRepo.buscarParaAuditoria(bloco)) {
                saida.add(OrdemAuditoriaDTO.from(
                        os,
                        lotes.getOrDefault(os.getId(), List.of()),
                        desidros.getOrDefault(os.getId(), List.of())));
            }
        }
        return saida;
    }
}
