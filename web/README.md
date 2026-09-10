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
| `POST /api/ordens/{id}/cargas/liberar` | fecha o passo aberto de cada carga da lista e a devolve ao pool (`ordemAtual = null`). **Não toca no lote nem no acoplamento.** | hub **Encerrar etapas** da home (`modals/EncerrarLote.tsx`) |
| `POST /api/ordens/{id}/lotes/finalizar` | fecha o lote corrente e abre o seguinte; a OS segue aberta. **Único caminho para o 2º lote.** | diálogo de confirmação `modals/ExpedirParcial.tsx`, aberto pelo botão **Expedir parcial** da Inspeção final |
| `POST /api/ordens/{id}/finalizar` | expedição total: libera as cargas restantes, fecha o lote corrente e encerra a OS. | **Expedir** (Inspeção final) e **Expedição total** (modal Expedir) |
| `POST /api/ordens/{id}/reabrir` | desfaz a expedição total: a OS volta a `emProcesso` e ganha um lote NOVO, vazio. Devolve, com ele, as cargas que aquela expedição soltou e que ainda estão livres — sugestão, não vínculo. 409 se a OS estiver em produção ou cancelada. | **Reabrir OS** no detalhe de uma OS expedida |

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

A expedição total é a única coisa aqui que se desfaz, e só de um jeito: a
reabertura **não** reabre lote nenhum — ela abre o número seguinte da sequência,
vazio, e deixa os lotes históricos como estão. O que se perde é o registro da
expedição desfeita: `finalizada_em`/`finalizada_por_id` são o que marca a OS
como concluída, então limpá-los apaga o evento `OS_EXPEDIDA` da auditoria e dos
relatórios; sobra o fecho daquele lote como vestígio. Foi uma escolha
deliberada, para não pagar uma tabela de reaberturas por um caso raro. Daí os
dois toques no botão.

O lote novo continua a nascer **sem cargas** — `finalizar` já as liberou, e
revinculá-las por conta própria seria inventar história. Mas o operador também
não pode ter de adivinhar quais eram as suas entre todas as cargas livres do
setor, e o histórico de passos não responde isso (ele diz que a carga passou
pela OS, não que estava lá no instante da expedição). Por isso `finalizar`
anota os ids que soltou em `os_cargas_expedidas` (V13) — estado efêmero, não
histórico — e `reabrir` lê essa lista, descarta o que caducou (carga sucateada,
tomada por outra OS, mudada de setor), **consome-a** e devolve o resto em
`cargasSugeridas`. Confirmando, o detalhe emenda direto no `VincularModal`, já
com essas cargas marcadas; o vínculo em si acontece quando o operador confirma
ali, um `POST /api/ordens/{id}/cargas` por carga, que é o que abre o passo
inicial. Desmarcar tudo é caminho válido — a OS fica reaberta e sem cargas.

O lado **carona** do acoplamento não volta nem é sugerido: as linhas que
`finalizar` apaga dizem "esta OS pegava carona na carga de outra", dependem de
essa outra OS ainda estar aberta, e nunca produziram linha no painel para esta
OS — a linha é sempre da titular. Quem precisar reacopla no detalhe da OS.

`liberarCargas` e `finalizarLote` compartilham o mesmo laço no service
(`OrdemServicoService.liberar`), então as validações não divergem: carga que
não esteja vinculada àquela OS → 422 `CARGA_NAO_VINCULADA`, e tudo numa
transação só.

Finalizar um passo isolado continua no detalhe da OS (Buscar OS ou Processos →
abrir a OS → "Finalizar" em cada passo em andamento) — isso nunca mexeu no lote.

## Cargas acopladas

Peças de 2-3 OS entram na **mesma carga física** e passam juntas pelos
processos. Três frases resumem o modelo, e toda a interface existe para
dizê-las no momento certo:

- **Uma carga, várias OS.** O tanque é um só; as peças lá dentro são de mais
  de uma ordem.
- **A titular é a dona da carga** (`Carga.ordemAtual`); as demais pegaram
  carona. Não é hierarquia — é de onde o registro pendura.
- **Enquanto a carga estiver vinculada, as peças da carona estão nela.** Não é
  só durante uma etapa: fechada a etapa, as peças continuam no tanque, e a
  seguinte é dos mesmos donos.

No banco são duas tabelas com papéis diferentes, e confundi-las é o erro fácil:

| Tabela | O que é | Vida |
|---|---|---|
| `carga_ordens_acopladas` (`V12`) | **estado**: quem está dentro da carga agora | dura o vínculo da carga com a OS |
| `log_ordens_acopladas` (`V11`) | **histórico**: quem estava dentro quando aquele passo rodou | congela quando o passo fecha |

Abrir um passo copia a primeira para a segunda. O passo continua sendo **uma
linha em `logs`**, com a titular em `ordem_servico_id` — é isso que faz um
evento físico contar **uma vez**: todo agregado do sistema deriva de linhas de
`logs`, e clonar o passo por OS inflaria a produção.

### Onde se acopla

No **vínculo da carga**, que é onde o operador tem o tanque na mão, e no
**cabeçalho do detalhe da OS**, que é onde se corrige depois:

| Caminho | Quando |
|---|---|
| Criar OS → passo 2, **+ Acoplar outra OS a uma carga** | a OS nova é a titular e leva peças de outras |
| Criar OS → Nº já existente → mesmo bloco | cargas novas para uma OS já aberta |
| Detalhe da OS → **Vincular cargas** → mesmo bloco | idem, a partir da OS |
| Detalhe da OS → cabeçalho → **+ Acoplar OS** (por carga) | acoplamento tardio: a carga já está na OS e já pode ter etapa a correr |
| Detalhe da OS → cabeçalho → **Acoplar a uma carga** | a OS **não tem carga própria** — nasceu sem nenhuma e vai inteira de carona |

Os dois últimos existem por causa da OS sem carga: `cargaIds` é opcional na
criação, então uma OS pode nascer só com o lote 1 e nenhum tanque. Enquanto
estiver assim ela não aparece na tabela do painel (que lista cargas) nem na
inspeção final (não há o que expedir) — aparece no aviso **"N OS sem carga"**
acima da tabela, que é por onde se chega ao detalhe dela. O predicado é
`emEspera` (`domain/derive.ts`): aberta, sem carga, não carona e **sem passo
nenhum**.

O componente é `modals/AcoplarCargas.tsx`, recolhido por omissão porque a
esmagadora maioria das cargas não acopla. Ele pede as duas coisas na ordem em
que existem: primeiro a **carga** (uma linha por carga já selecionada), depois
as **OS** que vão dentro dela.

Abrir etapa **não** pergunta nada: `POST /api/ordens/{id}/logs` já não aceita
lista de acopladas, e o service lê a composição da carga. É o ponto da mudança
— antes a lista morria com o passo e alguém tinha de a remarcar a cada tanque.

### O que acontece ao acoplar

`POST /api/ordens/cargas/{cargaId}/acopladas/{osId}`. Além de gravar o vínculo:

- **encerra os passos abertos da própria carona** — as peças saíram da carga
  dela. A carga dela continua vinculada à sua OS, vazia e aguardando etapa;
  devolvê-la ao pool é decisão do operador, em "Encerrar etapas";
- **injeta a carona no passo em curso** da carga de destino, se houver. Só a
  carona se move: o passo da titular não é reaberto nem substituído — abrir uma
  etapa nova só para reescrever a composição cortaria a duração real em duas e
  inventaria na linha do tempo um passo que ninguém executou.

O encerramento dos passos da carona **não tem volta simétrica**: `logs` é
append-only, então desacoplar depois não os reabre. Daí o segundo toque para
confirmar cada OS no seletor. Repetir a chamada é inócuo (idempotente): a
segunda só afirma o que já é verdade, sem fechar passo nenhum.

### O que muda depois de acoplar

| Onde | Efeito |
|---|---|
| **Inspeção final** | a OS carona **não aparece** enquanto estiver acoplada (`cargaCarona`). A tela lista OS sem carga vinculada, e carga emprestada conta como carga — mesmo depois de a carga voltar ao pool. Também não aparece a OS `emEspera`, que nunca produziu |
| **Detalhe da OS** | o passo aparece nas duas, marcado com `⇋` na carona. Finalizar encerra para todas, por isso pede **dois toques** |
| **Linha do tempo** | a barra é desenhada no grupo de cada OS, com `⇋`. Já os KPIs "Etapas iniciadas/concluídas" deduplicam por `log.id` — senão contariam o mesmo evento 2-3 vezes |
| **Dashboard** | selo `+N` na coluna Vínculo da carga compartilhada |
| **Abrir etapa** (detalhe e lote) | o modal nomeia quem mais vai no tanque antes de abrir — finalizar vai encerrar para todos |
| **Encerrar etapas** | cada linha diz quais OS caronas saem junto. São OS que o operador não selecionou; encerrá-las em silêncio seria o pior caso desta tela |
| **Planilha da OS** | bloco `ETAPA ACOPLADA — OS #x`, com subtotal próprio marcado como fora do total, e a coluna `Acoplada à OS` na aba Dados. Os indicadores (ETAPAS, CARGAS) seguem medindo só o que a OS executou |
| **Relatório por período** | coluna `Etapas acopladas` (contagem, sem tempo) e as linhas de carona na aba Etapas. Nelas a **duração fica vazia** de propósito: o tempo já está na linha da titular, e somar a coluna tem de continuar dando o tempo real |

### Como termina

Um caminho manual, e mais nada que o operador não tenha pedido — nem "a etapa
fechou", nem "a carga foi liberada":

| Caminho | O que faz |
|---|---|
| `DELETE /api/ordens/cargas/{cargaId}/acopladas/{osId}` | o `×` no chip (lado da titular) ou **Desacoplar** (lado da carona), ambos no **cabeçalho** do detalhe da OS. É a saída normal |
| carona expedida ou cancelada | sai da carga na hora, em `finalizar`/`cancelar`; e `acopladasVigentes` ainda varre as caducas na abertura da etapa seguinte |

**Encerrar etapas não desacopla.** Fechar a etapa não tira as peças da carona de
dentro do tanque: a carga volta ao pool **ainda a levá-las**, e quem a vincular
a seguir herda a composição (`vincularCarga` + `acopladasVigentes`) — que é o
que a física do tanque diz. Se a OS que vincula era ela própria uma das caronas,
o vínculo **promove-a a titular**: as peças são as mesmas, muda quem responde
pela carga.

Enquanto a carga está livre com caronas, `trg_coa_protege` não se opõe: ela é
`BEFORE INSERT OR UPDATE`, e a liberação não escreve nada. A recusa de "carga
sem titular" continua a valer onde importa — em `acoplarNaCarga`, que é um
INSERT e exige alguém a dar boleia.

O desacoplamento manual vive no cabeçalho, e não junto das etapas, justamente
porque o vínculo existe **entre** uma etapa e a seguinte — pendurá-lo no passo
aberto o faria desaparecer da tela metade do tempo. Ele sai da carga e do passo
em curso; os passos já fechados guardam a composição que tiveram, que é o que a
trigger `trg_loa_protege` garante no banco.

As recusas ao acoplar são todas de coerência física: OS de outra posição
(`ACOPLAMENTO_POSICAO_INCOMPATIVEL`), OS já expedida (409
`ORDEM_FORA_DE_CIRCULACAO`), a própria titular (`ACOPLAMENTO_A_SI_MESMA`), OS
que já pega carona noutra carga (`ACOPLAMENTO_EM_OUTRA_CARGA` — peças estão num
tanque só), carga sem OS (`CARGA_NAO_VINCULADA`) e o teto de 5 por carga
(`ACOPLAMENTO_EXCEDE_LIMITE`). Todas são verificadas **antes** de qualquer passo
ser fechado.

## Leitores RFID

Os botões "Ler crachá / Ler carga / Ler etiqueta" usam `window.prompt` como
stand-in do leitor: leem uma tag e resolvem por
`GET /api/operadores/por-tag/{tag}` e `GET /api/cargas/por-tag/{tag}`. Trocar o
prompt pelo evento do leitor real não muda nada além do ponto de entrada.

A API também expõe `POST /api/ordens/logs/tag` (abre um passo só com as três
tags, sem digitar o número da OS). Essa rota ainda não é usada aqui — o design
não tem tela para ela.
