package com.ramajo.logs.system.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ramajo.logs.system.entities.Carga;
import com.ramajo.logs.system.entities.Cliente;
import com.ramajo.logs.system.entities.Log;
import com.ramajo.logs.system.entities.Lote;
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.OrdemClienteDivergenteException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.PosicaoIncompativelException;
import com.ramajo.logs.system.repositories.CargaRepository;
import com.ramajo.logs.system.repositories.ClienteRepository;
import com.ramajo.logs.system.repositories.LogRepository;
import com.ramajo.logs.system.repositories.LoteRepository;
import com.ramajo.logs.system.repositories.OperadorRepository;
import com.ramajo.logs.system.repositories.OrdemAlteracaoRepository;
import com.ramajo.logs.system.repositories.OrdemServicoRepository;
import com.ramajo.logs.system.repositories.ProcessoInicialRepository;
import com.ramajo.logs.system.repositories.ProcessoRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Criação de OS, e sobretudo a regra que a V20 trouxe: o Nº do ERP é único
 * POR POSIÇÃO, não em absoluto. É isso que decide, dentro do próprio POST,
 * entre abrir uma ordem nova e vincular as cargas a uma que já existe.
 *
 * O que estes casos vigiam:
 *
 * 1. As três vias de criar() — Nº livre, Nº do mesmo setor, Nº de outro setor —
 *    e, em cada uma, o que NÃO pode acontecer: a via do vínculo não pode gravar
 *    OS nem lote, e a via do outro setor não pode recusar nada.
 * 2. O Nº não volta a ser reutilizado: repetir o Nº de uma ordem já expedida,
 *    no mesmo setor, é recusa — não uma OS nova.
 * 3. O caminho do vínculo não é uma porta dos fundos: as validações da carga
 *    continuam de pé, e o cliente do corpo tem de ser o da OS que já está lá.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceCriacaoTest {

    private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

    @Mock private OrdemServicoRepository osRepo;
    @Mock private ClienteRepository clienteRepo;
    @Mock private OperadorRepository operadorRepo;
    @Mock private CargaRepository cargaRepo;
    @Mock private ProcessoRepository processoRepo;
    @Mock private LogRepository logRepo;
    @Mock private LoteRepository loteRepo;
    @Mock private ProcessoInicialRepository processoInicialRepo;
    @Mock private OrdemAlteracaoRepository alteracaoRepo;

    @InjectMocks private OrdemServicoService service;

    /* -- Nº livre: a criação de sempre ------------------------------------- */

    @Test
    void numeroLivreAbreOsNovaComLote1() {
        cliente();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of());
        when(osRepo.save(any(OrdemServico.class))).thenAnswer(i -> i.getArgument(0));
        when(loteRepo.save(any(Lote.class))).thenAnswer(i -> i.getArgument(0));

        var criada = service.criar(1L, 10L, 100L, Posicao.OXIDACAO, List.of(), null);

        assertThat(criada.vinculada()).isFalse();
        assertThat(criada.ordem().getIdExterno()).isEqualTo(100L);
        assertThat(criada.ordem().getPosicoes()).containsExactly(Posicao.OXIDACAO);
        assertThat(criada.ordem().getLotes()).singleElement()
                .extracting(Lote::getNumero).isEqualTo((short) 1);
    }

    /** OS sem conciliação com o ERP nunca tem irmã — várias delas convivem. */
    @Test
    void numeroNuloNemConsultaAsIrmas() {
        cliente();
        operador();
        when(osRepo.save(any(OrdemServico.class))).thenAnswer(i -> i.getArgument(0));
        when(loteRepo.save(any(Lote.class))).thenAnswer(i -> i.getArgument(0));

        var criada = service.criar(1L, 10L, null, Posicao.OXIDACAO, List.of(), null);

        assertThat(criada.vinculada()).isFalse();
        verify(osRepo, never()).findAllByIdExterno(any());
    }

    /* -- Nº do MESMO setor: vira vínculo ----------------------------------- */

    @Test
    void numeroDoMesmoSetorVinculaNaOsExistenteSemAbrirOutra() throws Exception {
        OrdemServico existente = ordem(7L, 100L, Posicao.OXIDACAO);
        Carga nova = carga(20L, "C-20", Posicao.OXIDACAO);
        Processo entrada = processo("Desengraxe", Posicao.OXIDACAO);

        cliente();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of(existente));
        when(osRepo.findById(7L)).thenReturn(Optional.of(existente));
        when(cargaRepo.findById(20L)).thenReturn(Optional.of(nova));
        when(processoInicialRepo.findById(Posicao.OXIDACAO))
                .thenReturn(Optional.of(new ProcessoInicial(Posicao.OXIDACAO, entrada)));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(20L)).thenReturn(Optional.empty());
        when(logRepo.save(any(Log.class))).thenAnswer(i -> i.getArgument(0));

        var criada = service.criar(1L, 10L, 100L, Posicao.OXIDACAO, List.of(20L), null);

        assertThat(criada.vinculada()).isTrue();
        assertThat(criada.ordem()).isSameAs(existente);
        assertThat(nova.getOrdemAtual()).isSameAs(existente);
        assertThat(criada.logsIniciados()).singleElement()
                .extracting(Log::getProcesso).isSameAs(entrada);

        // O cliente do corpo é o mesmo da OS — e a OS continua com o dela. Sem
        // isto, o caso passaria mesmo que o vínculo reescrevesse o cliente.
        assertThat(existente.getCliente().getId()).isEqualTo(1L);

        // O ponto do caso: nada NASCEU. Nem ordem, nem lote.
        verify(osRepo, never()).save(any(OrdemServico.class));
        verify(loteRepo, never()).save(any(Lote.class));
    }

    /**
     * A corrida que motiva a recusa: a tela concluiu "Nº livre, OS nova" com uma
     * lista atrasada e pediu o cliente ao operador. O Nº daquele setor já tem
     * dono — vincular calado na ordem do outro cliente devolveria 200
     * confirmando o engano.
     */
    @Test
    void numeroDoMesmoSetorDeOutroClienteERecusado() throws Exception {
        OrdemServico daAcme = ordem(7L, 100L, Posicao.OXIDACAO);

        clienteBeta();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of(daAcme));

        assertThatThrownBy(() ->
                service.criar(2L, 10L, 100L, Posicao.OXIDACAO, List.of(20L), null))
                .isInstanceOf(OrdemClienteDivergenteException.class);

        // Antes de qualquer vínculo: a carga nem chegou a ser buscada.
        verify(cargaRepo, never()).findById(any());
        verify(logRepo, never()).save(any(Log.class));
        assertThat(daAcme.getCliente().getId()).isEqualTo(1L);
    }

    /**
     * A recusa do dono NÃO olha o setor, e é o que separa as duas leituras do
     * mesmo número: a 100 de Oxidação e a 100 de Automática são a MESMA ordem
     * do ERP partida entre sítios, não duas ordens que calharam no número. Duas
     * ordens de clientes diferentes com o Nº 100 seria o ERP a mentir.
     */
    @Test
    void numeroDeOutroSetorDeOutroClienteERecusado() throws Exception {
        OrdemServico daAcmeNaOxidacao = ordem(7L, 100L, Posicao.OXIDACAO);

        clienteBeta();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of(daAcmeNaOxidacao));

        assertThatThrownBy(() ->
                service.criar(2L, 10L, 100L, Posicao.AUTOMATICA, List.of(), null))
                .isInstanceOf(OrdemClienteDivergenteException.class);

        verify(osRepo, never()).save(any(OrdemServico.class));
        verify(loteRepo, never()).save(any(Lote.class));
    }

    /**
     * O Nº não é reutilizado: gasto naquele setor, gasto fica. Repeti-lo depois
     * da expedição não abre uma segunda OS — cai na irmã, que já saiu de
     * circulação.
     */
    @Test
    void numeroDoMesmoSetorJaExpedidoERecusado() throws Exception {
        OrdemServico expedida = ordem(7L, 100L, Posicao.OXIDACAO);
        expedida.setFinalizadaEm(T0);
        expedida.setEmProcesso(false);

        cliente();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of(expedida));
        when(osRepo.findById(7L)).thenReturn(Optional.of(expedida));

        assertThatThrownBy(() ->
                service.criar(1L, 10L, 100L, Posicao.OXIDACAO, List.of(), null))
                .isInstanceOf(OrdemForaDeCirculacaoException.class);

        verify(osRepo, never()).save(any(OrdemServico.class));
    }

    /** As regras do vínculo continuam de pé: a carga tem de ser do setor da OS. */
    @Test
    void cargaDeOutroSetorDerrubaOVinculo() throws Exception {
        OrdemServico existente = ordem(7L, 100L, Posicao.OXIDACAO);
        Carga deOutroSetor = carga(20L, "C-20", Posicao.AUTOMATICA);

        cliente();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of(existente));
        when(osRepo.findById(7L)).thenReturn(Optional.of(existente));
        when(cargaRepo.findById(20L)).thenReturn(Optional.of(deOutroSetor));

        assertThatThrownBy(() ->
                service.criar(1L, 10L, 100L, Posicao.OXIDACAO, List.of(20L), null))
                .isInstanceOf(PosicaoIncompativelException.class);

        assertThat(deOutroSetor.getOrdemAtual()).isNull();
    }

    /* -- Nº de OUTRO setor: nasce OS nova ---------------------------------- */

    /**
     * O caso que a V20 existe para permitir: a ordem 100 do ERP tem peças na
     * Oxidação e peças na Automática. São duas OS paralelas com o mesmo Nº — e
     * a segunda nasce sem recusa nenhuma.
     */
    @Test
    void numeroDeOutroSetorAbreOsNovaNaquelaPosicao() throws Exception {
        OrdemServico naOxidacao = ordem(7L, 100L, Posicao.OXIDACAO);

        cliente();
        operador();
        when(osRepo.findAllByIdExterno(100L)).thenReturn(List.of(naOxidacao));
        when(osRepo.save(any(OrdemServico.class))).thenAnswer(i -> i.getArgument(0));
        when(loteRepo.save(any(Lote.class))).thenAnswer(i -> i.getArgument(0));

        var criada = service.criar(1L, 10L, 100L, Posicao.AUTOMATICA, List.of(), null);

        assertThat(criada.vinculada()).isFalse();
        assertThat(criada.ordem()).isNotSameAs(naOxidacao);
        assertThat(criada.ordem().getIdExterno()).isEqualTo(100L);
        assertThat(criada.ordem().getPosicoes()).containsExactly(Posicao.AUTOMATICA);
    }

    /**
     * A irmã escolhida é a do setor pedido, mesmo havendo outras com o mesmo Nº
     * — e uma OS multi-setor (V19) conta para o setor em que roda.
     */
    @Test
    void escolheAIrmaDoSetorPedidoEntreAsDeMesmoNumero() throws Exception {
        OrdemServico naOxidacao = ordem(7L, 100L, Posicao.OXIDACAO);
        OrdemServico noPenduradoEAutomatica = ordem(8L, 100L, Posicao.PENDURADO);
        noPenduradoEAutomatica.getPosicoes().add(Posicao.AUTOMATICA);

        cliente();
        operador();
        when(osRepo.findAllByIdExterno(100L))
                .thenReturn(List.of(naOxidacao, noPenduradoEAutomatica));
        when(osRepo.findById(8L)).thenReturn(Optional.of(noPenduradoEAutomatica));

        var criada = service.criar(1L, 10L, 100L, Posicao.AUTOMATICA, List.of(), null);

        assertThat(criada.vinculada()).isTrue();
        assertThat(criada.ordem()).isSameAs(noPenduradoEAutomatica);
    }

    /* -- fixtures ---------------------------------------------------------- */

    private void cliente() {
        when(clienteRepo.findById(1L)).thenReturn(Optional.of(new Cliente(1L, "ACME LTDA")));
    }

    /** O outro dono: a fixture `ordem(...)` nasce sempre da ACME (#1). */
    private void clienteBeta() {
        when(clienteRepo.findById(2L)).thenReturn(Optional.of(new Cliente(2L, "BETA SA")));
    }

    private void operador() {
        when(operadorRepo.findById(10L))
                .thenReturn(Optional.of(new Operador("João", Permissao.FUNCIONARIO, "T1")));
    }

    private OrdemServico ordem(Long id, Long idExterno, Posicao posicao) throws Exception {
        OrdemServico os = new OrdemServico(idExterno, new Cliente(1L, "ACME LTDA"), posicao);
        os.setIniciadaPor(new Operador("Ana", Permissao.ADMIN, "A1"));
        set(os, "id", id);
        set(os, "iniciadaEm", T0);
        return os;
    }

    private Carga carga(Long id, String nome, Posicao posicao) throws Exception {
        Carga c = new Carga(nome, TipoCarga.TAMBOR, posicao);
        set(c, "id", id);
        return c;
    }

    private Processo processo(String descricao, Posicao posicao) {
        Processo p = new Processo(descricao, Etapa.PRE_TRATAMENTO);
        p.getPosicoes().add(posicao);
        return p;
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
