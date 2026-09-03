# RAMAJO · Tratamento — frontend

Terminal de chão de fábrica para a API em `../system_API`. É a implementação do
design `App Historico OS v2.dc.html` (Claude Design, projeto
`4635a19d-bfec-4db8-ab74-b4461a8781eb`) sobre dados reais da API — o design
rodava sobre um dataset fictício embutido.

React + Vite + TypeScript, sem framework de UI: o design system importado
(`_ds/industry-…`) é CSS-only, e o `_ds_bundle.js` dele está vazio.

## Rodar

A stack inteira (Postgres + API + este front servido por nginx) sobe pelo
`docker-compose.yaml` **na raiz do repositório**:

```bash
docker compose up --build    # app em http://localhost
```

Para desenvolver o front com hot reload, basta a API estar de pé em
`localhost:8080` — pelo Compose acima ou direto pelo Maven:

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

Em produção quem faz esse papel é o **nginx** do `web/Dockerfile`: ele serve o
`dist/` e proxia `/api/` para `http://api:8080`, mantendo front e API na mesma
origem sem tocar no Spring. Como todo `fetch` usa caminho relativo, a imagem é
portátil — não há URL de API embutida no build, e o mesmo container funciona em
qualquer host ou IP.

## Acesso pela rede local

Outros dispositivos da mesma rede abrem o app pelo IP do host, ex.
`http://192.168.0.106`. Duas coisas são necessárias:

1. **Só a porta 80 é publicada na rede.** No `docker-compose.yaml` da raiz, as
   portas do `db` (5432) e da `api` (8080) estão presas a `127.0.0.1` — elas
   existem para DBeaver/psql e para o proxy do Vite, e não precisam sair da
   máquina. O nginx alcança a API pela rede interna do Compose.
2. **Liberar a porta 80 no firewall do Windows.** Em PowerShell **como
   Administrador**:

   ```powershell
   New-NetFirewallRule -DisplayName "Ramajo web LAN 80" -Direction Inbound `
     -Action Allow -Protocol TCP -LocalPort 80 -Profile Public `
     -RemoteAddress 192.168.0.0/24
   ```

   `-Profile Public` atende a rede Wi-Fi sem reclassificá-la como Private, e
   `-RemoteAddress` limita a origem à sub-rede doméstica (ajuste se a sua for
   outra). Para desfazer: `Remove-NetFirewallRule -DisplayName "Ramajo web LAN 80"`.

O IP vem de DHCP e pode mudar ao reiniciar o roteador; uma reserva por MAC evita
ter de reavisar todo mundo.

> **Sem autenticação e sem TLS.** A API não tem Spring Security e o "login" é
> apenas a escolha do operador no browser — quem alcança a porta 80 pode chamar
> qualquer mutação. Exponha só em rede confiável.

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
| `Etapa` de um passo (chip colorido) | `LogDTO.processoDescricao` cruzado com `GET /api/processos` — o LogDTO não traz etapa nem processoId. É por isso que a rota devolve também os processos arquivados (`ativo: false`): sem eles, todo passo de um processo arquivado perderia a cor da etapa. Quem *oferece* processo ao utilizador é que filtra por `ativo` |
| id da carga de um passo | `LogDTO.cargaNome` cruzado com `GET /api/cargas` |
| cliente de uma OS na listagem | `OrdemResumoDTO` só tem `clienteNome`, não `clienteId` |
| `finalizadaEm` de uma OS | só no `OrdemDetalheDTO` — o relatório de tempo médio busca cada OS encerrada |
| busca por `idExterno` | não existe rota; o filtro roda sobre a lista já carregada |

## Modo de visualização mobile

O padrão continua o quadro de **1180 × 820** do design. Abaixo de 480px ele
passa para **360 × 780** — a proporção do Galaxy S24 em retrato (1080 × 2340
físicos, DPR 3).

A troca é **automática e só pela largura da tela**: `matchMedia("(max-width:
480px)")` em `src/state/useViewMode.ts`. Não há botão de alternância — para
conferir a proporção no desktop, use o modo dispositivo do DevTools.

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
| **Expedição parcial** (modal Expedir) | Nada — falta a UI de seleção de cargas neste diálogo |
| **Expedir parcial** (Inspeção final) | idem |
| **Reativar** carga (Registrar cargas) | `DELETE /api/cargas/{id}` só desativa; não há rota de reativação |
| **Desidrogenizar** (Inspeção final) | Etapa não modelada — já vinha desabilitada no próprio design |

O patch `cargaIds` (`entrega/PATCH-finalizarLote.md` no projeto de design)
**já foi aplicado**: `FinalizarLoteDTO` ganhou `List<Long> cargaIds` opcional e
`OrdemServicoService.finalizarLote` agora fecha o passo aberto de cada carga
listada e a libera (`ordemAtual = null`) antes de virar o lote. Carga que não
esteja vinculada àquela OS → 422 `CARGA_NAO_VINCULADA`.

Quem usa isso hoje é o hub **Encerrar etapas** da home (`modals/EncerrarLote.tsx`):
seleciona-se cargas na tabela e cada OS afetada leva um POST com os seus
`cargaIds`. Os dois botões de expedição parcial continuam desabilitados apenas
porque a UI de escolher *quais* cargas ainda não existe nesses diálogos — a rota
já os atende.

Finalizar um passo isolado continua no detalhe da OS (Buscar OS ou Processos →
abrir a OS → "Finalizar" em cada passo em andamento).

## Leitores RFID

Os botões "Ler crachá / Ler carga / Ler etiqueta" usam `window.prompt` como
stand-in do leitor: leem uma tag e resolvem por
`GET /api/operadores/por-tag/{tag}` e `GET /api/cargas/por-tag/{tag}`. Trocar o
prompt pelo evento do leitor real não muda nada além do ponto de entrada.

A API também expõe `POST /api/ordens/logs/tag` (abre um passo só com as três
tags, sem digitar o número da OS). Essa rota ainda não é usada aqui — o design
não tem tela para ela.
