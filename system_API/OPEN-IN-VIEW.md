# Desligar o `open-in-view`

Plano guardado. **Não está aplicado** — este documento existe para que a migração possa
ser feita noutro momento sem refazer a investigação.

Levantamento feito em 2026-09-22, sobre o código logo após a otimização da aba Visão
Geral (as rotas em lote `/api/ordens/logs` e `/api/ordens/auditoria` já existem).

## O problema

`spring.jpa.open-in-view` não está definido em `src/main/resources/application.properties`,
logo vale `true`. Com ele ligado, a sessão do Hibernate fica aberta até a resposta HTTP ser
serializada — e os DTOs são montados **no controller**, depois de o `@Transactional` do
service já ter retornado:

```java
// controllers/OrdemServicoController.java:139
return OrdemDetalheDTO.from(service.buscarDetalhe(id));
//     ^ toca associações LAZY aqui, fora de qualquer transação
```

Cada toque LAZY desses vira uma query extra, silenciosa. Sem exceção, sem log de erro. Só
lentidão.

**Isto não é teórico.** Foi exatamente esse mecanismo que deixou o N+1 da Visão Geral
passar despercebido por todo o desenvolvimento da aba: `LogRepository.buscarHistorico` não
tinha `join fetch`, o `LogDTO.from` desreferenciava quatro `@ManyToOne` por passo, e a
única evidência era a aba demorar. Com o open-in-view desligado, aquilo teria estourado
`LazyInitializationException` na primeira execução.

Desligar não acelera nada por si só. **Converte uma classe inteira de bug invisível em
falha alta, na linha exata, que o teste pega.** É trava, não motor.

## Inventário: o que quebra

Das **52 chamadas `DTO.from`** em 7 controllers, **20 endpoints quebram**. O resto já está
coberto — ou porque a consulta faz `join fetch`, ou porque a entidade é nova (recém-salva,
coleções já inicializadas), ou porque o DTO não lê campo LAZY nenhum.

> **`@BatchSize` não ajuda aqui.** Ele reduz o N+1 *enquanto a sessão está aberta*; com o
> open-in-view desligado a sessão já fechou, e uma coleção com `@BatchSize` estoura
> `LazyInitializationException` exatamente como uma sem. As anotações que existem em
> `OrdemServico` e `Log` são irrelevantes para esta migração.

### Por controller

| Controller | Quebram | Causa raiz |
|---|---|---|
| `ClienteController` | **0** | `Cliente` não tem nenhuma associação LAZY |
| `OperadorController` | **0** | `Operador` não tem nenhuma associação LAZY |
| `ProcessoInicialController` | 1 | `ProcessoInicial.processo` |
| `DesidrogenizacaoController` | 1 | `OrdemServico.posicoes` |
| `ProcessoController` | 3 | `Processo.posicoes` |
| `CargaController` | 5 | `Carga.ordensAcopladas` |
| `OrdemServicoController` | 10 | misto — ver abaixo |

### Os 20 pontos

| Endpoint | Controller:linha | Por que quebra |
|---|---|---|
| `listar` | `ProcessoInicialController.java:31` | `ProcessoInicialDTO.from` lê `pi.getProcesso().getDescricao()`; repositório é `findAll()` puro |
| `listar` | `ProcessoController.java:46` | `Set.copyOf(p.getPosicoes())`; `ProcessoRepository` não tem **nenhuma** consulta com fetch |
| `buscar` | `ProcessoController.java:51` | idem |
| `reativar` | `ProcessoController.java:73` | idem — `buscar` + `setAtivo`, `posicoes` nunca tocada |
| `atualizar` | `CargaController.java:40` | `CargaDTO.from` faz `List.copyOf(c.getOrdensAcopladas())` |
| `listar` | `CargaController.java:46` | idem, uma vez por linha |
| `buscar` | `CargaController.java:52` | idem |
| `porTag` | `CargaController.java:58` | idem |
| `reativar` | `CargaController.java:65` | idem |
| `emAndamento` | `DesidrogenizacaoController.java:61` | `DesidroEmAndamentoDTO.from` chama `od.getOrdemServico().getPosicoesOrdenadas()`; a consulta faz fetch da OS mas não da coleção `posicoes` |
| `criar` (caminho da OS irmã) | `OrdemServicoController.java:99` | `vincularNaIrma` → `findById`; só quebra quando o Nº já é de uma OS do mesmo setor |
| `listar` | `OrdemServicoController.java:130` | `posicoes` e `lotes` nunca vêm no fetch, nos dois caminhos |
| `buscar` | `OrdemServicoController.java:138` | `buscarParaDetalhe` cobre os quatro `@ManyToOne`, mas `posicoes`/`cargas`/`lotes`/`desidrogenizacoes` ficam de fora **de propósito** (`MultipleBagFetchException`) |
| `corrigir` | `OrdemServicoController.java:147` | `carregarAberta` → `findById` |
| `adicionarPosicao` | `OrdemServicoController.java:163` | idem |
| `historico` | `OrdemServicoController.java:175` | tudo fetchado **exceto** `log.getOrdensAcopladas()` |
| `historicoDeOrdens` | `OrdemServicoController.java:191` | mesma razão — rota nova, mesmo buraco |
| `finalizarLog` | `OrdemServicoController.java:275` | `logRepo.findById(logId)` sem fetch: `carga`, `processo`, `responsavel` e `ordensAcopladas`, quatro proxies crus |
| `lotes` | `OrdemServicoController.java:326` | usa `findByOrdemServicoIdOrderByNumeroAsc`, sem fetch — enquanto `LoteRepository.buscarParaRelatorio`, que **tem** o fetch, existe e não é usada aqui |
| `reabrir` | `OrdemServicoController.java:385` | `ReaberturaDTO` → `CargaDTO.from` nas cargas sugeridas → `ordensAcopladas`. Latente: só dispara se sobrar alguma carga livre |

### Dois padrões explicam quase tudo

1. **`@ElementCollection` LAZY lida por DTO.** `Carga.ordensAcopladas` (`Carga.java:76`),
   `Log.ordensAcopladas` (`Log.java:93`), `OrdemServico.posicoes` (`OrdemServico.java:79`),
   `Processo.posicoes` (`Processo.java:42`). Nenhuma consulta do projeto faz fetch de
   nenhuma delas — e nem dá para juntá-las aos outros fetches sem produto cartesiano.

2. **Endpoint de escrita devolvendo a entidade do caminho de mutação.** `finalizarLog`,
   `corrigir`, `adicionarPosicao`, `Carga.atualizar`/`reativar`, `Processo.reativar`: o
   service carrega com `findById` para aplicar a regra, toca só o campo que muda, e o
   controller depois pede ao DTO tudo o resto.

### Os de maior impacto

`GET /api/ordens` e `GET /api/ordens/{id}` (toda tela os carrega), `GET /api/cargas` (o
pool do chão de fábrica) e `PATCH /api/ordens/logs/{logId}/finalizar` (quatro proxies não
inicializados de uma vez).

## O padrão da migração

Mecânico. O service passa a devolver DTO em vez de entidade, e o mapeamento move-se para
dentro do `@Transactional`:

```java
// antes — controller monta o DTO, já fora da transação
@GetMapping
public List<ProcessoInicialDTO> listar() {
    return service.listar().stream().map(ProcessoInicialDTO::from).toList();
}

// depois — o service devolve pronto
@GetMapping
public List<ProcessoInicialDTO> listar() {
    return service.listar();
}
```

**O padrão já tem um exemplo no repositório**, e é o código mais novo:
`services/AuditoriaService.java:102` (`auditoriaDeOrdens`) devolve `List<OrdemAuditoriaDTO>`
montado dentro do `@Transactional(readOnly = true)`. É o único service que hoje devolve
DTO — replicar aquilo, não inventar um padrão do zero. E é por isso que ele é o único
endpoint de `OrdemServicoController` classificado como seguro.

**Alternativa descartada:** encher as consultas de `join fetch`. Resolve os 20 casos de
hoje e não impede o vigésimo primeiro — o próximo DTO que tocar um campo novo volta ao
silêncio. O que se quer é a trava, não o remendo.

## Ordem de execução

Um controller por commit, do menor para o maior. `Cliente` e `Operador` não aparecem: não
têm nada a fazer.

1. `ProcessoInicialController` — 1 ponto
2. `ProcessoController` — 3 pontos
3. `CargaController` — 5 pontos
4. `DesidrogenizacaoController` — 1 ponto (mas mexe em `OrdemServico.posicoes`; deixar
   colado ao passo 5)
5. `OrdemServicoController` — 10 pontos, o grosso
6. **Só então** `spring.jpa.open-in-view=false` em `application.properties`

Invertendo esta ordem — ligando a flag primeiro — a aplicação fica com 20 rotas quebradas
ao mesmo tempo e não dá para trabalhar.

## Portão de aceitação

**Os testes atuais não pegam nada disto.** Os de `src/test/java/.../services/` são
unitários com Mockito, sem banco, e nunca exercitam a serialização da resposta — que é
onde o lazy load acontece.

O que pega: `spring-boot-starter-webmvc-test` já está no `pom.xml:93` (não é dependência
nova). Um smoke MockMvc que chame **todo `GET`** com `open-in-view=false` é o gate real.
São **28 rotas** em 9 controllers:

```bash
grep -rn "@GetMapping" src/main/java/com/ramajo/logs/system/controllers/
```

Três pedem tratamento à parte, por não devolverem JSON: `/api/ordens/{id}/planilha` e
`/api/relatorios/periodo/planilha` (devolvem `byte[]`) e `/api/estado/stream` (SSE).

Os `POST`/`PUT`/`PATCH` que devolvem DTO também precisam de cobertura — `finalizarLog` é o
pior ponto da lista inteira e é um `PATCH`.

Complemento útil, o mesmo usado para medir a otimização da Visão Geral:

```properties
logging.level.org.hibernate.SQL=DEBUG
```

Contar statements por requisição antes e depois. Um endpoint que caia de N para 1 statement
é a confirmação de que o `join fetch` certo entrou.

## Custo, e quando não fazer

20 endpoints, 5 controllers, e **zero ganho de performance por si só**. Se o objetivo for
velocidade, a otimização da Visão Geral já entregou (126 requisições → 3). Esta migração
compra outra coisa: que o próximo N+1 apareça como erro em vez de como reclamação de
usuário.

Vale quando houver espaço para uma refatoração transversal sem pressa. Não vale como
resposta a "tal tela está lenta" — para isso, meça primeiro.
