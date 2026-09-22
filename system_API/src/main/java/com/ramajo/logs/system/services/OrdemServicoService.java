package com.ramajo.logs.system.services;

import com.ramajo.logs.system.entities.*;
import com.ramajo.logs.system.enums.CampoAlterado;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.exceptions.*;
import com.ramajo.logs.system.repositories.*;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final OrdemAlteracaoRepository alteracaoRepo;
    private final OrdemAvaliacaoService avaliacaoService;

    // Fallback do processo inicial, usado só quando a posição não tem linha em
    // posicao_processo_inicial (V7) — o valor global que valia antes dela.
    // Campo NÃO-final de propósito: @RequiredArgsConstructor só injeta os
    // finais, então o @Value continua valendo sem alterar o construtor gerado.
    @Value("${app.processo-inicial-id:0}")
    private Long processoInicialId;

    // Teto de OS acopladas a uma mesma carga. Não há limite físico exato — é
    // guarda contra requisição absurda numa API sem autenticação. Ver
    // acoplarNaCarga.
    private static final int MAX_OS_ACOPLADAS = 5;

    // O `motivo` da linha de histórico de adicionarPosicao. A coluna é NOT NULL
    // (V14) e a rota não pede motivo ao operador — acrescentar setor é fato do
    // chão, não correção de engano. Texto fixo para o histórico dizer QUAL das
    // duas origens escreveu a linha.
    private static final String MOTIVO_SETOR_ACRESCENTADO = "Setor acrescentado na operação.";

    /**
     * OS recém-criada mais os passos que nasceram com ela — o controller
     * precisa dos dois para montar a resposta, e devolver a lista explícita
     * evita depender do estado da coleção `logs` da OS.
     *
     * `vinculada` distingue os dois desfechos de criar(): a OS é nova (false)
     * ou é uma que já existia com aquele Nº naquele setor, à qual as cargas
     * foram apenas vinculadas (true). O controller lê isto para responder 201
     * ou 200 — ver criar().
     */
    public record OrdemCriada(OrdemServico ordem, List<Log> logsIniciados, boolean vinculada) {
    }

    /**
     * O lote recém-aberto pela reabertura e as cargas que a expedição soltou e
     * ainda podem voltar. As cargas são SUGESTÃO — nenhuma foi revinculada;
     * quem as vincula é o operador, no modal de vínculo. Ver reabrir().
     */
    public record Reabertura(Lote lote, List<Carga> cargasSugeridas) {
    }

    // CRIAÇÃO  ===============================================================================
    /**
     * Cria a OS e, se `cargaIds` vier preenchido, vincula cada carga e abre
     * para ela um passo no processo inicial DO SETOR da OS — tudo numa
     * transação só. É o que torna a criação atômica: qualquer carga inválida
     * derruba a operação inteira, em vez de deixar uma OS meio-montada que
     * ninguém consegue desfazer (não existe endpoint de desvincular carga nem
     * de apagar OS).
     *
     * `acopladasPorCarga` diz, por carga, quais OS JÁ ABERTAS pegam carona
     * nela: as peças delas entram no mesmo tanque. Aplicado no fim, quando
     * todo vínculo existe — e dentro da mesma transação, então um par inválido
     * derruba a OS inteira, como já acontece com uma carga inválida.
     *
     * O Nº do ERP (`idExterno`) NÃO é mais único em absoluto (V20): é único
     * por POSIÇÃO. Daí as três vias desta rota, decididas aqui e não no front:
     *
     *  - Nº já existe NESTE setor  -> nada nasce; as cargas são vinculadas
     *    àquela OS, como faria POST /ordens/{id}/cargas. É o Nº 42 voltando
     *    com mais peças para o mesmo sítio.
     *  - Nº já existe em OUTRO setor -> nasce uma OS nova. São duas ordens
     *    paralelas que só partilham o número — a V19 admitiu a ordem partida
     *    entre setores, e esta é a porta por onde ela entra.
     *  - Nº livre (ou nulo) -> nasce uma OS nova, como sempre.
     *
     * Atravessando as três: o Nº é de UM cliente. Qualquer homônima de outro
     * dono derruba a rota, seja qual for o setor — as irmãs são a mesma ordem
     * do ERP partida, não ordens distintas que calharam no mesmo número.
     *
     * Note o que a primeira via implica: o Nº nunca é REUTILIZADO. Se a OS 42
     * daquele setor já foi expedida ou cancelada, criar 42 ali de novo cai em
     * carregarAberta() e é recusado — não vira OS nova.
     */
    @Transactional
    public OrdemCriada criar(Long clienteId, Long operadorId, Long idExterno, Posicao posicao,
                             List<Long> cargaIds, Map<Long, List<Long>> acopladasPorCarga){

        Cliente cliente = clienteRepo.findById(clienteId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId));

        Operador operador = operadorRepo.findById(operadorId)
            .orElseThrow(() -> new RecursoNaoEncontradoException("Operador", operadorId));

        if (!operador.isAtivo()){
            throw new OperadorInativoException(operadorId);
        }

        // As homônimas: tudo o que já usa este Nº, em qualquer setor. Antes de
        // qualquer mutação, como o resto do service. Nº nulo (OS sem
        // conciliação com o ERP) nunca tem homônima — várias delas convivem.
        List<OrdemServico> mesmoNumero = idExterno == null
                ? List.of()
                : osRepo.findAllByIdExterno(idExterno);

        // O Nº do ERP é de UMA ordem, e uma ordem é de UM cliente — a V20
        // partiu a ordem por SETOR, não por cliente. As irmãs são a mesma
        // ordem com as peças em sítios diferentes, logo o mesmo dono. Esta
        // recusa vale para as três vias, e é por isso que está aqui em cima e
        // não dentro do vínculo.
        mesmoNumero.stream()
                .filter(outra -> !outra.getCliente().getId().equals(cliente.getId()))
                .findFirst()
                .ifPresent(outra -> {
                    throw new OrdemClienteDivergenteException(idExterno,
                            outra.getPosicoesOrdenadas(),
                            descrever(outra.getCliente()), descrever(cliente));
                });

        OrdemServico irma = mesmoNumero.stream()
                .filter(outra -> outra.rodaEm(posicao))
                .findFirst()
                .orElse(null);

        if (irma != null){
            return vincularNaIrma(irma, operador, cargaIds, acopladasPorCarga);
        }

        OrdemServico os = new OrdemServico(idExterno ,cliente, posicao);
        os.setIniciadaPor(operador);

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
            return new OrdemCriada(salva, List.of(), false);
        }

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
            exigirPosicaoAutorizada(carga, salva);

            carga.setOrdemAtual(salva);

            // Mesma razão do add() nos lotes: o lado dono da relação é a
            // Carga, então sem isto o DTO da resposta reportaria a OS com
            // zero cargas. Não gera insert nenhum.
            salva.getCargas().add(carga);

            // O processo inicial é o DO SETOR DA CARGA, resolvido por carga e
            // não uma vez para a OS toda: é na carga que o trabalho acontece.
            // Hoje dá no mesmo — exigirPosicaoAutorizada acabou de garantir que os
            // dois setores são o mesmo —, mas é a leitura que continua certa
            // quando uma OS puder rodar em mais de um setor.
            //
            // abrirLog revalida vínculo/carga ativa/operador ativo. A checagem
            // de vínculo enxerga o setOrdemAtual acima porque é a mesma sessão.
            logs.add(abrirLog(salva, carga, processoInicial(carga.getPosicao()), operador));
        }

        // Depois do laço, não dentro dele: acoplarNaCarga exige a carga já
        // vinculada, e o passo inicial já aberto é onde a carona entra. Uma
        // carga que não está nesta OS é recusada lá com CARGA_NAO_VINCULADA.
        aplicarAcoplamentos(acopladasPorCarga, operador);

        return new OrdemCriada(salva, logs, false);

    }

    /**
     * A via do Nº que já existe NESTE setor: nada nasce — nem OS, nem lote. As
     * cargas entram na ordem que já estava lá e o resultado é indistinguível de
     * ter chamado vincularCarga() uma vez por carga.
     *
     * Por isso usa vincularNaOs() e não o laço de criar(): aquele recusa
     * qualquer carga já vinculada, porque uma OS recém-nascida não pode ter
     * cargas; aqui a OS é velha, e uma carga que já está nela é repetição
     * inofensiva do operador, não conflito.
     *
     * carregarAberta() é o que recusa o Nº de uma OS já expedida ou cancelada:
     * ele não volta a ser criável neste setor.
     *
     * O cliente do corpo não é conferido aqui: criar() já recusou qualquer
     * homônima de outro dono antes de escolher a irmã, e esta é uma delas.
     */
    private OrdemCriada vincularNaIrma(OrdemServico irma, Operador operador,
                                       List<Long> cargaIds,
                                       Map<Long, List<Long>> acopladasPorCarga){
        OrdemServico os = carregarAberta(irma.getId());

        List<Log> logs = new ArrayList<>();

        if (cargaIds != null){
            // LinkedHashSet pela mesma razão de criar(): id repetido no corpo
            // não pode abrir dois passos para a mesma carga.
            for (Long cargaId : new LinkedHashSet<>(cargaIds)){
                logs.add(vincularNaOs(os, cargaId, operador));
            }
        }

        aplicarAcoplamentos(acopladasPorCarga, operador);

        return new OrdemCriada(os, logs, true);
    }

    /**
     * Aplica um mapa carga -> OS caronas, ignorando entradas vazias. Serve à
     * criação da OS e ao vínculo de carga: nos dois casos a composição chega
     * junto com o vínculo, e é uma chamada a acoplarNaCarga por par.
     *
     * `op` é quem faz o vínculo, e é em nome dele que os passos abertos das
     * caronas fecham lá dentro.
     */
    private void aplicarAcoplamentos(Map<Long, List<Long>> acopladasPorCarga, Operador op){
        if (acopladasPorCarga == null) return;

        acopladasPorCarga.forEach((cargaId, osIds) -> {
            if (osIds == null) return;
            for (Long osId : new LinkedHashSet<>(osIds)){
                acoplar(cargaId, osId, op);
            }
        });
    }

    // CARGAS: LIBERAÇÃO  =====================================================
    /**
     * Fecha o passo aberto de cada carga listada e a devolve ao pool de livres
     * (`ordemAtual = null`), **desfazendo o acoplamento**: as OS que pegavam
     * carona saem da carga junto com a etapa. A OS continua aberta e **o lote
     * não muda**.
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
        Operador op = exigirOperadorAtivo(operadorId);

        // Um instante só para todos os passos: são o mesmo evento, e datas
        // diferentes sujariam o relatório de duração.
        liberar(osId, cargaIds, Instant.now(), op);
    }

    /**
     * O laço de liberação em si, compartilhado com finalizarLote(). `cargaIds`
     * nulo é no-op — a chamada "só avança o lote" passa por aqui sem efeito.
     *
     * `op` é quem assina o fecho dos passos: quem carregou em "encerrar
     * etapas" ou em "expedição parcial", não quem os tinha aberto.
     */
    private void liberar(Long osId, List<Long> cargaIds, Instant at, Operador op){
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
                    .ifPresent(aberto -> fecharPasso(aberto, at, op));

            // Encerrar a etapa desfaz a composição: as peças da carona saem
            // do tanque junto com as da titular, e a carga volta ao pool
            // vazia. É a saída normal do acoplamento — o × do detalhe da OS
            // continua a existir para desfazê-lo antes disso.
            soltarCarga(carga);
        }
    }

    /**
     * Soltar a carga de uma OS é desfazer a composição inteira. Encerrar a
     * etapa tira as peças da carona de dentro do tanque junto com as da
     * titular: a carga volta ao pool vazia, e quem a pegar a seguir começa do
     * zero, sem herdar carona de ninguém.
     *
     * Carga sem titular nunca carrega carona — é o mesmo invariante que
     * trg_coa_protege (V12) já exige de qualquer INSERT, agora também
     * respeitado por quem solta a carga.
     *
     * O histórico não se perde: log_ordens_acopladas é o snapshot do passo,
     * é append-only, e nada aqui lhe toca.
     */
    private void soltarCarga(Carga carga){
        carga.getOrdensAcopladas().clear();
        carga.setOrdemAtual(null);
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

        liberar(osId, cargaIds, at, op);

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
     *
     * `ordensAcopladasIds` são outras OS abertas cujas peças entram nesta
     * mesma carga. Declaradas aqui, valem para todos os passos que a carga
     * abrir enquanto estiver vinculada — não só para o inicial.
     */
    @Transactional
    public Log vincularCarga(Long osId, Long cargaId, Long operadorId,
                             List<Long> ordensAcopladasIds){
        OrdemServico os = carregarAberta(osId);

        Operador operador = responsavelDoVinculo(os, operadorId);

        // Sem add() em os.getCargas(): aqui a coleção é LAZY e a resposta é o
        // passo, não a OS — tocá-la só provocaria um SELECT inútil.
        Log passo = vincularNaOs(os, cargaId, operador);

        // Depois do passo estar aberto: acoplarNaCarga injeta a carona nele
        // além de a gravar na carga, e assim a etapa inicial já vale para
        // todas as OS envolvidas.
        aplicarAcoplamentos(Map.of(cargaId, ordensAcopladasIds == null
                ? List.<Long>of() : ordensAcopladasIds), operador);

        return passo;
    }

    /**
     * O vínculo em si: valida a carga, amarra-a à OS e abre o passo no
     * processo inicial do setor da OS. Partilhado por vincularCarga() e pela
     * troca de posição em corrigir(), que vincula as cargas do setor novo.
     */
    private Log vincularNaOs(OrdemServico os, Long cargaId, Operador operador){
        Carga carga = cargaRepo.findById(cargaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Carga", cargaId));

        if (!carga.isAtivo()){
            throw new CargaInativaException(cargaId);
        }
        if(carga.getOrdemAtual() != null && !carga.getOrdemAtual().getId().equals(os.getId())){
            throw new CargaIndisponivelException(cargaId, carga.getOrdemAtual().getId());
        }
        exigirPosicaoAutorizada(carga, os);

        // Defesa, não regra: soltarCarga() esvazia as caronas, então uma carga
        // do pool já chega aqui sem nenhuma. Sobra para linha legada gravada
        // antes de V18 — sem isto abrirLog acoplá-la-ia a si mesma e
        // trg_coa_protege recusaria o próximo acoplamento nesta carga.
        carga.getOrdensAcopladas().remove(os.getId());

        carga.setOrdemAtual(os);

        // O processo inicial é o DO SETOR DA CARGA — mesma leitura de criar().
        //
        // abrirLog revalida vínculo/carga ativa/operador ativo e enxerga o
        // setOrdemAtual acima porque é a mesma sessão.
        return abrirLog(os, carga, processoInicial(carga.getPosicao()), operador);
    }

    // CORREÇÃO (ADMIN)  ======================================================
    /**
     * Corrige Nº, cliente e/ou posição de uma OS aberta com dado errado. É a
     * saída para o engano de quem criou a OS — não há rota de apagar OS — e só
     * um ADMIN ativo a usa. Cada campo que muda vira uma linha em
     * `ordem_alteracoes`, com o motivo; sem registro, a correção não acontece.
     *
     * O corpo traz os três campos inteiros (não só os alterados): o que difere
     * do atual é o que muda. Nada diferente é recusado — uma correção vazia
     * deixaria no histórico uma entrada que não diz nada.
     *
     * Só OS em processo: expedida ou cancelada já está nos relatórios como
     * foi, e quem precisa mexer nela reabre primeiro.
     *
     * Mexer nas POSIÇÕES não é só trocar linhas: as cargas vinculadas e os
     * passos abertos dos setores que SAEM são desfeitos. Ver trocarPosicoes().
     * `cargaIds` são as cargas a vincular nessa mesma transação, e só contam
     * quando o conjunto muda.
     *
     * Esta é a única rota que REMOVE um setor, e é por isso que é de ADMIN e
     * exige motivo: remover apaga trabalho em curso. Acrescentar um setor não
     * apaga nada e por isso tem rota própria, sem gate — ver adicionarPosicao().
     *
     * Tudo numa transação: carga inválida, Nº repetido ou cliente inexistente
     * derrubam a correção inteira, histórico incluído.
     */
    @Transactional
    public OrdemServico corrigir(Long osId, Long operadorId, Long idExterno, Long clienteId,
                                 Set<Posicao> posicoes, List<Long> cargaIds, String motivo){
        OrdemServico os = carregarAberta(osId);

        Operador admin = exigirOperadorAtivo(operadorId);
        if (admin.getPermissao() != Permissao.ADMIN){
            throw new OperacaoRestritaException(operadorId);
        }

        // Uma OS sem setor não roda em lugar nenhum. A trigger da V19 garante
        // o mesmo no banco; aqui é para a recusa ter mensagem legível.
        if (posicoes == null || posicoes.isEmpty()){
            throw CorrecaoInvalidaException.semPosicao(osId);
        }

        boolean mudaNumero = !idExterno.equals(os.getIdExterno());
        boolean mudaCliente = !clienteId.equals(os.getCliente().getId());
        boolean mudaPosicao = !posicoes.equals(os.getPosicoes());

        if (!mudaNumero && !mudaCliente && !mudaPosicao){
            throw CorrecaoInvalidaException.semAlteracao(osId);
        }

        String porque = motivo.trim();

        // Todas as recusas que não dependem das cargas ANTES de qualquer
        // mutação, pela disciplina de sempre. As das cargas novas só aparecem
        // no vínculo, e aí quem desfaz é o rollback.
        Cliente novoCliente = mudaCliente
                ? clienteRepo.findById(clienteId)
                        .orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId))
                : null;

        // As homônimas depois da correção: as três mudanças mexem na relação
        // Nº <-> cliente <-> setor, então qualquer uma delas obriga a reler.
        if (mudaNumero || mudaCliente || mudaPosicao){
            Cliente dono = mudaCliente ? novoCliente : os.getCliente();

            List<OrdemServico> homonimas = osRepo.findAllByIdExterno(idExterno).stream()
                    .filter(outra -> !outra.getId().equals(osId))
                    .toList();

            // Mesma regra de criar(): o Nº é de um cliente só. Sem isto a
            // correção seria a porta dos fundos para o estado que a criação
            // recusa — e é ela, não a criação, que consegue mudar o cliente.
            homonimas.stream()
                    .filter(outra -> !outra.getCliente().getId().equals(dono.getId()))
                    .findFirst()
                    .ifPresent(outra -> {
                        throw new OrdemClienteDivergenteException(idExterno,
                                outra.getPosicoesOrdenadas(),
                                descrever(outra.getCliente()), descrever(dono));
                    });

            // Desde a V20 o Nº só colide por SETOR: outra OS com o mesmo número
            // em Automática não impede esta de ser a 42 de Oxidação. E por isso
            // a checagem não depende só do Nº — ACRESCENTAR um setor também
            // pode esbarrar na irmã que já roda nele.
            homonimas.stream()
                    .filter(outra -> outra.getPosicoes().stream().anyMatch(posicoes::contains))
                    .findFirst()
                    .ifPresent(outra -> {
                        throw new OrdemIdExternoExistente(idExterno, outra.getPosicoesOrdenadas());
                    });
        }

        List<OrdemAlteracao> alteracoes = new ArrayList<>();

        if (mudaNumero){
            alteracoes.add(new OrdemAlteracao(os, CampoAlterado.ID_EXTERNO,
                    os.getIdExterno() == null ? null : String.valueOf(os.getIdExterno()),
                    String.valueOf(idExterno), porque, admin));
            os.setIdExterno(idExterno);
        }

        if (mudaCliente){
            alteracoes.add(new OrdemAlteracao(os, CampoAlterado.CLIENTE,
                    descrever(os.getCliente()), descrever(novoCliente), porque, admin));
            os.setCliente(novoCliente);
        }

        if (mudaPosicao){
            alteracoes.addAll(trocarPosicoes(os, posicoes, cargaIds, admin, porque));
        }

        alteracaoRepo.saveAll(alteracoes);
        return os;
    }

    /**
     * A OS passa a rodar TAMBÉM neste setor. É a exceção que a V19 existe para
     * suportar: as peças de uma mesma ordem partidas entre dois setores,
     * produzindo em paralelo.
     *
     * Sem gate de ADMIN e sem motivo, ao contrário de corrigir(): acrescentar
     * um setor não desfaz nada. Nenhuma carga é solta, nenhum passo é
     * cancelado, nenhum lote é tocado — a OS só passa a aceitar cargas de mais
     * um sítio. É trabalho de chão, como vincular carga ou entregar, e quem
     * descobre a necessidade é quem está na linha.
     *
     * Também não vincula carga nenhuma: o vínculo continua a ser a rota de
     * sempre, que a partir daqui aceita as cargas do setor novo. Separar as
     * duas coisas é o que mantém a rota trivial de desfazer — uma posição
     * acrescentada por engano sai pela correção, sem ter deixado rasto no
     * chão de fábrica.
     *
     * Idempotente: a OS que já roda no setor volta como está. Dois terminais
     * podem tocar o botão ao mesmo tempo, como em acoplarNaCarga.
     *
     * Grava a linha de histórico mesmo não sendo correção de ADMIN. Sem ela, a
     * remoção deste setor mais tarde apareceria no histórico como a saída de
     * algo que nunca entrou.
     */
    @Transactional
    public OrdemServico adicionarPosicao(Long osId, Posicao nova, Long operadorId){
        OrdemServico os = carregarAberta(osId);
        Operador operador = exigirOperadorAtivo(operadorId);

        if (os.rodaEm(nova)){
            return os;
        }

        List<Posicao> antes = os.getPosicoesOrdenadas();
        os.getPosicoes().add(nova);

        alteracaoRepo.save(new OrdemAlteracao(os, CampoAlterado.POSICAO,
                rotulo(antes), rotulo(os.getPosicoesOrdenadas()),
                MOTIVO_SETOR_ACRESCENTADO, operador));

        return os;
    }

    /**
     * O conjunto de setores da OS muda. O que desfaz trabalho é a REMOÇÃO, e
     * ela é cirúrgica: só o que pertence aos setores que saíram.
     *
     * 1. Passos abertos EM CARGA DE SETOR REMOVIDO são CANCELADOS, não só
     *    fechados — foram registrados num setor onde a OS não devia estar, e
     *    `cancelado` é o que os tira da duração nos relatórios. Mesma regra de
     *    cancelar(). Passos já fechados ficam como estão: `logs` é append-only.
     * 2. As cargas DOS SETORES REMOVIDOS voltam ao pool, com as caronas delas
     *    (regra de liberar(): as peças das outras OS continuam no tanque).
     * 3. A OS deixa de ser carona nas cargas DOS SETORES REMOVIDOS. As cargas
     *    dos setores que ficam não são tocadas.
     * 4. As cargas novas entram pelo processo inicial do setor DELAS. O passo
     *    fica no nome de quem abriu a OS (quem de fato trabalha nela); sem
     *    ele, ou inativo, no do ADMIN.
     *
     * O ponto 1-3 é a diferença que importa numa OS de dois setores: tirar a
     * AUTOMATICA não pode parar o que está a correr no PENDURADO. Numa OS de um
     * setor só — o caso normal — "removidas" é o conjunto inteiro e o efeito é
     * exatamente o de antes.
     *
     * Devolve as linhas de histórico: POSICAO e, se alguma carga saiu ou
     * entrou, CARGAS.
     */
    private List<OrdemAlteracao> trocarPosicoes(OrdemServico os, Set<Posicao> novas,
                                                List<Long> cargaIds, Operador admin,
                                                String motivo){
        Long osId = os.getId();
        List<Posicao> antigas = os.getPosicoesOrdenadas();
        Instant at = Instant.now();

        // Os setores que SAEM. É este conjunto que autoriza cada desfazimento
        // abaixo — nada fora dele é tocado.
        Set<Posicao> removidas = new LinkedHashSet<>(os.getPosicoes());
        removidas.removeAll(novas);

        for (Log aberto : logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId)){
            if (!removidas.contains(aberto.getCarga().getPosicao())) continue;
            aberto.setCancelado(true);
            fecharPasso(aberto, at, admin);
        }
        // Os UPDATEs dos passos antes dos INSERTs dos novos: no flush
        // automático o Hibernate faria o contrário. Hoje as cargas são outras e
        // nenhum índice colide, mas é a mesma ordem de finalizarLote/abrirLog.
        logRepo.flush();

        List<Carga> soltas = os.getCargas().stream()
                .filter(c -> removidas.contains(c.getPosicao()))
                .toList();
        for (Carga c : soltas){
            soltarCarga(c);
        }
        // Lado inverso da relação: não gera SQL, só mantém a resposta do PUT
        // coerente com o que ficou gravado. removeAll e não clear(): as cargas
        // dos setores que ficam continuam vinculadas.
        os.getCargas().removeAll(soltas);

        cargaRepo.buscarAcoplamentosDe(osId).stream()
                .filter(c -> removidas.contains(c.getPosicao()))
                .forEach(c -> c.getOrdensAcopladas().remove(osId));

        // retainAll + addAll, e não clear + addAll: preserva as linhas dos
        // setores que ficam em vez de as apagar e reinserir iguais.
        os.getPosicoes().retainAll(novas);
        os.getPosicoes().addAll(novas);

        Operador iniciou = os.getIniciadaPor();
        Operador responsavel = iniciou != null && iniciou.isAtivo() ? iniciou : admin;

        List<Carga> vinculadas = new ArrayList<>();
        if (cargaIds != null){
            for (Long cargaId : new LinkedHashSet<>(cargaIds)){
                Carga carga = vincularNaOs(os, cargaId, responsavel).getCarga();
                vinculadas.add(carga);
                os.getCargas().add(carga);
            }
        }

        List<OrdemAlteracao> linhas = new ArrayList<>();
        linhas.add(new OrdemAlteracao(os, CampoAlterado.POSICAO,
                rotulo(antigas), rotulo(os.getPosicoesOrdenadas()), motivo, admin));
        if (!soltas.isEmpty() || !vinculadas.isEmpty()){
            linhas.add(new OrdemAlteracao(os, CampoAlterado.CARGAS,
                    nomes(soltas), nomes(vinculadas), motivo, admin));
        }
        return linhas;
    }

    /** "#12 ACME LTDA": o id é o que o ERP conhece, o nome é o que se lê. */
    private static String descrever(Cliente cliente){
        return "#" + cliente.getId() + " " + cliente.getNome();
    }

    /**
     * As posições como o histórico as grava: ordem canônica, separadas por
     * vírgula ("PENDURADO, AUTOMATICA"). Ordem fixa porque a linha de
     * histórico é comparada com a anterior por texto — um conjunto que se
     * imprime em ordem variável registaria mudanças que não houve.
     */
    private static String rotulo(List<Posicao> posicoes){
        return posicoes.stream().map(Posicao::name).collect(Collectors.joining(", "));
    }

    /** Nomes por ordem alfabética; lista vazia é null — "nenhuma", não "". */
    private static String nomes(List<Carga> cargas){
        if (cargas.isEmpty()) return null;
        return String.join(", ", cargas.stream().map(Carga::getNome).sorted().toList());
    }

    @Transactional(readOnly = true)
    public List<OrdemAlteracao> alteracoes(Long osId){
        buscar(osId);
        return alteracaoRepo.buscarDaOrdem(osId);
    }

    /**
     * Abre o passo. As OS acopladas NÃO vêm do chamador: são as da carga
     * (`Carga.ordensAcopladas`), declaradas quando a carga foi vinculada e
     * válidas enquanto ela lá estiver. É isto que faz o acoplamento sobreviver
     * à etapa — as peças da carona não saem do tanque quando o passo fecha.
     */
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
        // O processo tem de rodar onde a CARGA está, que é onde o passo vai de
        // facto acontecer — e não onde a OS está registada. Hoje é a mesma
        // coisa (o vínculo da carga exigiu setores iguais), mas ler da carga é
        // o que continua correto quando a OS rodar em mais de um setor: aí o
        // passo será legítimo se o processo servir o setor DAQUELA carga.
        if (!processo.getPosicoes().contains(carga.getPosicao())){
            throw new PosicaoIncompativelException(
                    processo.getId(), processo.getDescricao(), carga.getPosicao());
        }

        // Resolvido ANTES do auto-fechamento abaixo, junto das demais
        // verificações — e por simetria com elas, ainda que aqui nada possa
        // recusar: caronas caducas são limpas, não rejeitadas.
        Set<Long> acopladas = acopladasVigentes(carga);

        // A carga está num lugar por vez: iniciar o próximo passo é o que
        // encerra o anterior. Ninguém precisa fechá-lo à mão — e como isto
        // roda dentro da @Transactional de quem chamou, ou os dois acontecem
        // ou nenhum: a carga nunca fica sem passo no meio do caminho.
        // Todas as recusas ficam ACIMA daqui de propósito: não se encerra o
        // passo anterior de uma movimentação que vai ser rejeitada.
        logRepo.findByCargaIdAndFinalizadoEmIsNull(carga.getId()).ifPresent(anterior -> {
            // Fecha em nome de quem abre o passo NOVO: é quem está a mover a
            // carga, e é o gesto dele que encerra o anterior. fecharPasso trata
            // do clock skew (o relógio do JVM pode estar atrás do do Postgres,
            // que carimba iniciado_em).
            fecharPasso(anterior, Instant.now(), op);

            // O flush é OBRIGATÓRIO, não é otimização — mesmo motivo do
            // loteRepo.flush() em finalizarLote(). No flush automático o
            // Hibernate executa os INSERTs antes dos UPDATEs, então o passo
            // novo entraria com o anterior ainda aberto e
            // ux_logs_carga_aberto rejeitaria o INSERT.
            logRepo.flush();
        });

        Log passo = new Log(os, op, carga, processo);
        passo.getOrdensAcopladas().addAll(acopladas);
        return logRepo.save(passo);
    }

    /**
     * As caronas da carga que ainda fazem sentido, limpando as que não fazem.
     *
     * A composição é declarada uma vez e lida a cada passo, então entre uma
     * coisa e outra uma carona pode ter sido expedida ou cancelada. Isso é
     * caducidade, não erro do operador: recusar a abertura do passo pararia o
     * chão de fábrica por causa de uma OS que outro terminal fechou. A linha
     * caduca sai da carga e ninguém precisa de saber.
     *
     * Só a posição e a circulação são revistas — o teto e a coerência "peças
     * num tanque só" foram verificados quando a carona foi declarada, em
     * acoplarNaCarga, e nada os pode ter invalidado desde então.
     */
    private Set<Long> acopladasVigentes(Carga carga){
        Set<Long> ids = carga.getOrdensAcopladas();
        if (ids.isEmpty()){
            return Set.of();
        }

        Set<Long> vigentes = new LinkedHashSet<>();

        for (Long id : new LinkedHashSet<>(ids)){
            OrdemServico acoplada = osRepo.findById(id).orElse(null);

            if (acoplada == null
                    || acoplada.isFinalizada()
                    || acoplada.isCancelada()
                    || !acoplada.rodaEm(carga.getPosicao())){
                ids.remove(id);
                continue;
            }
            vigentes.add(id);
        }

        return vigentes;
    }

    /**
     * As recusas de coerência física de UMA carona, no momento em que ela é
     * declarada. Uma de cada vez: acoplar é sempre uma OS que acabou de entrar
     * no tanque, nunca uma lista.
     *
     * O setor comparado é o da CARGA — o tanque físico onde as peças das duas
     * OS se encontram —, e não o da titular. Hoje são o mesmo valor (a carga só
     * está vinculada porque exigirPosicaoAutorizada a deixou entrar), mas é a carga
     * que descreve o lugar, e é essa leitura que sobrevive à OS multi-setor.
     */
    private void validarAcoplada(Carga carga, OrdemServico titular, Long id){
        if (id.equals(titular.getId())){
            throw AcoplamentoInvalidoException.aSiMesma(id);
        }

        // carregarAberta traz as recusas de sempre: 404 se não existe,
        // 409 se já foi expedida ou cancelada.
        OrdemServico acoplada = carregarAberta(id);

        if (!acoplada.rodaEm(carga.getPosicao())){
            throw AcoplamentoInvalidoException.posicaoDiferente(
                    acoplada.getId(), acoplada.getPosicoesOrdenadas(),
                    titular.getId(), carga.getPosicao());
        }
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

    /**
     * O setor da carga tem que estar entre os AUTORIZADOS da OS — senão o
     * passo é impossível: não há processo inicial daquele setor configurado
     * para esta ordem, e nada do que ela faça ali seria legítimo.
     *
     * É a única regra que liga a OS a um setor. Todo o resto (qual processo
     * inicial, que processo pode abrir passo, com quem se pode acoplar) lê a
     * posição da CARGA, porque é a carga que está fisicamente no sítio.
     */
    private void exigirPosicaoAutorizada(Carga carga, OrdemServico os){
        if (!os.rodaEm(carga.getPosicao())){
            throw new PosicaoIncompativelException(
                    carga.getId(), carga.getPosicao(),
                    os.getId(), os.getPosicoesOrdenadas());
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

    /**
     * O fecho à mão, da lista de etapas em andamento. `operadorId` é quem
     * carregou no botão — não se deduz do responsável da abertura, que muitas
     * vezes é outra pessoa (foi ele que abriu, não que fechou).
     */
    @Transactional
    public Log finalizarLog(UUID logId, Long operadorId){
        Log log = logRepo.findById(logId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Log", logId));

        if (log.getFinalizadoEm() != null){
            throw new PassoJaFinalizadoException(logId);
        }

        Operador op = exigirOperadorAtivo(operadorId);

        Instant at = Instant.now();
        if (at.isBefore(log.getIniciadoEm())){
            throw new IllegalArgumentException("O Log não pode ser finalizado agora, verifique os fusos!");
        }

        fecharPasso(log, at, op);
        return log;
    }

    /**
     * Acopla uma OS à CARGA: as peças dela estão no mesmo tanque que as da
     * titular (`carga.ordemAtual`).
     *
     * O vínculo dura o que a carga durar na OS, e não o que um passo durar. É
     * a diferença que motiva este modelo: quando a etapa fecha, as peças da
     * carona não saem do tanque — a etapa seguinte é dos mesmos donos, e
     * abrirLog lê a composição daqui em vez de a pedir de novo ao operador.
     *
     * Encerra, no mesmo instante, os passos abertos da PRÓPRIA carona: as
     * peças saíram da carga dela. A carga dela continua vinculada à sua OS,
     * vazia e aguardando etapa — devolvê-la ao pool é decisão do operador, em
     * "Encerrar etapas".
     *
     * Sem volta simétrica: `logs` é append-only, então desacoplar depois não
     * reabre o passo que foi fechado aqui.
     */
    @Transactional
    public Carga acoplarNaCarga(Long cargaId, Long osId, Long operadorId){
        return acoplar(cargaId, osId, exigirOperadorAtivo(operadorId));
    }

    /**
     * O acoplamento em si, com o operador já resolvido: é assim que a criação
     * da OS e o vínculo de carga o chamam, sem reabrir o cadastro do operador
     * a cada par.
     *
     * `op` assina o fecho dos passos da carona — o gesto de acoplar é dele.
     */
    private Carga acoplar(Long cargaId, Long osId, Operador op){
        Carga carga = cargaRepo.findById(cargaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Carga", cargaId));

        // Idempotente: dois terminais no mesmo tanque tocam o botão ao mesmo
        // tempo, e a segunda chamada só afirma o que já é verdade. Antes das
        // validações de propósito — repetir não pode fechar passo nenhum.
        if (carga.getOrdensAcopladas().contains(osId)){
            return carga;
        }

        if (!carga.isAtivo()){
            throw new CargaInativaException(cargaId);
        }

        // Carona pressupõe alguém a dar boleia. Sem titular não há tanque de
        // ninguém, e a linha ficaria órfã à espera do próximo vínculo.
        if (carga.getOrdemAtual() == null){
            throw new CargaNaoVinculadaException(cargaId);
        }

        OrdemServico titular = carga.getOrdemAtual();
        validarAcoplada(carga, titular, osId);

        int total = carga.getOrdensAcopladas().size() + 1;
        if (total > MAX_OS_ACOPLADAS){
            throw AcoplamentoInvalidoException.demais(total, MAX_OS_ACOPLADAS);
        }

        // Peças num tanque só POR SETOR: se ela já pega carona noutra carga do
        // MESMO setor, o pedido descreve duas coisas incompatíveis — as peças
        // não estão em dois tanques do mesmo sítio ao mesmo tempo.
        //
        // O filtro por posição não muda nada hoje (uma OS roda num setor só,
        // logo todas as cargas em que ela pega carona são desse setor). Ele
        // existe para que a regra continue a dizer o que quer dizer quando uma
        // OS rodar em dois setores: aí ela TEM peças em dois tanques, um em
        // cada lado da fábrica, e recusar o segundo seria recusar o facto.
        cargaRepo.buscarAcoplamentosDe(osId).stream()
                .filter(outra -> outra.getPosicao() == carga.getPosicao())
                .findFirst()
                .ifPresent(outra -> {
                    throw AcoplamentoInvalidoException.jaEmOutraCarga(osId, outra.getId());
                });

        // Só DEPOIS de todas as recusas — mesma disciplina de abrirLog: um
        // acoplamento que vai ser rejeitado não pode custar à carona o passo
        // que ela tem em curso.
        //
        // Fecham só os passos abertos da carona NO SETOR desta carga: as peças
        // dela saíram da carga própria daquele setor para entrarem nesta. O que
        // ela tenha a correr noutro setor não foi tocado por este acoplamento e
        // continua a correr. Hoje o filtro não exclui nada — a carona roda num
        // setor só —, mas sem ele uma OS de dois setores veria o passo do lado
        // de lá fechar sozinho ao acoplar deste lado.
        Instant at = Instant.now();
        logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId).stream()
                .filter(aberto -> aberto.getCarga().getPosicao() == carga.getPosicao())
                .forEach(aberto -> fecharPasso(aberto, at, op));

        carga.getOrdensAcopladas().add(osId);

        // A etapa que já está a correr nesta carga passa a valer para a
        // carona, sem ser reaberta nem substituída: abrir um passo novo só
        // para reescrever a composição cortaria a duração real em duas e
        // inventaria na linha do tempo um passo que ninguém executou.
        logRepo.findByCargaIdAndFinalizadoEmIsNull(cargaId)
                .filter(passo -> !passo.isCancelado())
                .ifPresent(passo -> passo.getOrdensAcopladas().add(osId));

        return carga;
    }

    /**
     * Desfaz um acoplamento: as peças daquela OS não estão nesta carga.
     *
     * Sai da carga e também do passo em curso — a composição de um passo
     * ABERTO ainda é corrigível, é a mesma regra que a trigger trg_loa_protege
     * (V11) garante no banco. Os passos já fechados ficam como estão: `logs` é
     * append-only justamente para que o registro do que aconteceu não seja
     * reescrito depois.
     */
    @Transactional
    public void desacoplarDaCarga(Long cargaId, Long osId){
        Carga carga = cargaRepo.findById(cargaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Carga", cargaId));

        // Não é 404: a carga existe e a OS também — o que não existe é o
        // vínculo entre as duas, e isso é pedido inválido, não recurso ausente.
        if (!carga.getOrdensAcopladas().remove(osId)){
            throw AcoplamentoInvalidoException.naoAcoplada(cargaId, osId);
        }

        logRepo.findByCargaIdAndFinalizadoEmIsNull(cargaId)
                .ifPresent(passo -> passo.getOrdensAcopladas().remove(osId));
    }

    /**
     * O ÚNICO lugar que fecha um passo. Carimba a hora e quem fechou — que
     * quase nunca é quem abriu: fecha a etapa seguinte, a liberação da carga,
     * o acoplamento, a expedição ou o cancelamento da OS, cada um em nome de
     * quem estava no terminal.
     *
     * Protege contra clock skew: relógio atrasado devolveria um `finalizadoEm`
     * anterior ao `iniciadoEm` e o ck_logs_janela recusaria o UPDATE. Mesmo
     * tratamento de abrirLog.
     */
    private void fecharPasso(Log log, Instant at, Operador por){
        log.setFinalizadoEm(at.isBefore(log.getIniciadoEm()) ? log.getIniciadoEm() : at);
        log.setFinalizadoPor(por);
    }

    @Transactional
    public void finalizar(Long osId, Long operadorId){
        finalizar(osId, operadorId, null);
    }

    /**
     * Expedição total, com a avaliação da inspeção final quando ela vem.
     *
     * `avaliacao` é opcional: nula, a OS expede sem avaliar, e uma avaliação
     * que já exista (feita pelo ADMIN com a OS em produção) continua valendo.
     * Preenchida, é gravada NA MESMA TRANSAÇÃO, em nome de quem expede — ponto
     * inválido derruba a expedição inteira, em vez de expedir sem a avaliação
     * que o operador achou que tinha salvo.
     */
    @Transactional
    public void finalizar(Long osId, Long operadorId, OrdemAvaliacaoService.Entrada avaliacao){
        OrdemServico os = carregarAberta(osId);

        Operador op = operadorRepo.findById(operadorId)
                .orElseThrow(()-> new RecursoNaoEncontradoException("Operador", operadorId));

        if (avaliacao != null){
            avaliacaoService.registrar(os, op, avaliacao);
        }

        Instant at = Instant.now();

        // Passo aberto não sobrevive à OS: além de sujar o histórico, com
        // ux_logs_carga_aberto ele impediria a carga — já liberada logo
        // abaixo — de iniciar qualquer passo futuro, em qualquer OS.
        for(Log aberto : logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(osId)){
            fecharPasso(aberto, at, op);
        }

        // Antes de soltar: quais cargas estavam aqui. É o único registo do
        // vínculo desfeito — sem ele a reabertura abre o modal de vínculo em
        // branco, e nem o histórico de passos o substitui (ver
        // OrdemServico.cargasExpedidas). clear() primeiro: uma expedição
        // anterior desta mesma OS não pode acumular com esta.
        os.getCargasExpedidas().clear();

        for(Carga c : os.getCargas()){
            os.getCargasExpedidas().add(c.getId());
            soltarCarga(c);
        }

        // O outro lado do acoplamento: a OS sai de cena também como carona, e
        // as peças dela não estão mais em tanque nenhum. acopladasVigentes()
        // já a ignoraria no próximo passo, mas até lá a linha órfã apareceria
        // no detalhe da carga como se ela continuasse lá dentro.
        cargaRepo.buscarAcoplamentosDe(osId)
                .forEach(c -> c.getOrdensAcopladas().remove(osId));

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

    /**
     * A entrega ao cliente: o passo DEPOIS da expedição.
     *
     * A expedição tira as peças da produção; a entrega tira-as da casa. São
     * dois eventos, e por isso dois carimbos — quem expediu não é
     * necessariamente quem entrega, nem no mesmo dia.
     *
     * Carimbo único: `jaEntregue` recusa a segunda chamada em vez de reescrever
     * a primeira, que perderia a hora e o nome de quem entregou de facto.
     *
     * Sem gate de ADMIN, ao contrário de corrigir(): entregar é trabalho de
     * chão, como abrir um passo ou expedir.
     *
     * O que a desfaz é a reabertura, que limpa o carimbo junto com o da
     * expedição — ver reabrir().
     */
    @Transactional
    public void entregar(Long osId, Long operadorId){
        // Sem carregarAberta(): aqui exige-se o oposto — a OS TEM de estar fora
        // de circulação, e expedida em vez de cancelada.
        OrdemServico os = buscar(osId);

        if (os.isCancelada())   throw EntregaInvalidaException.cancelada(osId);
        if (!os.isFinalizada()) throw EntregaInvalidaException.naoFinalizada(osId);
        if (os.isEntregue())    throw EntregaInvalidaException.jaEntregue(osId);

        Operador op = exigirOperadorAtivo(operadorId);

        os.setEntregueEm(Instant.now());
        os.setEntreguePor(op);
    }

    /**
     * Desfaz a expedição total: a OS volta a produzir num LOTE NOVO.
     *
     * O lote antigo não é reaberto — ele descreve uma produção que de facto
     * terminou, e `finalizado_em` dele é o que a auditoria e os relatórios
     * contam. Reabrir é abrir o número seguinte da sequência, exatamente como
     * a expedição parcial faz, com a diferença de que aqui não há lote corrente
     * a fechar antes.
     *
     * O lote nasce VAZIO, e continua a nascer: nenhuma carga é revinculada
     * aqui. O que esta rota devolve, além do lote, são as cargas que a
     * expedição soltou (`OrdemServico.cargasExpedidas`) e que ainda podem
     * voltar — é uma SUGESTÃO, que o front usa para abrir o modal de vínculo já
     * com elas marcadas. Quem vincula continua a ser o operador, pela rota de
     * vínculo, como numa OS qualquer: adivinhar por ele seria inventar
     * história, mas deixá-lo escolher às cegas entre todas as cargas livres do
     * setor era pior.
     *
     * A sugestão é filtrada, não validada: carga sucateada, tomada por outra OS
     * ou mudada de setor no entretanto sai da lista em silêncio. É caducidade,
     * não erro do operador — mesmo tratamento que acopladasVigentes() dá à
     * carona caduca.
     *
     * O lado CARONA do acoplamento não é sugerido: as linhas que `finalizar`
     * apagou em carga_ordens_acopladas dizem "esta OS pegava carona na carga de
     * outra", dependem de essa outra OS ainda estar aberta, e nunca produziram
     * linha no painel para esta OS de qualquer forma — a linha é da titular.
     * Quem precisar reacopla no detalhe da OS.
     *
     * O que não volta: `finalizada_em`/`finalizada_por_id` são limpos (é o que
     * marca a OS como concluída), então a data da expedição desfeita se perde.
     * O fecho do último lote fica como o vestígio dela. O carimbo de ENTREGA
     * segue o mesmo caminho, e pelo mesmo motivo da avaliação: descrevia uma
     * saída que deixou de ter acontecido — as peças estão de volta à produção.
     */
    @Transactional
    public Reabertura reabrir(Long osId, Long operadorId){
        // Sem carregarAberta(): é justamente o gate que recusa OS finalizada.
        OrdemServico os = buscar(osId);

        if (os.isCancelada()) throw ReaberturaInvalidaException.cancelada(osId);
        if (!os.isFinalizada()) throw ReaberturaInvalidaException.naoFinalizada(osId);
        // ux_lotes_os_aberto admite um lote aberto por OS; uma OS finalizada
        // não deveria ter nenhum, mas o INSERT abaixo falharia feio se tivesse.
        if (os.getLoteAberto() != null) throw ReaberturaInvalidaException.loteAberto(osId);

        // Nada é gravado com ele — o lote novo ainda não tem quem o feche, e o
        // vínculo das cargas ainda não aconteceu —, mas a rota não aceita
        // operador inexistente ou inativo, como as outras.
        exigirOperadorAtivo(operadorId);

        // A avaliação era da expedição que está a ser desfeita. Se sobrasse,
        // a próxima expedição sem avaliar mostraria a de uma produção anterior.
        avaliacaoService.descartar(osId);

        os.setFinalizadaEm(null);
        os.setFinalizadaPor(null);
        os.setEmProcesso(true);

        // A entrega era daquela expedição. Limpa junto — e ANTES de qualquer
        // flush, porque ck_os_entrega exige finalizada_em quando ela existe.
        os.setEntregueEm(null);
        os.setEntreguePor(null);

        // Lido ANTES do clear, e depois de todas as recusas: uma reabertura
        // que vá ser rejeitada não pode consumir o snapshot.
        List<Carga> sugeridas = cargasSugeridas(os);
        // Consumido: a sugestão vale para ESTA reabertura. Reabrir de novo sem
        // ter expedido no meio não reoferece o que o operador já descartou.
        os.getCargasExpedidas().clear();

        // A partir do ÚLTIMO lote, e não da contagem: numeração que tenha um
        // buraco continua a crescer em vez de colidir com ux_lotes_os_numero.
        List<Lote> anteriores = loteRepo.findByOrdemServicoIdOrderByNumeroAsc(osId);
        short proximo = anteriores.isEmpty()
                ? 1
                : (short)(anteriores.get(anteriores.size() - 1).getNumero() + 1);

        return new Reabertura(loteRepo.save(new Lote(os, proximo)), sugeridas);
    }

    /**
     * As cargas soltas pela expedição que ainda fazem sentido oferecer de volta.
     *
     * Entre a expedição e a reabertura o chão de fábrica andou: a carga pode ter
     * sido sucateada, pega por outra OS ou mudada de setor no Ajustes. Nenhuma
     * dessas é erro de quem reabre — a linha simplesmente sai da sugestão, do
     * mesmo modo que acopladasVigentes() limpa a carona caduca em vez de
     * recusar o passo.
     */
    private List<Carga> cargasSugeridas(OrdemServico os){
        List<Carga> sugeridas = new ArrayList<>();

        for (Long cargaId : os.getCargasExpedidas()){
            Carga carga = cargaRepo.findById(cargaId).orElse(null);

            if (carga == null
                    || !carga.isAtivo()
                    || carga.getOrdemAtual() != null
                    || !os.rodaEm(carga.getPosicao())){
                continue;
            }
            sugeridas.add(carga);
        }

        // Pela mesma ordem em que o painel e o modal de vínculo as mostram — o
        // conjunto de ids não tem ordem nenhuma, e uma sugestão que troca de
        // ordem a cada leitura confunde quem confere antes de confirmar.
        sugeridas.sort(Comparator.comparing(Carga::getNome));
        return sugeridas;
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
            fecharPasso(aberto, at, op);
        }

        for(Carga c : os.getCargas()){
            soltarCarga(c);
        }

        // O outro lado do acoplamento: a OS sai de cena também como carona, e
        // as peças dela não estão mais em tanque nenhum. acopladasVigentes()
        // já a ignoraria no próximo passo, mas até lá a linha órfã apareceria
        // no detalhe da carga como se ela continuasse lá dentro.
        cargaRepo.buscarAcoplamentosDe(osId)
                .forEach(c -> c.getOrdensAcopladas().remove(osId));

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

    /**
     * Passos da OS, incluindo aqueles em que ela pegou carona na carga de
     * outra (V11). Alimenta a tela; os relatórios usam as consultas de
     * relatório, que ficam na OS titular.
     */
    @Transactional(readOnly = true)
    public List<Log> historico(Long osId){
        return logRepo.buscarHistorico(osId);
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
        return osRepo.listarParaResumo();
    }
}
