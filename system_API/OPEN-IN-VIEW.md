# `open-in-view` desligado

**Aplicado.** `spring.jpa.open-in-view=false` está em `application.properties`.

Levantamento feito em 2026-09-22, migração executada em 2026-09-23, na branch
`refactor/open-in-view` — sete commits, um por controller.

## A regra que passou a valer

**Nenhum controller chama `DTO.from(...)`.** Todo mapeamento acontece dentro de um método
`@Transactional` de service, que devolve o DTO pronto.

```bash
# tem de voltar vazio
grep -rn "DTO\.from\|DTO::from" src/main/java/com/ramajo/logs/system/controllers/
```

Isto não é estilo. Com o open-in-view desligado a sessão do Hibernate fecha quando o
service retorna, e um `from(...)` chamado depois disso estoura
`LazyInitializationException` na linha exata. Antes, o mesmo toque LAZY virava uma query
extra silenciosa — sem exceção, sem log, só lentidão.

Foi esse mecanismo que deixou o N+1 da Visão Geral passar despercebido por todo o
desenvolvimento da aba: `LogRepository.buscarHistorico` não tinha `join fetch`, o
`LogDTO.from` desreferenciava quatro `@ManyToOne` por passo, e a única evidência era a aba
demorar.

Desligar não acelerou nada por si só. Converteu uma classe inteira de bug invisível em
falha alta, na linha exata, que o teste pega. É trava, não motor.

## O portão

`src/test/java/com/ramajo/logs/system/controllers/RotasSmokeTest.java` — `@SpringBootTest`
+ `@AutoConfigureMockMvc` contra um Postgres de verdade, percorrendo todas as rotas.

Precisa do banco próprio, criado uma vez:

```bash
docker exec ramajo-db-1 psql -U postgres -c "create database ramajo_smoke"
```

Duas armadilhas, ambas documentadas na classe e nenhuma das duas óbvia:

- **`@WebMvcTest` não serve.** Substitui os services por mocks: não há sessão, não há lazy
  load, e o teste passaria com todos os bugs no lugar. Era o que o plano original pedia.
- **`@Transactional` na classe não serve.** A transação de teste manteria a sessão aberta
  por toda a requisição e mascararia exatamente a exceção que se quer provocar — o
  open-in-view voltaria pela porta dos fundos. Por isso o teste não desfaz o que escreve,
  e por isso o banco é separado.

A semeadura vai pela própria API, não pelos repositórios: assim as rotas de escrita que
devolvem DTO entram no gate junto com os `GET`. O cenário monta as coleções LAZY que
nenhuma consulta faz fetch — sobre tabelas vazias toda rota devolve lista vazia e o smoke
não prova nada.

**Verificado por negativa:** reintroduzindo o mapeamento de `emAndamento` no controller, o
teste falha com `LazyInitializationException` em `OrdemServico.posicoes (no session)`.

## O padrão usado

O service devolve DTO em vez de entidade. Onde nenhum teste dependia do retorno, o tipo de
retorno mudou direto:

```java
// antes — controller monta o DTO, já fora da transação
return service.listar().stream().map(ProcessoInicialDTO::from).toList();

// depois
return service.listar();
```

Onde um teste Mockito **inspeciona a entidade devolvida**, o núcleo ficou intacto e ganhou
uma fachada — `criarEMapear`, `adicionarPosicaoEMapear`, `vincularCargaEMapear`,
`iniciarLogEMapear`, `acoplarNaCargaEMapear`, `reabrirEMapear`,
`avaliarComoAdminEMapear`. São sete, e existem para que os ~3.700 linhas de teste
continuassem a passar sem uma alteração:

```java
@Transactional
public OrdemCriadaDTO criarEMapear(...) {
    return OrdemCriadaDTO.from(criar(...));
}
```

A self-invocation ignora o `@Transactional` do núcleo, mas o da fachada cobre tudo — que é
o efeito desejado.

`AuditoriaService.auditoriaDeOrdens` já era assim antes da migração e serviu de modelo.

**Alternativa descartada:** encher as consultas de `join fetch`. Resolveria os 20 casos de
então e não impediria o vigésimo primeiro — o próximo DTO a tocar um campo novo voltaria
ao silêncio. O que se queria era a trava, não o remendo.

## O que mudou, por controller

| Controller | Pontos que quebravam | Causa raiz |
|---|---|---|
| `ClienteController` | 0 | `Cliente` não tem associação LAZY — movido só pelo invariante |
| `OperadorController` | 0 | idem |
| `ProcessoInicialController` | 1 | `ProcessoInicial.processo` |
| `DesidrogenizacaoController` | 1 | `OrdemServico.posicoes`, via `DesidroEmAndamentoDTO` |
| `ProcessoController` | 3 | `Processo.posicoes` |
| `CargaController` | 5 | `Carga.ordensAcopladas` |
| `OrdemServicoController` | 10 | misto |

Dois padrões explicavam quase tudo:

1. **`@ElementCollection` LAZY lida por DTO.** `Carga.ordensAcopladas`,
   `Log.ordensAcopladas`, `OrdemServico.posicoes`, `Processo.posicoes`. Nenhuma consulta
   fazia fetch de nenhuma delas — e nem dá para juntá-las aos outros fetches sem produto
   cartesiano.
2. **Endpoint de escrita devolvendo a entidade do caminho de mutação.** O service carrega
   com `findById` para aplicar a regra, toca só o campo que muda, e o controller depois
   pedia ao DTO tudo o resto.

> **`@BatchSize` não ajudava.** Reduz o N+1 *enquanto a sessão está aberta*; com o
> open-in-view desligado a sessão já fechou, e uma coleção com `@BatchSize` estoura
> `LazyInitializationException` exatamente como uma sem. Dentro da transação, porém, ele
> continua a fazer o seu trabalho — é o que segura `Log.ordensAcopladas` em `historico` e
> `OrdemServico.posicoes` em `emAndamento`, onde não há `join fetch` possível.

## `join fetch` que entraram junto

Onde a coleção passou a ser lida numa consulta de leitura pura, o fetch veio com ela:

- `ProcessoInicialRepository.buscarTodosComProcesso`
- `ProcessoRepository.buscarTodosComPosicoes` / `buscarComPosicoes`
- `CargaRepository.buscarTodasComAcopladas` / `buscarDisponiveisComAcopladas` /
  `buscarComAcopladas` / `buscarPorTagComAcopladas` — `GET /api/cargas` é o pool do chão
  de fábrica e pagava um SELECT por carga
- `OrdemServicoService.lotes` trocou `findByOrdemServicoIdOrderByNumeroAsc` por
  `LoteRepository.buscarParaRelatorio`, que **já tinha** o `left join fetch lo.finalizadoPor`
  e não era usada ali

Os caminhos de escrita continuam no `findById`: lá a entidade gerenciada é necessária para
o dirty checking, e a coleção é tocada dentro da transação de qualquer forma. Nos services
com os dois caminhos, o `buscar(id)` público virou leitura com fetch e um `carregar(id)`
privado passou a servir as mutações.

## Medir

```properties
logging.level.org.hibernate.SQL=DEBUG
```

Contar statements por requisição em `GET /api/ordens`, `GET /api/ordens/{id}`,
`GET /api/cargas` e `PATCH /api/ordens/logs/{logId}/finalizar` — os quatro de maior
impacto.
