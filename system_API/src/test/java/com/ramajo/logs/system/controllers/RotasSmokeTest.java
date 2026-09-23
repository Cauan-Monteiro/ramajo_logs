package com.ramajo.logs.system.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * O portão de `spring.jpa.open-in-view=false`.
 *
 * Os testes de service são unitários com Mockito: não têm banco e nunca
 * exercitam a serialização da resposta, que é onde o lazy load acontece. Este
 * percorre as rotas de ponta a ponta contra um Postgres de verdade — é o único
 * que vê um DTO.from chamado fora da transação.
 *
 * DUAS COISAS NÃO SE PODEM MEXER AQUI:
 *
 * 1. NÃO ponha @Transactional na classe. Uma transação de teste manteria a
 *    sessão aberta por toda a requisição e mascararia exatamente a
 *    LazyInitializationException que este teste existe para provocar — o
 *    open-in-view voltaria pela porta dos fundos. O preço é que o que se
 *    escreve fica gravado, e é por isso que o perfil `smoke` aponta para um
 *    banco próprio (ver application-smoke.properties).
 *
 * 2. NÃO troque por @WebMvcTest. Ali os services são mocks: não há sessão, não
 *    há lazy load, e o teste passaria com todos os bugs no lugar.
 *
 * A semeadura vai pela própria API, não pelos repositórios: assim as rotas de
 * escrita que devolvem DTO entram no gate junto com os GET — `finalizarLog`,
 * um PATCH, é o pior ponto da lista inteira.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RotasSmokeTest {

    @Autowired private MockMvc mvc;

    /**
     * Próprio, não injetado: o Spring Boot 4 usa Jackson 3, e só o
     * JsonMapper da aplicação está no contexto. Aqui só se LÊ a resposta, e
     * para isso o mapper padrão basta.
     */
    private final ObjectMapper json = new ObjectMapper();

    /** Setor único do cenário: acoplar exige que titular e carona rodem no mesmo. */
    private static final String SETOR = "OXIDACAO";

    private long clienteId;
    private long operadorId;
    private long processoId;
    private long cargaId;
    private long osId;
    private long caronaId;
    private long desidroId;
    private String logId;
    private String tagCarga;
    private String tagOperador;

    /**
     * Um cenário com TODAS as coleções LAZY que nenhuma consulta faz fetch:
     * `Carga.ordensAcopladas`, `Log.ordensAcopladas`, `OrdemServico.posicoes`,
     * `OrdemServico.lotes/cargas/desidrogenizacoes` e `Processo.posicoes`.
     *
     * Sem isto o smoke corre sobre tabelas vazias: toda rota devolve lista
     * vazia, nenhuma coleção é tocada e o teste não prova nada.
     */
    @BeforeAll
    void semear() throws Exception {
        long marca = System.nanoTime() % 100000;
        tagCarga = "TC-" + marca;
        tagOperador = "TO-" + marca;

        clienteId = corpo(put("/api/clientes/1"), "{\"nome\":\"ACME LTDA\"}").get("id").asLong();

        operadorId = corpo(post("/api/operadores"), """
                {"nome":"Ana","permissao":"ADMIN","tagId":"%s"}
                """.formatted(tagOperador)).get("id").asLong();

        processoId = corpo(post("/api/processos"), """
                {"descricao":"Desengraxe","etapa":"PRE_TRATAMENTO",
                 "tagId":"TP-%s","posicoes":["%s"]}
                """.formatted(marca, SETOR)).get("id").asLong();

        // Entrada do setor: é o que a criação da OS usa para abrir o passo.
        corpo(put("/api/processos-iniciais/" + SETOR),
                "{\"processoId\":%d}".formatted(processoId));

        cargaId = corpo(post("/api/cargas"), """
                {"nome":"CG-%s","tipo":"TAMBOR","posicao":"%s","tagId":"%s"}
                """.formatted(marca, SETOR, tagCarga)).get("id").asLong();

        // A titular nasce já com a carga vinculada, o que abre o passo inicial.
        osId = corpo(post("/api/ordens"), """
                {"clienteId":%d,"operadorId":%d,"idExterno":%d,
                 "posicao":"%s","cargaIds":[%d]}
                """.formatted(clienteId, operadorId, marca, SETOR, cargaId))
                .get("id").asLong();

        // A carona: sem carga própria, vai pegar boleia no tanque da titular.
        caronaId = corpo(post("/api/ordens"), """
                {"clienteId":%d,"operadorId":%d,"idExterno":%d,
                 "posicao":"%s","cargaIds":[]}
                """.formatted(clienteId, operadorId, marca + 1, SETOR))
                .get("id").asLong();

        // Preenche Carga.ordensAcopladas E Log.ordensAcopladas do passo aberto.
        JsonNode carga = corpo(
                post("/api/ordens/cargas/%d/acopladas/%d".formatted(cargaId, caronaId)),
                "{\"operadorId\":%d}".formatted(operadorId));
        assertThat(carga.get("ordensAcopladas")).hasSize(1);

        logId = corpo(get("/api/ordens/" + osId + "/logs"), null).get(0).get("id").asText();

        // Catálogo do forno + uma aplicação: enche OrdemServico.desidrogenizacoes.
        // Nome com a marca: ux_desidro_nome e unico entre as ativas, e o que
        // este teste escreve FICA no banco (nao ha transacao a desfazer).
        desidroId = corpo(post("/api/desidrogenizacoes"), """
                {"nome":"Forno %s","duracaoMin":180,"observacao":"smoke"}
                """.formatted(marca)).get("id").asLong();
        corpo(post("/api/ordens/" + osId + "/desidrogenizacoes"),
                "{\"desidrogenizacaoId\":%d,\"operadorId\":%d}".formatted(desidroId, operadorId));

        // Uma correção, para /alteracoes ter linha.
        corpo(put("/api/ordens/" + osId), """
                {"operadorId":%d,"idExterno":%d,"clienteId":%d,
                 "posicoes":["%s"],"cargaIds":[],"motivo":"smoke"}
                """.formatted(operadorId, marca + 500, clienteId, SETOR));
    }

    // ---------------------------------------------------------------- escrita
    // O mapeamento destas rotas também corre fora de qualquer sessão aberta
    // pelo controller. finalizarLog é o pior ponto da lista: quatro proxies
    // crus (carga, processo, responsavel, ordensAcopladas) de uma vez.

    @Test
    @DisplayName("PATCH /api/ordens/logs/{logId}/finalizar — quatro proxies de uma vez")
    void finalizarLog() throws Exception {
        JsonNode log = corpo(patch("/api/ordens/logs/" + logId + "/finalizar"),
                "{\"operadorId\":%d}".formatted(operadorId));

        assertThat(log.get("cargaNome").asText()).isNotBlank();
        assertThat(log.get("processoDescricao").asText()).isNotBlank();
        assertThat(log.get("responsavelNome").asText()).isNotBlank();
        assertThat(log.get("ordensAcopladas")).hasSize(1);
    }

    @Test
    @DisplayName("POST /api/ordens/{id}/posicoes")
    void adicionarPosicao() throws Exception {
        JsonNode os = corpo(post("/api/ordens/" + osId + "/posicoes"),
                "{\"posicao\":\"PENDURADO\",\"operadorId\":%d}".formatted(operadorId));
        assertThat(os.get("posicoes")).isNotEmpty();
    }

    @Test
    @DisplayName("PUT /api/ordens/{id}/avaliacao")
    void avaliar() throws Exception {
        JsonNode a = corpo(put("/api/ordens/" + osId + "/avaliacao"), """
                {"operadorId":%d,"avaliacao":{"visual":true,"aderencia":true,
                 "embalagem":true,"camada":true,"observacao":"ok"}}
                """.formatted(operadorId));
        assertThat(a.get("avaliadaPorNome").asText()).isNotBlank();
    }

    @Test
    @DisplayName("PUT /api/cargas/{id} e /api/processos/{id}")
    void atualizacoesQueDevolvemDto() throws Exception {
        JsonNode c = corpo(put("/api/cargas/" + cargaId), """
                {"nome":"CG-editada","tipo":"CESTO","posicao":"%s","tagId":"%s"}
                """.formatted(SETOR, tagCarga));
        assertThat(c.get("ordensAcopladas")).isNotNull();

        JsonNode p = corpo(put("/api/processos/" + processoId), """
                {"descricao":"Desengraxe II","etapa":"PRE_TRATAMENTO",
                 "tagId":null,"posicoes":["%s"]}
                """.formatted(SETOR));
        assertThat(p.get("posicoes")).hasSize(1);
    }

    // ---------------------------------------------------------------- leitura

    @Test
    @DisplayName("Todo GET que devolve JSON")
    void todosOsGets() throws Exception {
        // Cadastros simples — sem associação LAZY, mas no gate pelo invariante.
        ok(get("/api/clientes"));
        ok(get("/api/clientes/" + clienteId));
        ok(get("/api/operadores"));
        ok(get("/api/operadores/" + operadorId));
        ok(get("/api/operadores/por-tag/" + tagOperador));
        ok(get("/api/estado/revisao"));

        // Processo.posicoes
        assertThat(corpo(get("/api/processos"), null)).isNotEmpty();
        assertThat(corpo(get("/api/processos/" + processoId), null).get("posicoes")).hasSize(1);
        assertThat(corpo(get("/api/processos-iniciais"), null)).isNotEmpty();

        // Carga.ordensAcopladas
        assertThat(corpo(get("/api/cargas"), null)).isNotEmpty();
        ok(get("/api/cargas").param("disponiveis", "true"));
        assertThat(corpo(get("/api/cargas/" + cargaId), null).get("ordensAcopladas")).isNotNull();
        ok(get("/api/cargas/por-tag/" + tagCarga));

        // OrdemServico.posicoes via DesidroEmAndamentoDTO
        ok(get("/api/desidrogenizacoes"));
        ok(get("/api/desidrogenizacoes/" + desidroId));
        ok(get("/api/desidrogenizacoes/temperatura"));
        assertThat(corpo(get("/api/desidrogenizacoes/em-andamento"), null)).isNotEmpty();

        // OrdemServico.posicoes/lotes/cargas/desidrogenizacoes e Log.ordensAcopladas
        assertThat(corpo(get("/api/ordens"), null)).isNotEmpty();
        ok(get("/api/ordens").param("emProcesso", "true"));

        JsonNode detalhe = corpo(get("/api/ordens/" + osId), null);
        assertThat(detalhe.get("posicoes")).isNotEmpty();
        assertThat(detalhe.get("lotes")).isNotEmpty();
        assertThat(detalhe.get("desidrogenizacoes")).isNotEmpty();

        assertThat(corpo(get("/api/ordens/" + osId + "/alteracoes"), null)).isNotEmpty();
        assertThat(corpo(get("/api/ordens/" + osId + "/logs"), null).get(0)
                .get("ordensAcopladas")).hasSize(1);
        assertThat(corpo(get("/api/ordens/logs").param("ids", osId + "," + caronaId), null))
                .isNotEmpty();
        assertThat(corpo(get("/api/ordens/auditoria").param("ids", osId + "," + caronaId), null))
                .isNotEmpty();
        assertThat(corpo(get("/api/ordens/" + osId + "/lotes"), null)).isNotEmpty();
        assertThat(corpo(get("/api/ordens/" + osId + "/desidrogenizacoes"), null)).isNotEmpty();
        ok(get("/api/ordens/" + osId + "/avaliacao"));  // 200 ou 204
    }

    /**
     * As três rotas que não devolvem JSON. As planilhas são o caso extremo do
     * que este teste cobre: o workbook inteiro é montado a partir de relações
     * LAZY, e PlanilhaOrdemServicoService já o faz dentro da transação (ver o
     * javadoc de lá) — aqui confirma-se que continua verdade.
     */
    @Test
    @DisplayName("Rotas que não devolvem JSON: as duas planilhas e o SSE")
    void rotasNaoJson() throws Exception {
        byte[] daOrdem = mvc.perform(get("/api/ordens/" + osId + "/planilha"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(daOrdem).isNotEmpty();

        LocalDate hoje = LocalDate.now();
        byte[] doPeriodo = mvc.perform(get("/api/relatorios/periodo/planilha")
                        .param("dataInicio", hoje.minusDays(1).toString())
                        .param("dataFim", hoje.plusDays(1).toString()))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(doPeriodo).isNotEmpty();

        // SSE: a conexão é longa: basta ver que o emitter foi aceito.
        MvcResult sse = mvc.perform(get("/api/estado/stream")).andReturn();
        assertThat(sse.getRequest().isAsyncStarted()).isTrue();
        sse.getRequest().getAsyncContext().complete();
    }

    // ------------------------------------------------------------------ apoio

    /** Executa e exige 2xx; devolve o corpo como JSON (null se vazio). */
    private JsonNode corpo(RequestBuilder pedido, String body) throws Exception {
        if (pedido instanceof org.springframework.test.web.servlet.request
                .MockHttpServletRequestBuilder b && body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult r = mvc.perform(pedido).andReturn();
        String texto = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        exigirSucesso(r, texto);
        return texto.isBlank() ? null : json.readTree(texto);
    }

    private void ok(RequestBuilder pedido) throws Exception {
        MvcResult r = mvc.perform(pedido).andReturn();
        exigirSucesso(r, r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * A mensagem importa: uma LazyInitializationException chega aqui como 500
     * com a stack no `resolvedException`, e é ela que nomeia o DTO.from que
     * ficou fora da transação.
     */
    private void exigirSucesso(MvcResult r, String texto) {
        int status = r.getResponse().getStatus();
        if (status >= 200 && status < 300) {
            return;
        }
        Exception falha = r.getResolvedException();
        throw new AssertionError(
                "%s %s -> %d%n%s%n%s".formatted(
                        r.getRequest().getMethod(), r.getRequest().getRequestURI(),
                        status, texto, falha == null ? "" : rastro(falha)));
    }

    private static String rastro(Exception e) {
        StringBuilder sb = new StringBuilder(e.toString());
        for (StackTraceElement el : e.getStackTrace()) {
            if (el.getClassName().startsWith("com.ramajo")) {
                sb.append("\n\tat ").append(el);
            }
        }
        return sb.toString();
    }
}
