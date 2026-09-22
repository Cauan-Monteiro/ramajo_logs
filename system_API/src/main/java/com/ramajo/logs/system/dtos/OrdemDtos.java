package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.OrdemDesidrogenizacaoDTO;
import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ramajo.logs.system.entities.OrdemAlteracao;
import com.ramajo.logs.system.entities.OrdemAvaliacao;
import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.dtos.CargaDtos.CargaDTO;
import com.ramajo.logs.system.enums.CampoAlterado;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.services.OrdemAvaliacaoService;
import com.ramajo.logs.system.services.OrdemServicoService.Reabertura;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DTOs de entrada e saída do fluxo de Ordem de Serviço e Log.
 *
 * Os métodos `from(...)` tocam relações LAZY (cliente, cargas, carga/processo/
 * responsavel do log). Devem ser chamados com a sessão de persistência aberta
 * — o que ocorre no controller graças ao open-in-view (ligado por padrão no
 * Spring Boot).
 *
 * Essa dependência é justamente o que deixa um N+1 passar calado: o lazy load
 * fora da transação vira query extra, sem erro nenhum. Para desligar o
 * open-in-view — o que a transforma em LazyInitializationException, alta e na
 * linha exata — há um plano com o inventário do que quebra em
 * `system_API/OPEN-IN-VIEW.md`.
 */
public final class OrdemDtos {

    private OrdemDtos() {
    }

    // ---------------------------------------------------------------- entrada
    /**
     * Uma OS que pega carona numa carga desta ordem: as peças dela entram no
     * mesmo tanque. Par, e não lista solta de OS, porque a pergunta é sempre
     * "em QUAL carga" — a OS pode estar a levar várias.
     */
    public record AcoplamentoDTO(
            @NotNull Long cargaId,
            @NotNull Long ordemServicoId) {
    }

    /**
     * `cargaIds` é opcional. Quando vem preenchido, a OS já nasce com essas
     * cargas vinculadas e com um passo aberto no processo inicial para cada
     * uma — tudo na mesma transação. Omitido (ou vazio), o comportamento é o
     * de sempre: só a OS e o lote 1.
     *
     * `acoplamentos` declara as OS que pegam carona nessas cargas. Vale
     * enquanto a carga estiver vinculada, não só para o passo inicial: cada
     * etapa que a carga abrir nasce com esta composição.
     */
    public record CriarOrdemDTO(
            @NotNull Long clienteId,
            @NotNull Long operadorId,
            Long idExterno,                 // opcional: conciliação com o ERP
            @NotNull Posicao posicao,
            List<@NotNull Long> cargaIds,
            List<@Valid AcoplamentoDTO> acoplamentos) {

        public List<AcoplamentoDTO> acoplamentos() {
            return acoplamentos == null ? List.of() : acoplamentos;
        }
    }

    /**
     * O vínculo já abre o passo inicial da carga, e todo passo tem um
     * responsável. `operadorId` é opcional: informe quem está de fato
     * executando; omitido, o passo fica no nome de quem abriu a OS.
     *
     * `ordensAcopladasIds` são as OS que pegam carona NESTA carga. Como não há
     * dúvida sobre qual carga é, aqui basta a lista — o par de AcoplamentoDTO
     * só faz falta na criação, onde há várias.
     */
    public record VincularCargaDTO(
            @NotNull Long cargaId,
            Long operadorId,
            List<@NotNull Long> ordensAcopladasIds) {

        public List<Long> ordensAcopladasIds() {
            return ordensAcopladasIds == null ? List.of() : ordensAcopladasIds;
        }
    }

    /**
     * Sem lista de acopladas: a composição do passo vem da CARGA, declarada
     * quando ela foi vinculada à OS. Quem abre a etapa não a redigita.
     */
    public record IniciarLogDTO(
            @NotNull Long cargaId,
            @NotNull Long processoId,
            @NotNull Long responsavelId) {
    }

    /**
     * Entrada do chão de fábrica: o operador só encosta os três crachás/etiquetas
     * no leitor, sem digitar id nenhum. As tags são únicas quando presentes
     * (índices parciais em operadores/processos/cargas), então cada uma resolve
     * para no máximo uma entidade.
     */
    public record IniciarLogPorTagDTO(
            @NotBlank @Size(max = 64) String cargaTagId,
            @NotBlank @Size(max = 64) String processoTagId,
            @NotBlank @Size(max = 64) String responsavelTagId) {
    }

    /**
     * `avaliacao` é opcional: ausente, a OS expede sem avaliar (e quem já
     * chamava só com `operadorId` continua a funcionar).
     */
    public record FinalizarOrdemDTO(@NotNull Long operadorId, @Valid AvaliacaoInputDTO avaliacao) {
    }

    /**
     * Os pontos da inspeção final. Cada um de `visual`, `aderencia`,
     * `embalagem` e `camada` é `null` (não avaliado), `true` (avaliado) ou o
     * texto da observação daquele ponto. Tipados como Object porque o JSON
     * mistura os três; a validação de cada valor é de ItemAvaliacao.de, que
     * responde 422.
     *
     * Sem `isVerificado`: salvar é concluir, e a avaliação é gravada verificada.
     */
    public record AvaliacaoInputDTO(
            Object visual, Object aderencia, Object embalagem, Object camada,
            @Size(max = 500) String observacao) {

        public OrdemAvaliacaoService.Entrada entrada() {
            return new OrdemAvaliacaoService.Entrada(
                    visual, aderencia, embalagem, camada, observacao);
        }
    }

    /** Avaliação pelo Ajustes (só ADMIN). */
    public record SalvarAvaliacaoDTO(@NotNull Long operadorId, @Valid @NotNull AvaliacaoInputDTO avaliacao) {
    }

    public record CancelarOrdemDTO(@NotNull Long operadorId) {
    }

    /**
     * Reabertura da OS expedida. Mesmo corpo do FinalizarOrdemDTO — a operação
     * é o inverso dele — e sem `cargaIds`: o lote novo nasce vazio, e as cargas
     * entram pela rota de vínculo, a partir da sugestão que a resposta traz
     * (ver ReaberturaDTO).
     */
    public record ReabrirOrdemDTO(@NotNull Long operadorId) {
    }

    /**
     * Entrega da OS expedida. Mesmo corpo da reabertura — nada há a escolher: o
     * instante é o do servidor e o autor é quem chama.
     */
    public record EntregarOrdemDTO(@NotNull Long operadorId) {
    }

    /**
     * Correção de OS pelo ADMIN. Os três campos vão inteiros — o que difere do
     * atual é o que muda —, e `idExterno` é obrigatório: a tela de correção
     * acha a OS por ele, e esvaziá-lo a tiraria de lá. (Acha pelo Nº e, havendo
     * irmãs em outros setores, pergunta qual — o Nº sozinho não identifica.)
     *
     * `posicoes` vai INTEIRO, como os demais campos: é o conjunto final, não um
     * delta. O que sair dele é removido — e remover solta as cargas daquele
     * setor e cancela os passos abertos nelas. Vazio é recusado: uma OS sem
     * setor não roda em lugar nenhum.
     *
     * `cargaIds` são cargas LIVRES a vincular, e só contam quando o conjunto
     * muda: as dos setores removidos saem e estas entram, na mesma transação.
     * Ausente ou vazio, a OS fica sem carga nos setores acrescentados.
     */
    public record CorrigirOrdemDTO(
            @NotNull Long operadorId,
            @NotNull Long idExterno,
            @NotNull Long clienteId,
            @NotEmpty Set<@NotNull Posicao> posicoes,
            List<@NotNull Long> cargaIds,
            @NotBlank @Size(max = 500) String motivo) {
    }

    /**
     * A OS passa a rodar TAMBÉM neste setor.
     *
     * Só o setor e quem o declarou: não há motivo a pedir, porque acrescentar
     * não é corrigir um engano — é registrar que as peças desta ordem também
     * estão do outro lado da fábrica. Quem remove é a correção de ADMIN, que
     * aí sim exige motivo.
     */
    public record AdicionarPosicaoDTO(
            @NotNull Long operadorId,
            @NotNull Posicao posicao) {
    }

    /**
     * `cargaIds` é opcional: ausente ou vazio, o lote só avança — é assim que a
     * Inspeção Final chama a rota, sobre OS que já não têm carga vinculada.
     * Preenchido, as cargas listadas têm o passo aberto fechado e saem da OS.
     */
    public record FinalizarLoteDTO(@NotNull Long operadorId, List<@NotNull Long> cargaIds) {
    }

    /**
     * Liberação de cargas sem virar o lote. Ao contrário de FinalizarLoteDTO,
     * `cargaIds` é obrigatório: sem cargas a operação não faria nada.
     */
    public record LiberarCargasDTO(
            @NotNull Long operadorId, @NotEmpty List<@NotNull Long> cargaIds) {
    }

    /**
     * Fecho de uma etapa. Só o operador: a hora é a do servidor, e o passo já
     * diz a que carga e processo pertence. É quem FECHA — não se deduz do
     * responsável da abertura, que muitas vezes é outra pessoa.
     */
    public record FinalizarLogDTO(@NotNull Long operadorId) {
    }

    /**
     * Acoplamento de uma OS a uma carga. O par (carga, OS) vem na URL; o corpo
     * traz só quem o declara — é em nome dele que os passos abertos da carona
     * fecham.
     */
    public record AcoplarDTO(@NotNull Long operadorId) {
    }

    // ------------------------------------------------------------------ saída
    /**
     * A OS como ela aparece nas LISTAS. Além do essencial, carrega o fecho —
     * `finalizadaEm`, `cancelada` e a entrega — porque a tela precisa separar
     * "expedida e ainda cá" de "entregue" e de "cancelada", e fazê-lo pelo
     * detalhe custaria um GET por linha. Ver a aba de Entregas no front.
     *
     * `entreguePorNome` obriga a tocar a relação LAZY `entreguePor`; quem lista
     * traz o fetch resolvido (OrdemServicoRepository.listarParaResumo).
     *
     * `clienteId` além do nome: o Nº do ERP determina o cliente (ver
     * OrdemServicoService.criar), e a tela de criação precisa do ID para montar
     * o pedido a partir de uma OS homônima que já está na lista. O cliente já
     * vem no fetch — não custa query nenhuma.
     */
    public record OrdemResumoDTO(
            Long id, Long idExterno, Long clienteId, String clienteNome,
            List<Posicao> posicoes, boolean emProcesso, Instant iniciadaEm,
            Instant finalizadaEm, boolean cancelada,
            Instant entregueEm, String entreguePorNome,
            int totalLotes, long lotesFinalizados) {

        public static OrdemResumoDTO from(OrdemServico os) {
            return new OrdemResumoDTO(
                    os.getId(), os.getIdExterno(),
                    os.getCliente().getId(), os.getCliente().getNome(),
                    os.getPosicoesOrdenadas(), os.isEmProcesso(), os.getIniciadaEm(),
                    os.getFinalizadaEm(), os.isCancelada(),
                    os.getEntregueEm(),
                    os.getEntreguePor() != null ? os.getEntreguePor().getNome() : null,
                    os.getTotalLotes(), os.getLotesFinalizados());
        }
    }

    public record OrdemDetalheDTO(
            Long id, Long idExterno, Long clienteId, String clienteNome,
            List<Posicao> posicoes, Instant iniciadaEm, Instant finalizadaEm,
            boolean cancelada, boolean emProcesso,
            String iniciadaPorNome, String finalizadaPorNome,
            Instant entregueEm, String entreguePorNome,
            List<Long> cargasVinculadas, List<LoteDTO> lotes,
            List<OrdemDesidrogenizacaoDTO> desidrogenizacoes,
            List<LogDTO> logsIniciados) {

        /**
         * Só a criação da OS conhece os passos recém-abertos, e só ela os
         * devolve. Nas demais rotas o campo vem null DE PROPÓSITO: ler
         * `os.getLogs()` aqui faria todo GET de detalhe carregar o histórico
         * inteiro, que já é servido por GET /api/ordens/{id}/logs.
         */
        public static OrdemDetalheDTO from(OrdemServico os) {
            return from(os, null);
        }

        public static OrdemDetalheDTO from(OrdemServico os, List<Log> logsIniciados) {
            return new OrdemDetalheDTO(
                    os.getId(), os.getIdExterno(),
                    os.getCliente().getId(), os.getCliente().getNome(),
                    os.getPosicoesOrdenadas(), os.getIniciadaEm(), os.getFinalizadaEm(),
                    os.isCancelada(), os.isEmProcesso(),
                    os.getIniciadaPor() != null ? os.getIniciadaPor().getNome() : null,
                    os.getFinalizadaPor() != null ? os.getFinalizadaPor().getNome() : null,
                    os.getEntregueEm(),
                    os.getEntreguePor() != null ? os.getEntreguePor().getNome() : null,
                    os.getCargas().stream().map(Carga::getId).toList(),
                    os.getLotes().stream().map(LoteDTO::from).toList(),
                    // Etapa opcional: quase sempre lista vazia. @BatchSize na
                    // coleção evita o N+1 que isto abriria na listagem.
                    os.getDesidrogenizacoes().stream()
                            .map(OrdemDesidrogenizacaoDTO::from).toList(),
                    logsIniciados == null
                            ? null
                            : logsIniciados.stream().map(LogDTO::from).toList());
        }
    }

    /**
     * O que a Visão Geral precisa de uma OS e o OrdemResumoDTO não carrega.
     *
     * São só quatro campos: `finalizadaEm`, `cancelada`, `entregueEm` e
     * `entreguePorNome` já vêm no resumo desde a V17, e a auditoria lê de lá.
     * Por isso este DTO não é o OrdemDetalheDTO — aquele traz dezassete campos,
     * incluindo `cargasVinculadas`, que é mais uma coleção LAZY por ordem, e a
     * tela nunca a usa.
     *
     * Existe para ser pedido em lote: a aba montava-se com um GET de detalhe por
     * OS, o que a fazia abrir com mais de cem requisições.
     */
    public record OrdemAuditoriaDTO(
            Long ordemServicoId, String iniciadaPorNome, String finalizadaPorNome,
            List<LoteDTO> lotes, List<OrdemDesidrogenizacaoDTO> desidrogenizacoes) {

        /**
         * Lotes e desidrogenizações entram já buscados, e não lidos da entidade:
         * quem monta vários destes de uma vez traz as duas coleções numa
         * consulta só. Ler `os.getLotes()` aqui reabriria o N+1 pelo outro lado.
         */
        public static OrdemAuditoriaDTO from(OrdemServico os, List<Lote> lotes,
                                             List<OrdemDesidrogenizacao> desidros) {
            return new OrdemAuditoriaDTO(
                    os.getId(),
                    os.getIniciadaPor() != null ? os.getIniciadaPor().getNome() : null,
                    os.getFinalizadaPor() != null ? os.getFinalizadaPor().getNome() : null,
                    lotes.stream().map(LoteDTO::from).toList(),
                    desidros.stream().map(OrdemDesidrogenizacaoDTO::from).toList());
        }
    }

    /**
     * Uma linha do histórico de correções. `valorAnterior`/`valorNovo` já vêm
     * como texto de tela ("#12 ACME", "OXIDACAO", "CG-01, CG-02"); null quando
     * não havia valor (OS sem Nº, nenhuma carga).
     */
    public record OrdemAlteracaoDTO(
            Long id, CampoAlterado campo, String valorAnterior, String valorNovo,
            String motivo, String alteradaPorNome, Instant alteradaEm) {

        public static OrdemAlteracaoDTO from(OrdemAlteracao a) {
            return new OrdemAlteracaoDTO(
                    a.getId(), a.getCampo(), a.getValorAnterior(), a.getValorNovo(),
                    a.getMotivo(), a.getAlteradaPor().getNome(), a.getAlteradaEm());
        }
    }

    /** Os pontos saem no mesmo formato em que entram: null | true | "observação". */
    public record AvaliacaoDTO(
            @JsonProperty("isVerificado") boolean isVerificado,
            Object visual, Object aderencia, Object embalagem, Object camada,
            String observacao, String avaliadaPorNome, Instant avaliadaEm) {

        public static AvaliacaoDTO from(OrdemAvaliacao a) {
            return new AvaliacaoDTO(
                    a.isVerificado(),
                    a.getVisual().valor(), a.getAderencia().valor(),
                    a.getEmbalagem().valor(), a.getCamada().valor(),
                    a.getObservacao(), a.getAvaliadaPor().getNome(), a.getAvaliadaEm());
        }
    }

    public record LoteDTO(
            Long id, Short numero, Instant iniciadoEm,
            Instant finalizadoEm, String finalizadoPorNome) {

        public static LoteDTO from(Lote lote) {
            return new LoteDTO(
                    lote.getId(), lote.getNumero(), lote.getIniciadoEm(),
                    lote.getFinalizadoEm(),
                    lote.getFinalizadoPor() != null ? lote.getFinalizadoPor().getNome() : null);
        }
    }

    /**
     * Resposta da reabertura: o lote recém-aberto e as cargas que a expedição
     * total tinha soltado e que ainda estão livres neste setor.
     *
     * `cargasSugeridas` é SUGESTÃO, não estado — nenhuma delas foi revinculada.
     * O front abre o modal de vínculo com elas marcadas e o operador confirma,
     * desmarca ou acrescenta outras; o vínculo em si continua a acontecer em
     * POST /api/ordens/{id}/cargas, que é o que abre o passo inicial.
     */
    public record ReaberturaDTO(LoteDTO lote, List<CargaDTO> cargasSugeridas) {

        public static ReaberturaDTO from(Reabertura r) {
            return new ReaberturaDTO(
                    LoteDTO.from(r.lote()),
                    r.cargasSugeridas().stream().map(CargaDTO::from).toList());
        }
    }

    /**
     * `ordemServicoId` é a OS TITULAR do passo — a dona da carga. Quando o
     * passo tem `ordensAcopladas`, ele também aparece no histórico dessas
     * outras OS, e lá `ordemServicoId` aponta para outra ordem: é assim que o
     * front sabe que aquela etapa foi de carona, não própria.
     */
    /**
     * `cargaId` além do nome: o acoplamento é da carga, então a tela que mostra
     * um passo precisa de saber em qual tanque ele corre para poder desacoplar.
     * O nome sozinho obrigava o cliente a cruzar com GET /api/cargas.
     */
    /**
     * `responsavelNome` é quem ABRIU o passo; `finalizadoPorNome`, quem o
     * fechou — quase nunca a mesma pessoa, e null nos passos fechados antes da
     * V21, quando o autor do fecho não era registado.
     */
    public record LogDTO(
            UUID id, Long ordemServicoId, Long cargaId, String cargaNome,
            String processoDescricao, String responsavelNome, Instant iniciadoEm,
            Instant finalizadoEm, String finalizadoPorNome, boolean cancelado,
            List<Long> ordensAcopladas) {

        public static LogDTO from(Log log) {
            return new LogDTO(
                    log.getId(), log.getOrdemServico().getId(),
                    log.getCarga().getId(), log.getCarga().getNome(),
                    log.getProcesso().getDescricao(), log.getResponsavel().getNome(),
                    log.getIniciadoEm(), log.getFinalizadoEm(),
                    log.getFinalizadoPor() != null ? log.getFinalizadoPor().getNome() : null,
                    log.isCancelado(),
                    List.copyOf(log.getOrdensAcopladas()));
        }
    }
}
