package com.ramajo.logs.system.services;

import java.math.BigDecimal;
import java.util.List;

import com.ramajo.logs.system.entities.ConfigDesidrogenizacao;
import com.ramajo.logs.system.entities.Desidrogenizacao;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.exceptions.DesidrogenizacaoInativaException;
import com.ramajo.logs.system.exceptions.OperadorInativoException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.RecursoNaoEncontradoException;
import com.ramajo.logs.system.repositories.ConfigDesidrogenizacaoRepository;
import com.ramajo.logs.system.repositories.DesidrogenizacaoRepository;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemDesidrogenizacaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de desidrogenizações, a temperatura do forno e a aplicação de uma
 * receita a uma OS.
 *
 * As três coisas moram no mesmo service porque aplicar depende das outras duas:
 * a linha gravada copia a duração do catálogo e a temperatura da configuração,
 * e essa cópia tem que sair de uma leitura consistente.
 */
@Service
public class DesidrogenizacaoService {

    private final DesidrogenizacaoRepository desidroRepo;
    private final ConfigDesidrogenizacaoRepository configRepo;
    private final OrdemDesidrogenizacaoRepository aplicacaoRepo;
    private final OrdemServicoRepository osRepo;
    private final OperadorRepository operadorRepo;

    public DesidrogenizacaoService(DesidrogenizacaoRepository desidroRepo,
                                   ConfigDesidrogenizacaoRepository configRepo,
                                   OrdemDesidrogenizacaoRepository aplicacaoRepo,
                                   OrdemServicoRepository osRepo,
                                   OperadorRepository operadorRepo) {
        this.desidroRepo = desidroRepo;
        this.configRepo = configRepo;
        this.aplicacaoRepo = aplicacaoRepo;
        this.osRepo = osRepo;
        this.operadorRepo = operadorRepo;
    }

    // CATÁLOGO  ==============================================================

    /**
     * `arquivadas = true` devolve tudo. Diferente de ProcessoService.listar(),
     * o padrão aqui é só o que está ativo: cada aplicação guarda o próprio
     * snapshot de duração e temperatura, então o front não precisa do cadastro
     * arquivado para ler o histórico.
     */
    @Transactional(readOnly = true)
    public List<Desidrogenizacao> listar(boolean arquivadas) {
        return arquivadas
                ? desidroRepo.findAllByOrderByNomeAsc()
                : desidroRepo.findByAtivoTrueOrderByNomeAsc();
    }

    @Transactional(readOnly = true)
    public Desidrogenizacao buscar(Long id) {
        return desidroRepo.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Desidrogenização", id));
    }

    @Transactional
    public Desidrogenizacao criar(String nome, Integer duracaoMin, String observacao) {
        Desidrogenizacao d = new Desidrogenizacao(nome, duracaoMin);
        d.setObservacao(observacao);
        return desidroRepo.save(d);
    }

    @Transactional
    public Desidrogenizacao atualizar(Long id, String nome, Integer duracaoMin,
                                      String observacao) {
        Desidrogenizacao d = buscar(id);
        d.setNome(nome);
        d.setDuracaoMin(duracaoMin);
        d.setObservacao(observacao);
        return d; // dirty checking
    }

    /**
     * "Excluir" é arquivar. Não existe aqui a recusa por uso que
     * ProcessoService.arquivar faz: nenhuma configuração viva aponta para uma
     * desidrogenização, e as aplicações passadas carregam o próprio snapshot —
     * arquivar não deixa nada quebrado para trás.
     */
    @Transactional
    public void arquivar(Long id) {
        Desidrogenizacao d = buscar(id);
        d.setAtivo(false); // idempotente
    }

    @Transactional
    public Desidrogenizacao reativar(Long id) {
        Desidrogenizacao d = buscar(id);
        d.setAtivo(true);
        return d;
    }

    // TEMPERATURA  ===========================================================

    @Transactional(readOnly = true)
    public ConfigDesidrogenizacao configuracao() {
        return carregarConfig();
    }

    @Transactional
    public ConfigDesidrogenizacao definirTemperatura(BigDecimal temperatura) {
        ConfigDesidrogenizacao cfg = carregarConfig();
        cfg.setTemperatura(temperatura); // dirty checking
        return cfg;
    }

    // APLICAÇÃO  =============================================================

    /**
     * Aplica uma receita do catálogo a uma OS.
     *
     * A OS precisa estar em circulação pela mesma razão de todas as outras
     * rotas de escrita (ver carregarAberta em OrdemServicoService): registrar
     * trabalho numa OS finalizada ou cancelada é sujar o relatório dela depois
     * do fato.
     */
    @Transactional
    public OrdemDesidrogenizacao aplicar(Long osId, Long desidrogenizacaoId, Long operadorId) {
        OrdemServico os = osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));
        if (os.isFinalizada() || os.isCancelada()) {
            throw new OrdemForaDeCirculacaoException(osId);
        }

        Operador operador = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Operador", operadorId));
        if (!operador.isAtivo()) {
            throw new OperadorInativoException(operadorId);
        }

        Desidrogenizacao receita = buscar(desidrogenizacaoId);
        // Arquivada não volta pela porta dos fundos — mesma guarda que
        // ProcessoInicialService faz com processo inativo.
        if (!receita.isAtivo()) {
            throw new DesidrogenizacaoInativaException(receita.getId(), receita.getNome());
        }

        OrdemDesidrogenizacao aplicacao = new OrdemDesidrogenizacao(
                os, receita, operador, carregarConfig().getTemperatura());

        // saveAndFlush para o INSERT sair agora: é ele que preenche iniciadaEm
        // (clock_timestamp) e finalizadaEm (trigger trg_odesidro_fim), valores
        // que o @Generated relê em seguida. Com save() simples, o DTO da
        // resposta sairia com os dois nulos.
        aplicacaoRepo.saveAndFlush(aplicacao);

        // O lado dono da relação é a aplicação, então isto não gera insert —
        // só evita que o detalhe da OS, na mesma sessão, reporte a lista antiga.
        os.getDesidrogenizacoes().add(aplicacao);
        return aplicacao;
    }

    @Transactional(readOnly = true)
    public List<OrdemDesidrogenizacao> daOrdem(Long osId) {
        return aplicacaoRepo.buscarDaOrdem(osId);
    }

    /**
     * As desidrogenizações das OS ainda em produção, para o indicativo do
     * Dashboard. Devolve TODAS elas, inclusive as que já passaram do horário —
     * quem decide o que ainda merece aparecer na barra é a tela, que é onde
     * mora a escala de cores (ver web/src/domain/desidro.ts).
     */
    @Transactional(readOnly = true)
    public List<OrdemDesidrogenizacao> emAndamento() {
        return aplicacaoRepo.buscarDeOrdensEmProcesso();
    }

    /**
     * A linha 1 é semeada pela V10 e o CHECK da PK impede que exista outra. Se
     * sumiu, o banco está fora do estado que as migrations garantem — um 404
     * explícito diz isso melhor do que um NoSuchElementException.
     */
    private ConfigDesidrogenizacao carregarConfig() {
        return configRepo.findById(ConfigDesidrogenizacao.ID)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Configuração de desidrogenização", ConfigDesidrogenizacao.ID));
    }
}
