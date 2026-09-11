import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { DesidroEmAndamentoDTO } from "../api/types";
import { JANELA_ESTOURADA_MIN, estouradas, porUrgencia, proximoFim } from "../domain/desidro";

const KEY_CIENTES = "ramajo.desidroCiente";
const KEY_ARMADOS = "ramajo.desidroArmados";

/** Rede de segurança do relógio: numa aba escondida o navegador pode atrasar
 *  o timer exato, e a ponta "há X min" também precisa andar. */
const REDE_MS = 30000;

/** Folga depois do término: acordar um tique antes do carimbo não mostraria nada. */
const FOLGA_MS = 250;

/** Duração de um ciclo Temporal-3: três toques e a pausa longa. */
const CICLO_T3_MS = 4000;

/** Quanto "Silenciar" cala o alarme sem dar Ciente. */
export const SILENCIO_MS = 5 * 60000;

function lerIds(key: string): Set<number> {
  try {
    const arr: unknown = JSON.parse(localStorage.getItem(key) ?? "[]");
    return new Set(Array.isArray(arr) ? arr.filter((x): x is number => typeof x === "number") : []);
  } catch {
    return new Set();
  }
}

function gravarIds(key: string, ids: Set<number>) {
  try {
    localStorage.setItem(key, JSON.stringify([...ids]));
  } catch {
    // modo privado / storage bloqueado: o estado vale só até o próximo F5
  }
}

/** Mesmo Set quando nada muda, para não re-renderizar nem regravar à toa. */
function soVivos(atual: Set<number>, vivos: Set<number>): Set<number> {
  const podado = new Set([...atual].filter((id) => vivos.has(id)));
  return podado.size === atual.size ? atual : podado;
}

type AudioCtor = typeof AudioContext;

/**
 * Um ciclo do padrão Temporal-3 (ISO 8201 / NFPA 72) — o toque do alarme de
 * incêndio: 0,5 s ligado, 0,5 s desligado, três vezes, e 1,5 s de pausa. Foi
 * escolhido por ser reconhecido como "aja agora" e, ao contrário de uma sirene
 * contínua, deixar respirar entre as rajadas.
 *
 * Gerado na hora — sem arquivo de áudio para servir. As rampas de 10 ms na
 * entrada e na saída evitam o estalo de ligar e desligar a onda a seco.
 * Devolve os osciladores para quem quiser cortá-los antes do fim.
 */
function cicloT3(ctx: AudioContext): OscillatorNode[] {
  const t0 = ctx.currentTime;
  return [0, 1, 2].map((i) => {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "square";
    osc.frequency.value = 950;
    const ini = t0 + i * 1.0;
    const fim = ini + 0.5;
    gain.gain.setValueAtTime(0.0001, ini);
    gain.gain.exponentialRampToValueAtTime(0.25, ini + 0.01);
    gain.gain.setValueAtTime(0.25, fim - 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, fim);
    osc.connect(gain).connect(ctx.destination);
    osc.start(ini);
    osc.stop(fim + 0.01);
    return osc;
  });
}

/**
 * O alerta de desidrogenização estourada: o que ainda ninguém reconheceu NESTE
 * terminal.
 *
 * O estouro é um evento de tempo, não de dado — o servidor não muda nada quando
 * o `finalizadaEm` passa, então o useSync não tem o que avisar. Por isso o
 * relógio daqui não anda a passo fixo como o useAgora: agenda-se para o próximo
 * término, e o alerta acende no segundo em que ele chega.
 *
 * O "Ciente" é por terminal (localStorage): cada posição de fábrica precisa de
 * ver o aviso com os próprios olhos, e um clique noutra sala não prova isso.
 *
 * Disparo: um estouro só ACENDE se este terminal o perceber nos primeiros
 * JANELA_ESTOURADA_MIN depois do término — é o momento em que ainda dá para
 * correr ao forno. Terminal desligado, aba fechada ou máquina a dormir voltam
 * sem alarme pelo que passou entretanto. Aceso, o alerta fica "armado"
 * (localStorage, para um F5 não o engolir) até o "Ciente", com o teto de
 * JANELA_ALERTA_MIN. No login (`ativo` falso) nada arma.
 *
 * Som: o Temporal-3 toca SEM PARAR enquanto houver pendente, até o "Ciente".
 * "Silenciar" cala por SILENCIO_MS sem esconder a faixa, e só em memória — um
 * F5 devolve o som, que para alarme é o lado seguro do erro. Um estouro novo
 * derruba o silêncio: o que foi calado era outro aviso.
 *
 * Política de autoplay: o navegador só deixa tocar depois de um gesto na
 * página. Entrar já é um, mas depois de um F5 o alarme só começa no ciclo
 * seguinte ao primeiro clique ou tecla — a faixa, essa, aparece sempre.
 *
 * `ativo` falso (login, antes do primeiro boot) desliga tudo: a lista ainda é a
 * vazia inicial, e podar os "Ciente" contra ela apagaria todos.
 */
export function useAlertaDesidro(ds: DesidroEmAndamentoDTO[], ativo: boolean) {
  const [agora, setAgora] = useState(() => Date.now());
  const [cientes, setCientes] = useState<Set<number>>(() => lerIds(KEY_CIENTES));
  const [armados, setArmados] = useState<Set<number>>(() => lerIds(KEY_ARMADOS));

  // Dados novos releem o relógio na hora: uma desidro que terminou durante a
  // recarga não pode esperar pelo próximo tique da rede.
  useEffect(() => {
    setAgora(Date.now());
  }, [ds]);

  useEffect(() => {
    const tique = () => setAgora(Date.now());
    const t = setInterval(tique, REDE_MS);
    const aoVoltar = () => {
      if (!document.hidden) tique();
    };
    document.addEventListener("visibilitychange", aoVoltar);
    return () => {
      clearInterval(t);
      document.removeEventListener("visibilitychange", aoVoltar);
    };
  }, []);

  // O tique exato: reagendado a cada mudança de `agora`, então sempre mira o
  // término mais próximo que ainda não passou.
  useEffect(() => {
    const prox = proximoFim(ds, agora);
    if (prox === null) return;
    const t = setTimeout(() => setAgora(Date.now()), prox - Date.now() + FOLGA_MS);
    return () => clearTimeout(t);
  }, [ds, agora]);

  // Poda: um id que saiu da lista (OS expedida) nunca mais volta, e guardá-lo
  // só faria a chave crescer para sempre.
  useEffect(() => {
    if (!ativo) return;
    const vivos = new Set(ds.map((d) => d.id));
    setCientes((atual) => soVivos(atual, vivos));
    setArmados((atual) => soVivos(atual, vivos));
  }, [ds, ativo]);

  // Armar: o relógio lido aqui é o de agora, não o `agora` do estado — num
  // render em que `ds` chega antes do tique pós-sono, esse ainda é o de antes de
  // dormir e armaria estouros de horas atrás.
  useEffect(() => {
    if (!ativo) return;
    const recentes = estouradas(ds, Date.now(), JANELA_ESTOURADA_MIN);
    setArmados((atual) => {
      const novos = recentes.filter((d) => !atual.has(d.id));
      return novos.length === 0 ? atual : new Set([...atual, ...novos.map((d) => d.id)]);
    });
  }, [ds, agora, ativo]);

  useEffect(() => {
    gravarIds(KEY_CIENTES, cientes);
  }, [cientes]);

  useEffect(() => {
    gravarIds(KEY_ARMADOS, armados);
  }, [armados]);

  const pendentes = useMemo(
    () => (ativo
      ? porUrgencia(
        estouradas(ds, agora).filter((d) => armados.has(d.id) && !cientes.has(d.id)),
        agora,
      )
      : []),
    [ds, agora, cientes, armados, ativo],
  );

  const ciente = useCallback((id: number) => {
    setCientes((atual) => new Set(atual).add(id));
  }, []);

  const cienteTodas = useCallback((ids: number[]) => {
    setCientes((atual) => new Set([...atual, ...ids]));
  }, []);

  // — som —

  const audio = useRef<AudioContext | null>(null);

  const garantirAudio = useCallback((): AudioContext | null => {
    try {
      if (!audio.current) {
        const Ctor: AudioCtor | undefined = window.AudioContext
          ?? (window as typeof window & { webkitAudioContext?: AudioCtor }).webkitAudioContext;
        if (!Ctor) return null;
        audio.current = new Ctor();
      }
      if (audio.current.state === "suspended") void audio.current.resume();
      return audio.current;
    } catch {
      return null;
    }
  }, []);

  // Os osciladores do ciclo em curso: o "Ciente" corta o som na hora, em vez de
  // deixar o resto da rajada tocar por até 2,5 s.
  const soando = useRef<OscillatorNode[]>([]);

  const tocar = useCallback(() => {
    const ctx = garantirAudio();
    if (ctx?.state === "running") soando.current = cicloT3(ctx);
  }, [garantirAudio]);

  const calar = useCallback(() => {
    for (const osc of soando.current) {
      try {
        osc.stop();
      } catch {
        // já tinha parado sozinho
      }
    }
    soando.current = [];
  }, []);

  // Qualquer gesto destrava o áudio para o próximo estouro.
  useEffect(() => {
    const destravar = () => void garantirAudio();
    window.addEventListener("pointerdown", destravar);
    window.addEventListener("keydown", destravar);
    return () => {
      window.removeEventListener("pointerdown", destravar);
      window.removeEventListener("keydown", destravar);
    };
  }, [garantirAudio]);

  useEffect(() => () => {
    void audio.current?.close();
    audio.current = null;
  }, []);

  // — silenciar —

  const [silenciadoAte, setSilenciadoAte] = useState<number | null>(null);

  const silenciar = useCallback(() => setSilenciadoAte(Date.now() + SILENCIO_MS), []);
  const reativarSom = useCallback(() => setSilenciadoAte(null), []);

  useEffect(() => {
    if (silenciadoAte === null) return;
    const t = setTimeout(() => setSilenciadoAte(null), silenciadoAte - Date.now());
    return () => clearTimeout(t);
  }, [silenciadoAte]);

  // Um estouro que ainda não estava na faixa derruba o silêncio. A chave em
  // texto evita disparar a cada recálculo de `pendentes` com os mesmos ids.
  const vistos = useRef<Set<number>>(new Set());
  const chave = pendentes.map((d) => d.id).join(",");
  useEffect(() => {
    const atuais = new Set(chave ? chave.split(",").map(Number) : []);
    const novo = [...atuais].some((id) => !vistos.current.has(id));
    vistos.current = atuais;
    if (novo) setSilenciadoAte(null);
  }, [chave]);

  // O loop do alarme. Aba que está a tocar áudio não tem os timers estrangulados
  // pelo navegador, então o ciclo segura-se mesmo em segundo plano.
  const tocando = pendentes.length > 0 && silenciadoAte === null;
  useEffect(() => {
    if (!tocando) return;
    tocar();
    const t = setInterval(tocar, CICLO_T3_MS);
    return () => {
      clearInterval(t);
      calar();
    };
  }, [tocando, tocar, calar]);

  return { pendentes, ciente, cienteTodas, silenciadoAte, silenciar, reativarSom };
}
