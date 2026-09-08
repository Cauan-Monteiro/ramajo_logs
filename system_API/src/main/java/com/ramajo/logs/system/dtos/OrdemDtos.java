package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.dtos.DesidrogenizacaoDtos.OrdemDesidrogenizacaoDTO;
import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.enums.Posicao;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs de entrada e saída do fluxo de Ordem de Serviço e Log.
 *
 * Os métodos `from(...)` tocam relações LAZY (cliente, cargas, carga/processo/
 * responsavel do log). Devem ser chamados com a sessão de persistência aberta
 * — o que ocorre no controller graças ao open-in-view (ligado por padrão no
 * Spring Boot). Ver nota no fim da resposta se você desligar o open-in-view.
 */
public final class OrdemDtos {

    private OrdemDtos() {
    }

    // ---------------------------------------------------------------- entrada
    /**
     * `cargaIds` é opcional. Quando vem preenchido, a OS já nasce com essas
     * cargas vinculadas e com um passo aberto no processo inicial para cada
     * uma — tudo na mesma transação. Omitido (ou vazio), o comportamento é o
     * de sempre: só a OS e o lote 1.
     */
    public record CriarOrdemDTO(
            @NotNull Long clienteId,
            @NotNull Long operadorId,
            Long idExterno,                 // opcional: conciliação com o ERP
            @NotNull Posicao posicao,
            List<@NotNull Long> cargaIds) {
    }

    /**
     * O vínculo já abre o passo inicial da carga, e todo passo tem um
     * responsável. `operadorId` é opcional: informe quem está de fato
     * executando; omitido, o passo fica no nome de quem abriu a OS.
     */
    public record VincularCargaDTO(
            @NotNull Long cargaId,
            Long operadorId) {
    }

    /**
     * `ordensAcopladasIds` são outras OS cujas peças estavam na MESMA carga
     * quando o processo rodou. Opcional: nulo e vazio querem dizer a mesma
     * coisa — passo de uma OS só, o caso comum — e o campo ser aditivo mantém
     * os clientes antigos funcionando.
     */
    public record IniciarLogDTO(
            @NotNull Long cargaId,
            @NotNull Long processoId,
            @NotNull Long responsavelId,
            List<Long> ordensAcopladasIds) {

        public List<Long> ordensAcopladasIds() {
            return ordensAcopladasIds == null ? List.of() : ordensAcopladasIds;
        }
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

    public record FinalizarOrdemDTO(@NotNull Long operadorId) {
    }

    public record CancelarOrdemDTO(@NotNull Long operadorId) {
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

    // ------------------------------------------------------------------ saída
    public record OrdemResumoDTO(
            Long id, Long idExterno, String clienteNome,
            Posicao posicao, boolean emProcesso, Instant iniciadaEm,
            int totalLotes, long lotesFinalizados) {

        public static OrdemResumoDTO from(OrdemServico os) {
            return new OrdemResumoDTO(
                    os.getId(), os.getIdExterno(), os.getCliente().getNome(),
                    os.getPosicao(), os.isEmProcesso(), os.getIniciadaEm(),
                    os.getTotalLotes(), os.getLotesFinalizados());
        }
    }

    public record OrdemDetalheDTO(
            Long id, Long idExterno, Long clienteId, String clienteNome,
            Posicao posicao, Instant iniciadaEm, Instant finalizadaEm,
            boolean cancelada, boolean emProcesso,
            String iniciadaPorNome, String finalizadaPorNome,
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
                    os.getPosicao(), os.getIniciadaEm(), os.getFinalizadaEm(),
                    os.isCancelada(), os.isEmProcesso(),
                    os.getIniciadaPor() != null ? os.getIniciadaPor().getNome() : null,
                    os.getFinalizadaPor() != null ? os.getFinalizadaPor().getNome() : null,
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
     * `ordemServicoId` é a OS TITULAR do passo — a dona da carga. Quando o
     * passo tem `ordensAcopladas`, ele também aparece no histórico dessas
     * outras OS, e lá `ordemServicoId` aponta para outra ordem: é assim que o
     * front sabe que aquela etapa foi de carona, não própria.
     */
    public record LogDTO(
            UUID id, Long ordemServicoId, String cargaNome, String processoDescricao,
            String responsavelNome, Instant iniciadoEm,
            Instant finalizadoEm, boolean cancelado, List<Long> ordensAcopladas) {

        public static LogDTO from(Log log) {
            return new LogDTO(
                    log.getId(), log.getOrdemServico().getId(), log.getCarga().getNome(),
                    log.getProcesso().getDescricao(), log.getResponsavel().getNome(),
                    log.getIniciadoEm(), log.getFinalizadoEm(), log.isCancelado(),
                    List.copyOf(log.getOrdensAcopladas()));
        }
    }
}
