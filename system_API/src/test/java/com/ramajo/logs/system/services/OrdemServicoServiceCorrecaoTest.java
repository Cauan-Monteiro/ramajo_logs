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
import com.ramajo.logs.system.entities.Operador;
import com.ramajo.logs.system.entities.OrdemAlteracao;
import com.ramajo.logs.system.entities.OrdemServico;
import com.ramajo.logs.system.entities.Processo;
import com.ramajo.logs.system.entities.ProcessoInicial;
import com.ramajo.logs.system.enums.CampoAlterado;
import com.ramajo.logs.system.enums.Etapa;
import com.ramajo.logs.system.enums.Permissao;
import com.ramajo.logs.system.enums.Posicao;
import com.ramajo.logs.system.enums.TipoCarga;
import com.ramajo.logs.system.exceptions.CorrecaoInvalidaException;
import com.ramajo.logs.system.exceptions.OperacaoRestritaException;
import com.ramajo.logs.system.exceptions.OrdemForaDeCirculacaoException;
import com.ramajo.logs.system.exceptions.OrdemIdExternoExistente;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Correção de OS pelo ADMIN. O que estes casos vigiam:
 *
 * 1. Cada campo alterado vira UMA linha de histórico, com o texto de antes e
 *    de depois — e nada muda sem ela.
 * 2. As recusas (não-admin, OS fora de circulação, Nº repetido, pedido vazio)
 *    vêm antes de qualquer mutação.
 * 3. Trocar a posição leva junto o que era do setor antigo: passos abertos
 *    cancelados, cargas soltas, carona desfeita — e as cargas novas entram
 *    pelo processo inicial do setor NOVO.
 */
@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceCorrecaoTest {

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

    /* -- Nº e cliente ------------------------------------------------------ */

    @Test
    void corrigeNumeroEClienteComUmaLinhaDeHistoricoPorCampo() throws Exception {
        OrdemServico os = ordem(1L, 100L, Posicao.OXIDACAO);
        Cliente beta = new Cliente(2L, "BETA SA");

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(admin()));
        when(osRepo.findByIdExterno(200L)).thenReturn(Optional.empty());
        when(clienteRepo.findById(2L)).thenReturn(Optional.of(beta));

        service.corrigir(1L, 10L, 200L, 2L, Posicao.OXIDACAO, List.of(), "  Nº digitado errado ");

        assertThat(os.getIdExterno()).isEqualTo(200L);
        assertThat(os.getCliente()).isSameAs(beta);

        List<OrdemAlteracao> linhas = salvas();
        assertThat(linhas).extracting(OrdemAlteracao::getCampo)
                .containsExactly(CampoAlterado.ID_EXTERNO, CampoAlterado.CLIENTE);
        assertThat(linhas.get(0).getValorAnterior()).isEqualTo("100");
        assertThat(linhas.get(0).getValorNovo()).isEqualTo("200");
        assertThat(linhas.get(1).getValorAnterior()).isEqualTo("#1 ACME LTDA");
        assertThat(linhas.get(1).getValorNovo()).isEqualTo("#2 BETA SA");
        assertThat(linhas).allSatisfy(l -> assertThat(l.getMotivo()).isEqualTo("Nº digitado errado"));

        // Sem troca de setor, as cargas e os passos nem são consultados.
        verify(logRepo, never()).findByOrdemServicoIdAndFinalizadoEmIsNull(any());
        verify(cargaRepo, never()).buscarAcoplamentosDe(any());
    }

    @Test
    void recusaNumeroDeOutraOs() throws Exception {
        OrdemServico os = ordem(1L, 100L, Posicao.OXIDACAO);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(admin()));
        when(osRepo.findByIdExterno(200L)).thenReturn(Optional.of(ordem(5L, 200L, Posicao.OXIDACAO)));

        assertThatThrownBy(() ->
                service.corrigir(1L, 10L, 200L, 1L, Posicao.OXIDACAO, List.of(), "motivo"))
                .isInstanceOf(OrdemIdExternoExistente.class);

        assertThat(os.getIdExterno()).isEqualTo(100L);
        verify(alteracaoRepo, never()).saveAll(any());
    }

    /* -- os gates ---------------------------------------------------------- */

    @Test
    void recusaOperadorQueNaoEAdmin() throws Exception {
        when(osRepo.findById(1L)).thenReturn(Optional.of(ordem(1L, 100L, Posicao.OXIDACAO)));
        when(operadorRepo.findById(10L))
                .thenReturn(Optional.of(new Operador("João", Permissao.FUNCIONARIO, "T1")));

        assertThatThrownBy(() ->
                service.corrigir(1L, 10L, 200L, 1L, Posicao.OXIDACAO, List.of(), "motivo"))
                .isInstanceOf(OperacaoRestritaException.class);

        verify(alteracaoRepo, never()).saveAll(any());
    }

    @Test
    void recusaOsExpedida() throws Exception {
        OrdemServico os = ordem(1L, 100L, Posicao.OXIDACAO);
        os.setFinalizadaEm(T0);
        os.setEmProcesso(false);
        when(osRepo.findById(1L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() ->
                service.corrigir(1L, 10L, 200L, 1L, Posicao.OXIDACAO, List.of(), "motivo"))
                .isInstanceOf(OrdemForaDeCirculacaoException.class);
    }

    @Test
    void recusaPedidoSemAlteracao() throws Exception {
        when(osRepo.findById(1L)).thenReturn(Optional.of(ordem(1L, 100L, Posicao.OXIDACAO)));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(admin()));

        assertThatThrownBy(() ->
                service.corrigir(1L, 10L, 100L, 1L, Posicao.OXIDACAO, List.of(), "motivo"))
                .isInstanceOf(CorrecaoInvalidaException.class);

        verify(alteracaoRepo, never()).saveAll(any());
    }

    /* -- troca de posição -------------------------------------------------- */

    @Test
    void trocarPosicaoSoltaOSetorAntigoEVinculaAsCargasDoNovo() throws Exception {
        Operador joao = new Operador("João", Permissao.FUNCIONARIO, "T1");
        OrdemServico os = ordem(1L, 100L, Posicao.OXIDACAO);
        os.setIniciadaPor(joao);

        Carga antiga = carga(10L, "C-10", Posicao.OXIDACAO);
        antiga.setOrdemAtual(os);
        set(os, "cargas", new ArrayList<>(List.of(antiga)));

        Log aberto = new Log(os, joao, antiga, processo("Desengraxe", Posicao.OXIDACAO));
        set(aberto, "iniciadoEm", T0);

        // A OS também ia de carona numa carga do setor antigo.
        Carga alheia = carga(11L, "C-11", Posicao.OXIDACAO);
        alheia.setOrdemAtual(ordem(2L, 101L, Posicao.OXIDACAO));
        alheia.getOrdensAcopladas().add(1L);

        Carga nova = carga(20L, "C-20", Posicao.AUTOMATICA);
        Processo entradaAutomatica = processo("Desengraxe AUT", Posicao.AUTOMATICA);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(admin()));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(List.of(aberto));
        when(cargaRepo.buscarAcoplamentosDe(1L)).thenReturn(List.of(alheia));
        when(cargaRepo.findById(20L)).thenReturn(Optional.of(nova));
        when(processoInicialRepo.findById(Posicao.AUTOMATICA))
                .thenReturn(Optional.of(new ProcessoInicial(Posicao.AUTOMATICA, entradaAutomatica)));
        when(logRepo.findByCargaIdAndFinalizadoEmIsNull(20L)).thenReturn(Optional.empty());
        when(logRepo.save(any(Log.class))).thenAnswer(i -> i.getArgument(0));

        service.corrigir(1L, 10L, 100L, 1L, Posicao.AUTOMATICA, List.of(20L), "Setor errado");

        // O setor antigo: passo cancelado e fechado, carga livre, carona desfeita.
        assertThat(aberto.isCancelado()).isTrue();
        assertThat(aberto.getFinalizadoEm()).isNotNull();
        assertThat(antiga.getOrdemAtual()).isNull();
        assertThat(alheia.getOrdensAcopladas()).doesNotContain(1L);

        // O setor novo: a carga entra pelo processo inicial dele, no nome de
        // quem abriu a OS — não do ADMIN que corrigiu.
        assertThat(os.getPosicao()).isEqualTo(Posicao.AUTOMATICA);
        assertThat(nova.getOrdemAtual()).isSameAs(os);
        assertThat(os.getCargas()).containsExactly(nova);

        ArgumentCaptor<Log> passo = ArgumentCaptor.forClass(Log.class);
        verify(logRepo).save(passo.capture());
        assertThat(passo.getValue().getProcesso()).isSameAs(entradaAutomatica);
        assertThat(passo.getValue().getResponsavel()).isSameAs(joao);

        List<OrdemAlteracao> linhas = salvas();
        assertThat(linhas).extracting(OrdemAlteracao::getCampo)
                .containsExactly(CampoAlterado.POSICAO, CampoAlterado.CARGAS);
        assertThat(linhas.get(0).getValorAnterior()).isEqualTo("OXIDACAO");
        assertThat(linhas.get(0).getValorNovo()).isEqualTo("AUTOMATICA");
        assertThat(linhas.get(1).getValorAnterior()).isEqualTo("C-10");
        assertThat(linhas.get(1).getValorNovo()).isEqualTo("C-20");
    }

    @Test
    void cargaDeOutroSetorDerrubaACorrecao() throws Exception {
        OrdemServico os = ordem(1L, 100L, Posicao.OXIDACAO);
        Carga doSetorAntigo = carga(30L, "C-30", Posicao.OXIDACAO);

        when(osRepo.findById(1L)).thenReturn(Optional.of(os));
        when(operadorRepo.findById(10L)).thenReturn(Optional.of(admin()));
        when(logRepo.findByOrdemServicoIdAndFinalizadoEmIsNull(1L)).thenReturn(List.of());
        when(cargaRepo.buscarAcoplamentosDe(1L)).thenReturn(List.of());
        when(cargaRepo.findById(30L)).thenReturn(Optional.of(doSetorAntigo));

        assertThatThrownBy(() ->
                service.corrigir(1L, 10L, 100L, 1L, Posicao.AUTOMATICA, List.of(30L), "motivo"))
                .isInstanceOf(PosicaoIncompativelException.class);

        // O resto é rollback da transação; aqui basta que o histórico não saia.
        assertThat(doSetorAntigo.getOrdemAtual()).isNull();
        verify(alteracaoRepo, never()).saveAll(any());
    }

    /* -- fixtures ---------------------------------------------------------- */

    @SuppressWarnings("unchecked")
    private List<OrdemAlteracao> salvas() {
        ArgumentCaptor<List<OrdemAlteracao>> captor = ArgumentCaptor.forClass(List.class);
        verify(alteracaoRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    private OrdemServico ordem(Long id, Long idExterno, Posicao posicao) throws Exception {
        OrdemServico os = new OrdemServico(idExterno, new Cliente(1L, "ACME LTDA"), posicao);
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

    private Operador admin() {
        return new Operador("Ana", Permissao.ADMIN, "A1");
    }

    /** Os carimbos e ids são gerados pelo banco; no teste eles entram por reflexão. */
    private void set(Object alvo, String campo, Object valor) throws Exception {
        Field f = alvo.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(alvo, valor);
    }
}
