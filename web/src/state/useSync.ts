import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "../api/endpoints";

/** Sonda de reserva, só enquanto o SSE estiver fora do ar. Folgada de
 *  propósito: é rede de segurança, não o mecanismo principal. */
const FALLBACK_MS = 15000;

/** Quedas seguidas do EventSource, sem um `open` pelo meio, antes de assumir
 *  que o push não vai voltar já e ligar a sonda. */
const QUEDAS_ATE_FALLBACK = 3;

interface Args {
  /** Marca do estado no momento em que os dados atuais foram lidos. */
  marca: string | null;
  recarregar: () => Promise<void>;
  /** Falso na tela de login e antes do primeiro boot: não há o que sincronizar. */
  ativo: boolean;
  ocupado: boolean;
  carregando: boolean;
}

/**
 * Mantém todos os terminais no mesmo ponto: o servidor avisa, por SSE, sempre
 * que o estado muda, e só então dispara o recarregar() — que é caro (catálogos
 * + ordens + um GET de logs por OS em processo).
 *
 * A comparação é sempre contra a marca lida junto com os dados em uso, então o
 * terminal que fez a mutação já está em dia e não recarrega duas vezes.
 *
 * A marca que o servidor anuncia fica num ref e é reavaliada quando o terminal
 * fica livre. Isto é mais importante aqui do que era no polling: a sonda antiga
 * podia saltar um tique e tentar de novo 4 s depois, mas um evento SSE
 * descartado no meio de uma mutação nunca se repete.
 */
export function useSync({ marca, recarregar, ativo, ocupado, carregando }: Args) {
  const [online, setOnline] = useState(true);

  // Refs para que a conexão não seja refeita a cada render/recarga.
  const atual = useRef({ marca, recarregar, ocupado, carregando });
  atual.current = { marca, recarregar, ocupado, carregando };

  /** Última marca anunciada pelo servidor; null antes do primeiro evento. */
  const marcaServidor = useRef<string | null>(null);

  /**
   * Recarrega se o servidor estiver à frente destes dados. Sair daqui sem fazer
   * nada é normal: a marca fica guardada e volta a ser avaliada quando a
   * mutação/recarga em curso terminar.
   */
  const avaliar = useCallback(() => {
    const { marca, ocupado, carregando, recarregar } = atual.current;
    if (marcaServidor.current === null || marca === null) return;
    if (ocupado || carregando) return;
    if (marcaServidor.current !== marca) void recarregar();
  }, []);

  useEffect(() => {
    if (!ativo) return;
    let vivo = true;
    let quedas = 0;
    let fallback: number | null = null;

    const pararFallback = () => {
      if (fallback !== null) {
        clearInterval(fallback);
        fallback = null;
      }
    };

    /** Corpo da sonda antiga: só corre quando o push está fora do ar. */
    const sondar = async () => {
      if (document.hidden) return;
      try {
        const r = await api.revisaoEstado();
        if (!vivo) return;
        setOnline(true);
        marcaServidor.current = `${r.instancia}:${r.revisao}`;
        avaliar();
      } catch {
        // Silencioso de propósito: um cabo solto viraria um toast a cada tique.
        if (vivo) setOnline(false);
      }
    };

    const es = new EventSource(api.ROTA_ESTADO_STREAM);

    es.onopen = () => {
      if (!vivo) return;
      quedas = 0;
      pararFallback();
      setOnline(true);
    };

    // A conexão manda a marca atual assim que abre, então cada reconexão já
    // ressincroniza o terminal sem precisar de um GET à parte.
    es.addEventListener("revisao", (ev) => {
      if (!vivo) return;
      try {
        const r = JSON.parse((ev as MessageEvent<string>).data) as {
          instancia: string;
          revisao: number;
        };
        marcaServidor.current = `${r.instancia}:${r.revisao}`;
      } catch {
        return; // evento malformado: o próximo corrige
      }
      setOnline(true);
      avaliar();
    });

    es.onerror = () => {
      if (!vivo) return;
      // O EventSource reconecta sozinho; só depois de algumas tentativas
      // falhadas é que vale a pena acordar a sonda.
      quedas += 1;
      if (quedas < QUEDAS_ATE_FALLBACK || fallback !== null) return;
      setOnline(false);
      fallback = window.setInterval(() => void sondar(), FALLBACK_MS);
    };

    // Voltar para a aba não espera nada: o browser pode ter suspendido a
    // conexão em segundo plano e perdido eventos, então confirma-se na hora.
    const aoVoltar = () => {
      if (!document.hidden) void sondar();
    };
    document.addEventListener("visibilitychange", aoVoltar);
    window.addEventListener("focus", aoVoltar);

    return () => {
      vivo = false;
      es.close();
      pararFallback();
      document.removeEventListener("visibilitychange", aoVoltar);
      window.removeEventListener("focus", aoVoltar);
    };
  }, [ativo, avaliar]);

  // O terminal ficou livre: consome o aviso que chegou enquanto estava ocupado.
  useEffect(() => {
    if (ativo) avaliar();
  }, [ativo, ocupado, carregando, marca, avaliar]);

  return { online };
}
