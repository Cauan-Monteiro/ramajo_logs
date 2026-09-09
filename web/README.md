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
| **Expedição parcial** (modal Expedir) | Nada — a operação vive na Inspeção final; ver abaixo |
| **Reativar** carga (Registrar cargas) | `DELETE /api/cargas/{id}` só desativa; não há rota de reativação |

### Liberar cargas vs. virar o lote

As duas operações moravam na mesma rota, e o resultado era que a rotina de chão
de fábrica jogava as OS em "2º lote" sem ninguém ter expedido nada. Hoje são
rotas separadas:

| Rota | Efeito | Quem chama |
|---|---|---|
| `POST /api/ordens/{id}/cargas/liberar` | fecha o passo aberto de cada carga da lista e a devolve ao pool (`ordemAtual = null`). **Não toca no lote.** | hub **Encerrar etapas** da home (`modals/EncerrarLote.tsx`) |
| `POST /api/ordens/{id}/lotes/finalizar` | fecha o lote corrente e abre o seguinte; a OS segue aberta. **Único caminho para o 2º lote.** | diálogo de confirmação `modals/ExpedirParcial.tsx`, aberto pelo botão **Expedir parcial** da Inspeção final |
| `POST /api/ordens/{id}/finalizar` | expedição total: libera as cargas restantes, fecha o lote corrente e encerra a OS. | **Expedir** (Inspeção final) e **Expedição total** (modal Expedir) |

A Inspeção final só lista OS que já não têm carga vinculada, por isso o
"Expedir parcial" manda `cargaIds` vazio — não há carga a escolher. É por isso
também que o botão **Expedição parcial** do modal Expedir (detalhe da OS)
continua desabilitado: ali a OS ainda tem cargas, e a decisão de liberá-las é
do hub Encerrar etapas.

Sendo irreversível, a expedição parcial não acontece no clique: o botão abre
`ExpedirParcialModal`, e só a confirmação chama a API. Como a OS fica aberta à
espera de novas cargas, o diálogo já permite escolhê-las (mesma seleção por
chips e `ScanField` do vínculo normal); confirmando, ele emenda um
`POST /api/ordens/{id}/cargas` por carga **depois** do `lotes/finalizar` — nessa
ordem, senão as cargas novas cairiam no lote que está sendo expedido. Não
escolher nenhuma é um caminho válido: o lote vira e as cargas entram depois,
pelo detalhe da OS.

`liberarCargas` e `finalizarLote` compartilham o mesmo laço no service
(`OrdemServicoService.liberar`), então as validações não divergem: carga que
não esteja vinculada àquela OS → 422 `CARGA_NAO_VINCULADA`, e tudo numa
transação só.

Finalizar um passo isolado continua no detalhe da OS (Buscar OS ou Processos →
abrir a OS → "Finalizar" em cada passo em andamento) — isso nunca mexeu no lote.

## Etapas acopladas

Peças de 2-3 OS entram na **mesma carga física** e passam juntas por um
processo. Três frases resumem o modelo, e toda a interface existe para
dizê-las no momento certo:

- **Uma etapa, uma carga, várias OS.** O tanque é um só; as peças lá dentro
  são de mais de uma ordem.
- **A titular é a dona da carga** (`Carga.ordemAtual`); as demais pegaram
  carona. Não é hierarquia — é de onde o registro pendura.
- **Enquanto a etapa está aberta, as peças da carona estão no tanque.**

No banco (migration `V11`) o passo continua sendo **uma linha em `logs`**, com
a titular em `ordem_servico_id`; as caronas ficam em `log_ordens_acopladas`.
É isso que faz um evento físico contar **uma vez**: todo agregado do sistema
deriva de linhas de `logs`, e clonar o passo por OS inflaria a produção.

### Onde se acopla

| Caminho | Quando |
|---|---|
| Home → marcar **1 carga** → **Abrir etapa** | rotina do dia a dia. Com 2+ cargas a seção some: não haveria como dizer em qual tanque as peças das outras OS entraram |
| Buscar OS → OS → **Abrir etapa** | quando se parte de uma OS específica |
| Detalhe da OS → etapa em andamento → **+ acoplar** | **acoplamento tardio**: as peças entraram no tanque depois de o passo já ter começado |

Os dois primeiros usam o mesmo `modals/AcoplarOs.tsx` — recolhido por omissão,
porque a esmagadora maioria dos passos não acopla.

### Acoplar depois de a etapa começar

`POST /api/ordens/logs/{logId}/acopladas/{osId}`, pelo `AcoplarAgora` do mesmo
arquivo. **Só a carona se move**: o passo da titular não é reaberto nem
substituído — abrir uma etapa nova só para reescrever a composição cortaria a
duração real em duas e inventaria na linha do tempo um passo que ninguém
executou.

No mesmo instante, os passos abertos da **própria carona** são encerrados: as
peças saíram da carga dela. A carga continua vinculada à OS, vazia e aguardando
etapa — devolvê-la ao pool é decisão do operador, em "Encerrar etapas".

Isso **não tem volta simétrica**: `logs` é append-only, então desacoplar depois
não reabre o passo fechado aqui. Daí o segundo toque para confirmar, como no
encerramento de passo acoplado. Repetir a chamada é inócuo (idempotente): a
segunda só afirma o que já é verdade, sem fechar passo nenhum.

### O que muda depois de acoplar

| Onde | Efeito |
|---|---|
| **Inspeção final** | a OS carona **não aparece** enquanto o passo estiver aberto (`temAcoplamentoAberto`). A tela lista OS sem carga vinculada, e carga emprestada conta como carga |
| **Detalhe da OS** | o passo aparece nas duas, marcado com `⇋` na carona. Finalizar encerra para todas, por isso pede **dois toques** |
| **Linha do tempo** | a barra é desenhada no grupo de cada OS, com `⇋`. Já os KPIs "Etapas iniciadas/concluídas" deduplicam por `log.id` — senão contariam o mesmo evento 2-3 vezes |
| **Dashboard** | selo `+N` na coluna Vínculo da carga compartilhada |
| **Planilha da OS** | bloco `ETAPA ACOPLADA — OS #x`, com subtotal próprio marcado como fora do total, e a coluna `Acoplada à OS` na aba Dados. Os indicadores (ETAPAS, CARGAS) seguem medindo só o que a OS executou |
| **Relatório por período** | coluna `Etapas acopladas` (contagem, sem tempo) e as linhas de carona na aba Etapas. Nelas a **duração fica vazia** de propósito: o tempo já está na linha da titular, e somar a coluna tem de continuar dando o tempo real |

### Corrigir

`DELETE /api/ordens/logs/{logId}/acopladas/{osId}` desfaz um acoplamento —
o `×` no chip (lado da titular) ou o botão **Desacoplar** (lado da carona).
Só com o passo **aberto**: fechado, a composição é histórico e a API devolve
409 `PASSO_JA_FINALIZADO`, a mesma regra que a trigger `trg_loa_protege`
garante no banco.

As recusas ao acoplar são todas de coerência física: OS de outra posição
(`ACOPLAMENTO_POSICAO_INCOMPATIVEL`), OS já expedida (409
`ORDEM_FORA_DE_CIRCULACAO`), a própria OS (`ACOPLAMENTO_A_SI_MESMA`) e o teto
de 5 por passo (`ACOPLAMENTO_EXCEDE_LIMITE`). O acoplamento tardio acrescenta
duas: OS que já pega carona noutro passo aberto (`ACOPLAMENTO_EM_OUTRO_PASSO`
— peças estão num tanque só) e passo cancelado (`ACOPLAMENTO_PASSO_CANCELADO`,
que afirma que o processo não aconteceu). Todas são verificadas **antes** de
qualquer passo ser fechado.

## Leitores RFID

Os botões "Ler crachá / Ler carga / Ler etiqueta" usam `window.prompt` como
stand-in do leitor: leem uma tag e resolvem por
`GET /api/operadores/por-tag/{tag}` e `GET /api/cargas/por-tag/{tag}`. Trocar o
prompt pelo evento do leitor real não muda nada além do ponto de entrada.

A API também expõe `POST /api/ordens/logs/tag` (abre um passo só com as três
tags, sem digitar o número da OS). Essa rota ainda não é usada aqui — o design
não tem tela para ela.
