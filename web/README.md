# RAMAJO · Tratamento — frontend

Terminal de chão de fábrica para a API em `../system_API`. É a implementação do
design `App Historico OS v2.dc.html` (Claude Design, projeto
`4635a19d-bfec-4db8-ab74-b4461a8781eb`) sobre dados reais da API — o design
rodava sobre um dataset fictício embutido.

React + Vite + TypeScript, sem framework de UI: o design system importado
(`_ds/industry-…`) é CSS-only, e o `_ds_bundle.js` dele está vazio.

## Rodar

A API precisa estar de pé em `localhost:8080` com o Postgres do
`system_API/docker-compose.yml`:

```bash
cd ../system_API && ./mvnw spring-boot:run   # exige JDK 17+ (Spring Boot 4.1)
cd ../web && npm install && npm run dev      # http://localhost:5173
```

> **JDK**: se `./mvnw` falhar com `PluginContainerException` no
> `spring-boot-maven-plugin`, o `java` do PATH é antigo. Aponte o `JAVA_HOME`
> para um JDK 17+ antes de subir a API.

### CORS

A API **não tem CORS configurado**. Em desenvolvimento isso é resolvido pelo
proxy do Vite (`vite.config.ts`): tudo em `/api` é encaminhado para
`localhost:8080`, então front e API compartilham a origem e todo `fetch` usa
caminho relativo.

Para produção, sirva o `dist/` na mesma origem da API (por exemplo, copiando-o
para `system_API/src/main/resources/static`) **ou** adicione uma configuração de
CORS no Spring. Nada aqui depende de estar em `localhost:5173`.

## Estrutura

```
src/
  api/        types.ts (espelho dos DTOs Java) · client.ts (fetch + ApiError) · endpoints.ts
  domain/     format.ts (datas, rótulos) · derive.ts (joins, cores por etapa)
  state/      useSession.ts (operador do turno) · useAppData.ts (carga e recarga)
  components/ AppNav · Modal · Toast · Icons · Blueprint
  screens/    Login · Dashboard · RegistrarCargas · Relatorios
  modals/     um arquivo por diálogo do design
  styles.css  tokens do design system + o <style> do design, portados
```

`useAppData` recarrega tudo da API depois de cada mutação. Não há estado
otimista de propósito: regras como "abrir um passo fecha o anterior da mesma
carga" vivem no `OrdemServicoService` e o cliente não tem como replicá-las com
fidelidade.

### Joins que os DTOs não trazem prontos

| Precisa de | Vem de |
|---|---|
| `Etapa` de um passo (chip colorido) | `LogDTO.processoDescricao` cruzado com `GET /api/processos` — o LogDTO não traz etapa nem processoId |
| id da carga de um passo | `LogDTO.cargaNome` cruzado com `GET /api/cargas` |
| cliente de uma OS na listagem | `OrdemResumoDTO` só tem `clienteNome`, não `clienteId` |
| `finalizadaEm` de uma OS | só no `OrdemDetalheDTO` — o relatório de tempo médio busca cada OS encerrada |
| busca por `idExterno` | não existe rota; o filtro roda sobre a lista já carregada |

## Modo de visualização mobile

O padrão continua o quadro de **1180 × 820** do design. O modo mobile é uma
opção que passa o quadro para **360 × 780** — a proporção do Galaxy S24 em
retrato (1080 × 2340 físicos, DPR 3).

Ativa de duas formas:
- **Automático**: `matchMedia("(max-width: 480px)")` em `src/state/useViewMode.ts`.
- **Botão** na barra de navegação, ao lado de "Sair". Clicar **fixa** a escolha
  no localStorage (`ramajo.viewmode`); a partir daí a largura da tela deixa de
  mandar. Serve para conferir a proporção do S24 sem sair do desktop.

A media query mora no JS de propósito: com as duas vias em CSS
(`@media` + classe) cada regra teria de existir duas vezes. O hook põe a classe
`is-mobile` no `<body>`, e o CSS tem um caminho único — todo o bloco 4 de
`styles.css` é prefixado por `body.is-mobile`.

O que muda em mobile: navegação em duas faixas (abas roláveis na segunda),
hub de ações 4 → 2 colunas, cada linha da tabela de cargas vira um cartão de
2 linhas (`grid-template-areas`), o rail da direita vira faixa no rodapé,
os diálogos viram folha de tela cheia, e em Registrar cargas as colunas Tipo e
Tag são ocultadas. As tabelas de Relatórios rolam na horizontal — é tela
administrativa, não operação de chão de fábrica.

Algumas larguras fixas moravam em `style` inline, que vence CSS; foram
extraídas para classes com **os mesmos valores** (`.login-card`, `.rel-side`,
`.hub-grid`, `.dash-body`, `.dash-main`, `.cargas-body`, `.rel-body`,
`.rel-main`, `.nc-acoes`). O desktop não mudou.

## Pendências de backend

Estes controles existem na tela (como no design), mas ficam **desabilitados**,
com o motivo no `title`. Os textos estão centralizados em
`src/modals/tipos.ts` → `SEM_API`.

| Controle | Falta na API |
|---|---|
| **Encerrar etapas** (hub) | Não há como desvincular uma carga fora de `finalizar`/`finalizarLote` |
| **Expedição parcial** (modal Expedir) | `POST /api/ordens/{id}/lotes/finalizar` aceita só `operadorId`: avança o lote sem liberar carga nenhuma |
| **Expedir parcial** (Inspeção final) | idem |
| **Reativar** carga (Registrar cargas) | `DELETE /api/cargas/{id}` só desativa; não há rota de reativação |
| **Desidrogenizar** (Inspeção final) | Etapa não modelada — já vinha desabilitada no próprio design |

O patch que destrava os três primeiros está escrito: `entrega/PATCH-finalizarLote.md`
no projeto de design (adiciona `cargaIds` ao `FinalizarLoteDTO`, ao service e ao
controller). Depois de aplicá-lo, ligar os botões é trocar o `disabled` por uma
chamada a `POST /api/ordens/{id}/lotes/finalizar` com as cargas selecionadas.

Enquanto isso, **finalizar passos continua disponível** pelo detalhe da OS
(Buscar OS ou Processos → abrir a OS → "Finalizar" em cada passo em andamento) —
só o encerramento em lote com desvínculo é que não tem caminho.

## Leitores RFID

Os botões "Ler crachá / Ler carga / Ler etiqueta" usam `window.prompt` como
stand-in do leitor: leem uma tag e resolvem por
`GET /api/operadores/por-tag/{tag}` e `GET /api/cargas/por-tag/{tag}`. Trocar o
prompt pelo evento do leitor real não muda nada além do ponto de entrada.

A API também expõe `POST /api/ordens/logs/tag` (abre um passo só com as três
tags, sem digitar o número da OS). Essa rota ainda não é usada aqui — o design
não tem tela para ela.
