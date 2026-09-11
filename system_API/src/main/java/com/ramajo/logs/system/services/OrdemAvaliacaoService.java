package com.ramajo.logs.system.services;

import com.ramajo.logs.system.entities.ItemAvaliacao;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemAvaliacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.exceptions.OperacaoRestritaException;
import com.ramajo.logs.system.exceptions.OperadorInativoException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemAvaliacaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avaliação da inspeção final.
 *
 * Duas portas de entrada, com regras diferentes:
 *   - a EXPEDIÇÃO (OrdemServicoService.finalizar), onde qualquer operador
 *     avalia a OS que está expedindo — é o fluxo normal do chão de fábrica;
 *   - o AJUSTES ({@link #avaliarComoAdmin}), onde só ADMIN avalia ou refaz a
 *     avaliação de uma OS já expedida (ou ainda em produção) — é a correção.
 *
 * Nas duas, avaliar de novo substitui a anterior, e salvar grava a avaliação
 * como verificada. Não há histórico: a avaliação descreve o estado da
 * expedição corrente, e a reabertura a apaga.
 */
@Service
@RequiredArgsConstructor
public class OrdemAvaliacaoService {

    private final OrdemAvaliacaoRepository avaliacaoRepo;
    private final OrdemServicoRepository osRepo;
    private final OperadorRepository operadorRepo;

    /** O que chega da API, antes de validar cada ponto. */
    public record Entrada(Object visual, Object aderencia,
                          Object embalagem, Object camada, String observacao) {

        OrdemAvaliacao.Dados validar() {
            String obs = observacao == null ? null : observacao.trim();
            return new OrdemAvaliacao.Dados(
                    ItemAvaliacao.de("visual", visual),
                    ItemAvaliacao.de("aderencia", aderencia),
                    ItemAvaliacao.de("embalagem", embalagem),
                    ItemAvaliacao.de("camada", camada),
                    obs == null || obs.isEmpty() ? null : obs);
        }
    }

    @Transactional(readOnly = true)
    public Optional<OrdemAvaliacao> daOrdem(Long osId) {
        if (!osRepo.existsById(osId)) {
            throw new RecursoNaoEncontradoException("Ordem de Serviço", osId);
        }
        return avaliacaoRepo.buscarDaOrdem(osId);
    }

    /**
     * Avaliação pelo Ajustes. OS em produção ou expedida; cancelada não — não
     * vai ser expedida, não há o que inspecionar.
     */
    @Transactional
    public OrdemAvaliacao avaliarComoAdmin(Long osId, Long operadorId, Entrada entrada) {
        OrdemServico os = osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));
        if (os.isCancelada()) {
            throw new OrdemForaDeCirculacaoException(osId);
        }

        Operador admin = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Operador", operadorId));
        if (!admin.isAtivo()) {
            throw new OperadorInativoException(operadorId);
        }
        if (admin.getPermissao() != Permissao.ADMIN) {
            throw new OperacaoRestritaException(operadorId, "avaliar OS fora da expedição");
        }

        return registrar(os, admin, entrada);
    }

    /**
     * Grava a avaliação, criando ou substituindo. Não confere permissão nem o
     * estado da OS: quem chama já o fez (a expedição ou avaliarComoAdmin).
     *
     * saveAndFlush nos dois casos: o carimbo sai do banco (default no INSERT,
     * trigger no UPDATE) e o @Generated só o relê depois do SQL executado.
     */
    @Transactional
    public OrdemAvaliacao registrar(OrdemServico os, Operador avaliador, Entrada entrada) {
        OrdemAvaliacao.Dados dados = entrada.validar();

        OrdemAvaliacao avaliacao = avaliacaoRepo.buscarDaOrdem(os.getId())
                .map(existente -> {
                    existente.substituir(dados, avaliador);
                    return existente;
                })
                .orElseGet(() -> new OrdemAvaliacao(os, dados, avaliador));

        return avaliacaoRepo.saveAndFlush(avaliacao);
    }

    /** A reabertura desfaz a expedição, e a avaliação vai junto. */
    @Transactional
    public void descartar(Long osId) {
        avaliacaoRepo.deleteByOrdemServicoId(osId);
    }
}
