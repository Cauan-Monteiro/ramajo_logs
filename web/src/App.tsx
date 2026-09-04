import { useCallback, useState } from "react";
import { ApiErrorException } from "./api/client";
import { AppNav } from "./components/AppNav";
import { Toast, type Aviso } from "./components/Toast";
import { POSICOES } from "./domain/format";
import { Ajustes } from "./screens/Ajustes";
import { Auditoria } from "./screens/Auditoria";
import { Dashboard } from "./screens/Dashboard";
import { Login } from "./screens/Login";
import { Relatorios } from "./screens/Relatorios";
import { useAba } from "./state/useAba";
import { useAppData } from "./state/useAppData";
import { useSession } from "./state/useSession";
import { useSync } from "./state/useSync";
import { useViewMode } from "./state/useViewMode";
import type { Ctx } from "./modals/tipos";

export function App() {
  const { operador, entrar, sair, isAdmin } = useSession();
  const { isMobile } = useViewMode();
  const { aba, setAba, limparAba } = useAba(isAdmin);
  const [aviso, setAviso] = useState<Aviso | null>(null);
  const [ocupado, setOcupado] = useState(false);

  // Sair limpa a aba guardada: o próximo operador começa em Oxidação.
  const encerrar = useCallback(() => {
    limparAba();
    sair();
  }, [limparAba, sair]);

  const reportarErro = useCallback((e: unknown) => {
    if (e instanceof ApiErrorException) {
      setAviso({ tipo: "erro", codigo: e.codigo, mensagem: e.message });
    } else {
      setAviso({ tipo: "erro", mensagem: e instanceof Error ? e.message : String(e) });
    }
  }, []);

  const { data, carregando, pronto, marca, recarregar } = useAppData(reportarErro);

  // Mantém este terminal no mesmo ponto que os demais, sem F5.
  useSync({
    marca,
    recarregar,
    ativo: !!operador && pronto,
    ocupado,
    carregando,
  });

  /**
   * Toda mutação passa por aqui: executa, recarrega os dados da API e reporta.
   * Nada de estado otimista — regras como "abrir passo fecha o anterior" só
   * existem no service, e o cliente não tem como replicá-las com fidelidade.
   */
  const agir: Ctx["agir"] = useCallback(
    ({ fazer, ok, depois }) => {
      setOcupado(true);
      void (async () => {
        try {
          await fazer();
          await recarregar();
          if (ok) setAviso({ tipo: "ok", mensagem: ok });
          depois?.();
        } catch (e) {
          reportarErro(e);
        } finally {
          setOcupado(false);
        }
      })();
    },
    [recarregar, reportarErro],
  );

  if (!operador) {
    return (
      <>
        {aviso && <Toast aviso={aviso} onClose={() => setAviso(null)} />}
        <Login onEntrar={entrar} onErro={reportarErro} />
      </>
    );
  }

  const posicaoAtual = POSICOES.some((p) => p.key === aba)
    ? (aba as (typeof POSICOES)[number]["key"])
    : "OXIDACAO";

  return (
    <div className="tab">
      {aviso && <Toast aviso={aviso} onClose={() => setAviso(null)} />}

      <AppNav
        aba={aba}
        onAba={setAba}
        operador={operador}
        isAdmin={isAdmin}
        onSair={encerrar}
      />

      {!pronto ? (
        <div className="boot">
          {carregando ? (
            <>
              <span className="spin" />
              <span>Carregando dados da API...</span>
            </>
          ) : (
            <>
              <span>Não foi possível carregar os dados.</span>
              <button className="btn2" onClick={() => void recarregar()}>
                Tentar de novo
              </button>
            </>
          )}
        </div>
      ) : aba === "geral" ? (
        // Auditoria devolve um fragment e sempre viveu dentro de .rel-main:
        // reaproveitar o par .rel-body/.rel-main dá o mesmo scroll sem CSS novo.
        <div className="rel-body">
          <div className="rel-main">
            <Auditoria data={data} onErro={reportarErro} />
          </div>
        </div>
      ) : aba === "rel" && isAdmin ? (
        <Relatorios data={data} onErro={reportarErro} />
      ) : aba === "config" && isAdmin ? (
        <Ajustes
          data={data}
          operador={operador}
          posicaoAtual={posicaoAtual}
          agir={agir}
          ocupado={ocupado}
          isMobile={isMobile}
        />
      ) : (
        <Dashboard
          key={posicaoAtual}
          data={data}
          posicao={posicaoAtual}
          operador={operador}
          isAdmin={isAdmin}
          agir={agir}
          ocupado={ocupado}
          isMobile={isMobile}
        />
      )}
    </div>
  );
}
