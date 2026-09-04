package com.ramajo.logs.system.services;

import com.ramajo.logs.system.entities.*;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.*;
import com.ramajo.logs.system.repositories.*;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
public class OrdemServicoService {
    private final OrdemServicoRepository osRepo;
    private final ClienteRepository clienteRepo;
    private final OperadorRepository operadorRepo;
    private final CargaRepository cargaRepo;
    private final ProcessoRepository processoRepo;
    private final LogRepository logRepo;
    private final LoteRepository loteRepo;
    private final ProcessoInicialRepository processoInicialRepo;

    // Fallback do processo inicial, usado só quando a posição não tem linha em
    // posicao_processo_inicial (V7) — o valor global que valia antes dela.
    // Campo NÃO-final de propósito: @RequiredArgsConstructor só injeta os
    // finais, então o @Value continua valendo sem alterar o construtor gerado.
    @Value("${app.processo-inicial-id:0}")
    private Long processoInicialId;

    /**
     * OS recém-criada mais os passos que nasceram com ela — o controller
     * precisa dos dois para montar a resposta, e devolver a lista explícita
     * evita depender do estado da coleção `logs` da OS.
     */
    public record OrdemCriada(OrdemServico ordem, List<Log> logsIniciados) {
    }

    // CRIAÇÃO  ===============================================================================
    /**
     * Cria a OS e, se `cargaIds` vier preenchido, vincula cada carga e abre
     * para ela um passo no processo inicial DO SETOR da OS — tudo numa
     * transação só. É o que torna a criação atômica: qualquer carga inválida
     * derruba a operação inteira, em vez de deixar uma OS meio-montada que
     * ninguém consegue desfazer (não existe endpoint de desvincular carga nem
     * de apagar OS).
     */
    @Transactional
    public OrdemCriada criar(Long clienteId, Long operadorId, Long idExterno, Posicao posicao,
                             List<Long> cargaIds){

        Cliente cliente = clienteRepo.findById(clienteId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId));

        Operador operador = operadorRepo.findById(operadorId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Operador", operadorId));

        if (!operador.isAtivo()){
            throw new OperadorInativoException(operadorId);
        }

        OrdemServico os = new OrdemServico(idExterno ,cliente, posicao);
        os.setIniciadaPor(operador);

        if (osRepo.findByIdExterno(idExterno).isPresent()) {
            throw new OrdemIdExternoExistente(idExterno);
        }

        OrdemServico salva = osRepo.save(os);

        // Toda OS já começa no lote 1. Se ela for produzida de uma vez só,
        // termina com 1 lote; se for quebrada, os próximos vão sendo abertos
        // por finalizarLote(). Assim nunca existe OS sem lote corrente.
        Lote primeiro = loteRepo.save(new Lote(salva, (short) 1));

        // A coleção de uma entidade recém-persistida já nasce INICIALIZADA e
        // vazia — não é um proxy que vai buscar no banco. Sem este add, o DTO
        // da resposta do POST reportaria a OS com zero lotes. Como o lado dono
        // da relação é o Lote, isto não gera insert nenhum.
        salva.getLotes().add(primeiro);

        if (cargaIds == null || cargaIds.isEmpty()){
            return new OrdemCriada(salva, List.of());
        }

        Processo inicial = processoInicial(salva.getPosicao());

        List<Log> logs = new ArrayList<>();

        // LinkedHashSet: id repetido no corpo não pode abrir dois passos para
        // a mesma carga. Preserva a ordem em que o operador enviou.
        for (Long cargaId : new LinkedHashSet<>(cargaIds)){
            Carga carga = cargaRepo.findById(cargaId)
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Carga", cargaId));

            if (!carga.isAtivo()){
                throw new CargaInativaException(cargaId);
            }
            // Diferente de vincularCarga(): a OS acabou de nascer, então não
            // existe o caso "já vinculada a esta mesma OS" — qualquer vínculo
            // preexistente é conflito.
            if (carga.getOrdemAtual() != null){
                throw new CargaIndisponivelException(cargaId, carga.getOrdemAtual().getId());
            }
            exigirMesmaPosicao(carga, salva);

            carga.setOrdemAtual(salva);

            // Mesma razão do add() nos lotes: o lado dono da relação é a
            // Carga, então sem isto o DTO da resposta reportaria a OS com
            // zero cargas. Não gera insert nenhum.
            salva.getCargas().add(carga);

            // abrirLog revalida vínculo/carga ativa/operador ativo. A checagem
            // de vínculo enxerga o setOrdemAtual acima porque é a mesma sessão.
            logs.add(abrirLog(salva, carga, inicial, operador));
        }

        return new OrdemCriada(salva, logs);

    }

    // CARGAS: LIBERAÇÃO  =====================================================
    /**
     * Fecha o passo aberto de cada carga listada e a devolve ao pool de livres
     * (`ordemAtual = null`). A OS continua aberta e **o lote não muda**.
     *
     * É a rotina de chão de fábrica ("encerrar etapas"), separada de propósito
     * de finalizarLote(): virar o lote é decisão de expedição parcial, tomada
     * na Inspeção Final, não efeito colateral de fechar etapas em massa.
     *
     * Tudo na mesma transação — carga inválida na lista derruba a operação
     * inteira, sem cargas meio-liberadas.
     */
    @Transactional
    public void liberarCargas(Long osId, Long operadorId, List<Long> cargaIds){
        carregarAberta(osId);
        exigirOperadorAtivo(operadorId);

        // Um instante só para todos os passos: são o mesmo evento, e datas
        // diferentes sujariam o relatório de duração.
        liberar(osId, cargaIds, Instant.now());
    }

    /**
     * O laço de liberação em si, compartilhado com finalizarLote(). `cargaIds`
     * nulo é no-op — a chamada "só avança o lote" passa por aqui sem efeito.
     */
    private void liberar(Long osId, List<Long> cargaIds, Instant at){
        if (cargaIds == null) return;

        for (Long cargaId : new LinkedHashSet<>(cargaIds)){
            Carga carga = cargaRepo.findById(cargaId)
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Carga", cargaId));

            if (carga.getOrdemAtual() == null
                    || !carga.getOrdemAtual().getId().equals(osId)){
                throw new CargaNaoVinculadaException(cargaId, osId);
            }

            // Passo aberto não sobrevive à liberação: com
            // ux_logs_carga_aberto ele impediria a carga de iniciar
            // qualquer passo futuro, em qualquer OS. Mesma regra de
            // finalizar(); nem toda carga tem um, daí o ifPresent.
            logRepo.findByCargaIdAndFinalizadoEmIsNull(cargaId)
                    .ifPresent(aberto -> fecharPasso(aberto, at));

            carga.setOrdemAtual(null);
        }
    }

    private Operador exigirOperadorAtivo(Long operadorId){
        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Operador", operadorId));

        if (!op.isAtivo()){
            throw new OperadorInativoException(operadorId);
        }
        return op;
    }

    // LOTES  =================================================================
    /**
     * Expedição parcial: fecha o lote em produção e abre o seguinte — a OS
     * continua aberta. Retorna o lote recém-aberto, para o operador ver em
     * qual está agora. É o ÚNICO caminho que leva uma OS ao 2º lote.
     *
     * `cargaIds` é opcional e hoje vem sempre vazio: a Inspeção Final, de onde
     * a rota é chamada, só lista OS que já não têm carga vinculada. Preenchido,
     * cada carga listada tem o passo aberto fechado e sai da OS junto com o
     * lote. Tudo na mesma transação — carga inválida na lista derruba a
     * operação inteira, sem lote meio-fechado.
     */
    @Transactional
    public Lote finalizarLote(Long osId, Long operadorId, List<Long> cargaIds){
        OrdemServico os = carregarAberta(osId);

        Operador op = exigirOperadorAtivo(operadorId);

        // Um instante só para o lote e para os passos que fecham com ele: são
        // o mesmo evento, e datas diferentes sujariam o relatório de duração.
        Instant at = Instant.now();

        liberar(osId, cargaIds, at);

        Lote atual = loteRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Lote aberto da OS", osId));

        atual.setFinalizadoEm(at);
        atual.setFinalizadoPor(op);

        // O flush é OBRIGATÓRIO aqui, não é otimização. O id do lote é IDENTITY,
        // então o save() abaixo dispara o INSERT na hora; sem empurrar antes o
        // UPDATE que fecha o lote atual, o banco enxergaria dois lotes abertos
        // na mesma OS por um instante e ux_lotes_os_aberto rejeitaria o INSERT.
        loteRepo.flush();

        return loteRepo.save(new Lote(os, (short) (atual.getNumero() + 1)));
    }

    /**
     * Vincula a carga à OS e já abre o passo inicial dela — a mesma regra da
     * criação da OS: toda carga que entra numa ordem entra pelo processo
     * inicial configurado para o setor daquela OS.
     * Devolve o passo aberto. Tudo numa transação só: se qualquer validação
     * recusar, o vínculo também não fica gravado.
     *
     * `operadorId` é opcional; ausente, o responsável do passo é quem abriu a
     * OS.
     */
    @Transactional
    public Log vincularCarga(Long osId, Long cargaId, Long operadorId){
        OrdemServico os = carregarAberta(osId);

        Operador operador = responsavelDoVinculo(os, operadorId);

        Carga carga = cargaRepo.findById(cargaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Carga", cargaId));

        if (!carga.isAtivo()){
            throw new CargaInativaException(cargaId);
        }
        if(carga.getOrdemAtual() != null && !carga.getOrdemAtual().getId().equals(os.getId())){
            throw new CargaIndisponivelException(cargaId, carga.getOrdemAtual().getId());
        }
        exigirMesmaPosicao(carga, os);

        carga.setOrdemAtual(os);

        // abrirLog revalida vínculo/carga ativa/operador ativo e enxerga o
        // setOrdemAtual acima porque é a mesma sessão. Sem add() em
        // os.getCargas(): aqui a coleção é LAZY e a resposta é o passo, não a
        // OS — tocá-la só provocaria um SELECT inútil.
        return abrirLog(os, carga, processoInicial(os.getPosicao()), operador);
    }

    @Transactional
    public Log iniciarLog(Long osId, Long cargaId, Long processoId, Long responsavelId){
        OrdemServico os = carregarAberta(osId);

        Carga carga = cargaRepo.findById(cargaId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Carga", cargaId));

        Processo processo = processoRepo.findById(processoId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Processo", processoId));

        Operador op = operadorRepo.findById(responsavelId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Operador", responsavelId));

        return abrirLog(os, carga, processo, op);
    }

    /**
     * Mesma abertura de passo, mas identificando os três participantes pela tag
     * física (crachá do operador, etiqueta da carga e do processo). A OS ainda é
     * informada pela URL — use para o leitor posicionado numa OS já escolhida.
     */
    @Transactional
    public Log iniciarLogPorTag(Long osId, String cargaTagId, String processoTagId, String responsavelTagId){
        OrdemServico os = carregarAberta(osId);

        return abrirLog(os,
                cargaPorTag(cargaTagId),
                processoPorTag(processoTagId),
                operadorPorTag(responsavelTagId));
    }

    /**
     * Abertura 100% por tag: a OS sai do vínculo atual da carga (`ordemAtual`),
     * então o leitor não precisa saber em qual ordem está trabalhando. Se a
     * carga estiver livre não há OS a inferir — 422, não 404.
     */
    @Transactional
    public Log iniciarLogPorTag(String cargaTagId, String processoTagId, String responsavelTagId){
        Carga carga = cargaPorTag(cargaTagId);

        if (carga.getOrdemAtual() == null){
            throw new CargaNaoVinculadaException(carga.getId());
        }

        // Revalida o estado da OS (finalizada/cancelada) pelo caminho normal.
        OrdemServico os = carregarAberta(carga.getOrdemAtual().getId());

        return abrirLog(os, carga, processoPorTag(processoTagId), operadorPorTag(responsavelTagId));
    }

    /** Regras do passo, independentes de como carga/processo/operador chegaram. */
    private Log abrirLog(OrdemServico os, Carga carga, Processo processo, Operador op){
        if (carga.getOrdemAtual() == null || !carga.getOrdemAtual().getId().equals(os.getId())){
            throw new CargaNaoVinculadaException(carga.getId(), os.getId());
        }
        if (!carga.isAtivo()){
            throw new CargaInativaException(carga.getId());
        }
        if (!op.isAtivo()){
            throw new OperadorInativoException(op.getId());
        }

        // Processo arquivado saiu do catálogo em uso: o front nem o oferece,
        // mas a API não tem autenticação e a guarda de verdade é esta.
        if (!processo.isAtivo()){
            throw new ProcessoInativoException(processo.getId(), processo.getDescricao());
        }

        // Sem posição cadastrada o processo não roda em setor nenhum — é
        // cadastro incompleto, não incompatibilidade. Código de erro próprio
        // porque a ação corretiva é outra.
        if (processo.getPosicoes().isEmpty()){
            throw new PosicaoIncompativelException(processo.getId(), processo.getDescricao());
        }
        if (!processo.getPosicoes().contains(os.getPosicao())){
            throw new PosicaoIncompativelException(
                    processo.getId(), processo.getDescricao(), os.getPosicao());
        }

        // A carga está num lugar por vez: iniciar o próximo passo é o que
        // encerra o anterior. Ninguém precisa fechá-lo à mão — e como isto
        // roda dentro da @Transactional de quem chamou, ou os dois acontecem
        // ou nenhum: a carga nunca fica sem passo no meio do caminho.
        // Todas as recusas ficam ACIMA daqui de propósito: não se encerra o
        // passo anterior de uma movimentação que vai ser rejeitada.
        logRepo.findByCargaIdAndFinalizadoEmIsNull(carga.getId()).ifPresent(anterior -> {
            // O relógio do JVM pode estar atrás do relógio do Postgres, que é
            // quem carimba iniciado_em (clock_timestamp). Sem este piso,
            // ck_logs_janela rejeitaria finalizado_em < iniciado_em e a
            // movimentação morreria num 500. Duração zero é melhor que erro.
            Instant fim = Instant.now();
            anterior.setFinalizadoEm(
                    fim.isBefore(anterior.getIniciadoEm()) ? anterior.getIniciadoEm() : fim);

            // O flush é OBRIGATÓRIO, não é otimização — mesmo motivo do
            // loteRepo.flush() em finalizarLote(). No flush automático o
            // Hibernate executa os INSERTs antes dos UPDATEs, então o passo
            // novo entraria com o anterior ainda aberto e
            // ux_logs_carga_aberto rejeitaria o INSERT.
            logRepo.flush();
        });

        return logRepo.save(new Log(os, op, carga, processo));
    }

    /**
     * Quem responde pelo passo aberto no vínculo. Informar o operador é o
     * caminho correto — é quem está de fato movimentando a carga. Sem ele,
     * cai em quem abriu a OS: atribuição plausível e melhor do que recusar o
     * vínculo por um campo que o chamador pode não ter à mão.
     */
    private Operador responsavelDoVinculo(OrdemServico os, Long operadorId){
        if (operadorId != null){
            return operadorRepo.findById(operadorId)
                    .orElseThrow(() -> new RecursoNaoEncontradoException("Operador", operadorId));
        }

        Operador iniciou = os.getIniciadaPor();
        if (iniciou == null){
            // iniciada_por_id é nullable no schema: OS antiga (ou importada)
            // pode não ter operador. Aí não há de onde tirar o responsável.
            throw new RecursoNaoEncontradoException(
                    "A OS " + os.getId() + " não tem operador de abertura;"
                            + " informe operadorId para vincular a carga.");
        }
        return iniciou;
    }

    /**
     * O processo em que toda carga entra ao ser vinculada, por setor: cada
     * posição tem seu primeiro tanque, e a relação mora em
     * posicao_processo_inicial (V7), configurável por
     * PUT /api/processos-iniciais/{posicao}.
     *
     * Sem linha para a posição, cai no app.processo-inicial-id — o valor
     * global que valia antes da V7. É rede de segurança para banco que ainda
     * não migrou, não a fonte da verdade.
     */
    private Processo processoInicial(Posicao posicao){
        return processoInicialRepo.findById(posicao)
                .map(ProcessoInicial::getProcesso)
                .orElseGet(() -> processoRepo.findById(processoInicialId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException(
                                "Processo inicial da posição " + posicao
                                        + " (nem configurado, nem no fallback "
                                        + processoInicialId + ")")));
    }

    /** A carga tem que rodar no mesmo setor da OS — senão o passo é impossível. */
    private void exigirMesmaPosicao(Carga carga, OrdemServico os){
        if (carga.getPosicao() != os.getPosicao()){
            throw new PosicaoIncompativelException(
                    carga.getId(), carga.getPosicao(), os.getId(), os.getPosicao());
        }
    }

    private Carga cargaPorTag(String tagId){
        return cargaRepo.findByTagId(tagId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Carga com tag", tagId));
    }

    private Processo processoPorTag(String tagId){
        return processoRepo.findByTagId(tagId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Processo com tag", tagId));
    }

    private Operador operadorPorTag(String tagId){
        return operadorRepo.findByTagId(tagId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Operador com tag", tagId));
    }

    @Transactional
    public Log finalizarLog(UUID logId){
        Log log = logRepo.findById(logId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Log", logId));

        if (log.getFinalizadoEm() != null){
            throw new PassoJaFinalizadoException(logId);
        }

        Instant at = Instant.now();
        if (at.isBefore(log.getIniciadoEm())){
            throw new IllegalArgumentException("O Log não pode ser finalizado agora, verifique os fusos!");
        }

        log.setFinalizadoEm(at);
        return log;
    }

    /**
     * Fecha o intervalo de um passo protegendo contra clock skew: relógio
     * atrasado devolveria um `finalizadoEm` anterior ao `iniciadoEm` e o
     * ck_logs_janela recusaria o UPDATE. Mesmo tratamento de abrirLog.
     */
    private void fecharPasso(Log log, Instant at){
        log.setFinalizadoEm(at.isBefore(log.getIniciadoEm()) ? log.getIniciadoEm() : at);
    }

    @Transactional
    public void finalizar(Long osId, Long operadorId){
        OrdemServico os = carregarAberta(osId);

        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Operador", operadorId));

        Instant at = Instant.now();

        // Passo aberto não sobrevive à OS: além de sujar o histórico, com
        // ux_logs_carga_aberto ele impediria a carga — já liberada logo
        // abaixo — de iniciar qualquer passo futuro, em qualquer OS.
        for(Log aberto : logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId)){
            fecharPasso(aberto, at);
        }

        for(Carga c : os.getCargas()){
            c.setOrdemAtual(null);
        }

        // O lote em produção fecha junto, no mesmo instante da OS, e nenhum
        // outro é aberto. É o que faz a contagem bater com a intuição: fechar
        // 2 lotes e depois a OS resulta em 3 lotes, todos com data.
        loteRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId).ifPresent(lote -> {
            lote.setFinalizadoEm(at);
            lote.setFinalizadoPor(op);
        });

        os.setFinalizadaEm(at);
        os.setFinalizadaPor(op);
        os.setEmProcesso(false);
    }

    @Transactional
    public void cancelar(Long osId, Long operadorId){
        OrdemServico os = carregarAberta(osId);

        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Operador", operadorId));

        Instant at = Instant.now();

        // O passo não terminou, foi abortado junto com a OS — é a diferença
        // que `cancelado` registra. Fechar o intervalo também é obrigatório:
        // senão a carga fica com um passo aberto eterno.
        for(Log aberto : logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId)){
            aberto.setCancelado(true);
            fecharPasso(aberto, at);
        }

        for(Carga c : os.getCargas()){
            c.setOrdemAtual(null);
        }

        os.setCancelada(true);
        os.setEmProcesso(false);
    }

    @Transactional(readOnly = true)
    public List<OrdemServico> listarEmProcesso(){
        return osRepo.findByEmProcessoTrue();
    }

    @Transactional(readOnly = true)
    public List<Lote> lotes(Long osId){
        return loteRepo.findByOrdemServicoIdOrderByNumeroAsc(osId);
    }

    @Transactional(readOnly = true)
    public List<Log> historico(Long osId){
        return logRepo.findByOrdemServicoIdOrderByIniciadoEmAscIdAsc(osId);
    }

    private OrdemServico carregarAberta(Long osId){
        OrdemServico os = osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));

        if (os.isFinalizada() || os.isCancelada())
            throw new OrdemForaDeCirculacaoException(osId);
        return os;
    }

    @Transactional(readOnly = true)
    public OrdemServico buscar(Long osId) {
        return osRepo.findById(osId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem de Serviço", osId));
    }

    @Transactional(readOnly = true)
    public List<OrdemServico> listarTodas() {
        return osRepo.findAll();
    }
}
