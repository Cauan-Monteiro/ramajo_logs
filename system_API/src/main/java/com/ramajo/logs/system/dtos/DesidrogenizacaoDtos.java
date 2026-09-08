package com.ramajo.logs.system.dtos;

import com.ramajo.logs.system.entities.ConfigDesidrogenizacao;
import com.ramajo.logs.system.entities.Desidrogenizacao;
import com.ramajo.logs.system.entities.OrdemDesidrogenizacao;
import com.ramajo.logs.system.enums.Posicao;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class DesidrogenizacaoDtos {

    private DesidrogenizacaoDtos() {
    }

    // ENTRADA  ===============================================================

    /**
     * Os limites espelham os CHECK da V10 — falhar como 400 de validação, com o
     * campo apontado, é melhor do que deixar o banco devolver 409 genérico.
     */
    public record CriarDesidrogenizacaoDTO(
            @NotBlank @Size(max = 80) String nome,
            @NotNull @Positive @Max(10080) Integer duracaoMin,
            String observacao) {
    }

    public record DefinirTemperaturaDTO(
            @NotNull @Positive @DecimalMax("999.0") BigDecimal temperatura) {
    }

    /** `operadorId` no corpo, como nas demais rotas: a API não tem autenticação. */
    public record AplicarDesidrogenizacaoDTO(
            @NotNull Long desidrogenizacaoId,
            @NotNull Long operadorId) {
    }

    // SAÍDA  =================================================================

    public record DesidrogenizacaoDTO(
            Long id, String nome, Integer duracaoMin, String observacao, boolean ativo) {

        public static DesidrogenizacaoDTO from(Desidrogenizacao d) {
            return new DesidrogenizacaoDTO(
                    d.getId(), d.getNome(), d.getDuracaoMin(), d.getObservacao(), d.isAtivo());
        }
    }

    public record ConfigDesidrogenizacaoDTO(BigDecimal temperatura) {

        public static ConfigDesidrogenizacaoDTO from(ConfigDesidrogenizacao c) {
            return new ConfigDesidrogenizacaoDTO(c.getTemperatura());
        }
    }

    /**
     * O que o indicativo do Dashboard precisa: quanto falta e de quem é.
     *
     * Um record à parte, e não campos novos em OrdemDesidrogenizacaoDTO: aquele
     * é o histórico de UMA ordem, que já sabe qual é — carregá-lo de dados da OS
     * só para esta tela pioraria as duas rotas que o servem.
     */
    public record DesidroEmAndamentoDTO(
            Long id, Long ordemServicoId, Long ordemIdExterno, Posicao posicao,
            String nome, Instant iniciadaEm, Instant finalizadaEm) {

        /** Toca os proxies LAZY aqui, com a sessão ainda aberta. */
        public static DesidroEmAndamentoDTO from(OrdemDesidrogenizacao od) {
            return new DesidroEmAndamentoDTO(
                    od.getId(),
                    od.getOrdemServico().getId(),
                    // O número que o operador conhece é o externo; o interno é
                    // o fallback (osNum() no front faz a mesma escolha).
                    od.getOrdemServico().getIdExterno(),
                    od.getOrdemServico().getPosicao(),
                    od.getDesidrogenizacao().getNome(),
                    od.getIniciadaEm(),
                    od.getFinalizadaEm());
        }
    }

    /**
     * `duracaoMin` e `temperatura` saem da APLICAÇÃO, não do cadastro: são o
     * snapshot do que rodou. Só o `nome` vem do catálogo (é o rótulo corrente).
     */
    public record OrdemDesidrogenizacaoDTO(
            Long id, Long desidrogenizacaoId, String nome,
            Integer duracaoMin, BigDecimal temperatura,
            Instant iniciadaEm, Instant finalizadaEm, String aplicadaPorNome) {

        /** Toca os proxies LAZY aqui, com a sessão ainda aberta. */
        public static OrdemDesidrogenizacaoDTO from(OrdemDesidrogenizacao od) {
            return new OrdemDesidrogenizacaoDTO(
                    od.getId(),
                    od.getDesidrogenizacao().getId(),
                    od.getDesidrogenizacao().getNome(),
                    od.getDuracaoMin(),
                    od.getTemperatura(),
                    od.getIniciadaEm(),
                    od.getFinalizadaEm(),
                    od.getAplicadaPor() != null ? od.getAplicadaPor().getNome() : null);
        }
    }
}
