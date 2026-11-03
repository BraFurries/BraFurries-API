# Gestão Comercial privada BraFurries — Financeiro V1

## Escopo de autoridade e permissões

Esta funcionalidade é **exclusiva da BraFurries**, não pertence às Communities compartilhadas ou à loja virtual Discord. A API usa `CommerceAuthorizationService`: proprietário interno fixado em `COMMERCE_OWNER_USER_ID=2`, ou aprovação pessoal **e** cargo ativo do Time da Community oficial `COMMERCE_OFFICIAL_COMMUNITY_ID=1` com interseção de permissão. O Front apenas apresenta controles; a API verifica cada operação.

- `SALES_MANAGE`: criar venda física por lote, consultar vendas, cancelar integralmente as ainda não apuradas; consulta lotes disponíveis sem exibir custos dos fornecedores.
- `FINANCE_VIEW`: consultar lançamentos de royalties, demonstrativos, itens e transferências registradas.
- `SETTLEMENTS_MANAGE` **+** `FINANCE_VIEW`: preparar, aprovar ou descartar um demonstrativo.
- `PAYMENTS_RECORD` **+** `FINANCE_VIEW`: registrar transferências já realizadas fora do sistema.

Não herdar poderes de Community Admin e não adicionar `community_id` às tabelas comerciais.

## Venda física

V1 registra apenas vendas **confirmadas**, de 1 a 50 lotes distintos, com quantidade e preço unitário em BRL. Cada linha é vinculada a lote de produção já existente e produzido até a data da venda. O preço, custo de fabricação rateado do lote, contratos e composição dos royalties ficam congelados como snapshots históricos.

`requestKey` (UUID v4 canônico, no máximo uma venda) protege contra retries. Reutilizar com conteúdo diferente gera conflito. `channel+externalReference` têm unicidade, quando a referência é informada. O saldo disponível do lote é `quantity - sold_quantity`. O contador `sold_quantity` é atualizado atomicamente na mesma transação da venda ou cancelamento, com `SELECT ... FOR UPDATE` e `UPDATE` condicional; isso evita leituras antigas sob o isolamento REPEATABLE READ do MariaDB. A API também faz locking reads para produto, direitos da obra, período do artista e lote, evitando oversell ou alteração concorrente de contrato/rateio.

**Cancelamento integral** exige motivo e é idempotente. Não apaga linhas ou royalties anteriores: insere eventos negativos de reversão por item/arte. As reversões ficam no mesmo mês contábil da venda original (mesmo se o cancelamento ocorrer no mês seguinte), **antes** de o artista ser fechado. Cancelamento de vendas já incluídas em fechamento é bloqueado; ajustes depois do fechamento, estornos parciais, trocas e devoluções exigirão fluxo específico V2.

## Royalties

Calculados em **BigDecimal**, com arredondamento HALF_UP somente no evento (centavos):

- `PERCENT_GROSS`: `valor bruto da linha × percentual do contrato / 100 × participação da arte / 100`.
- `FIXED_UNIT`: `valor contratual por unidade × quantidade × participação da arte / 100`.
- `NONE`: não gera lançamento recorrente por venda; não equivale a liquidar um pagamento inicial.

O rateio por arte **precisa ser informado** quando o contrato gera royalties; o sistema não distribui 100% automaticamente. A soma dos rateios de obras royalty-bearing por produto não pode ultrapassar 100%.

**Ainda não suportados**: `PERCENT_NET` (definir despesas dedutíveis, tributos, frete, taxas, custos e datas de reconhecimento), `FIXED_MONTH` (independente de volume de vendas; depende de obrigação por competência) e liquidação de valores únicos de criação/cessão. A V1 rejeita a venda que tentaria gerar royalties com essas modalidades; nunca calcula repasses presumidos.

## Demonstrativo mensal / repasse

- Ano/mês contábil referem-se à **data da venda no fuso America/Sao_Paulo**.
- É possível gerar rascunho apenas de mês já encerrado e com lançamentos. Há no máximo um demonstrativo por artista/mês.
- O mutex por artista/mês impede novos lançamentos e estornos enquanto existe um rascunho, até sua aprovação ou descarte.
- O rascunho lista cada evento, inclusive reversões, e captura valores sem recalcular o contrato atual. A aprovação revalida a cobertura e o total.
- Depois de aprovado, o demonstrativo fica imutável. Pode ficar `APPROVED`, `PARTIALLY_PAID` ou `PAID`; total zero aprovado vai para `PAID`.
- Pagamentos são **registros de transferências realizadas externamente**, com referência, valor, data e UUID idempotente. A soma não pode exceder o valor aprovado. O sistema **não** envia PIX/transferências e, nesta versão, ainda não anexa comprovantes.
- Comprovantes e outros arquivos financeiros futuros usarão o R2 privado `R2_PRIVATE_*`, em prefixos com autorização de API própria. Sem links públicos.

## Dependências / rollout (ainda não autorizado)

1. Database: PR **#41 → #42 → #43**; conferir migrations Flyway anteriores, backup e schema MariaDB. Nenhuma DDL na API.
2. API: PR **#63 → #64 → #65**, revisando HEAD, PR threads, checks e o **diff final real**. Branches atuais são empilhadas e devem integrar todos os commits em `main`.
3. Front: PR **#47 → #48 → #49** depois de schema/API disponíveis.
4. Antes de qualquer deploy, recuperar conteúdo completo de **GitHub Environment Produção → PROD_ENV_FILE**, preservar todas as outras variáveis e acrescentar `COMMERCE_OWNER_USER_ID=2`, `COMMERCE_OFFICIAL_COMMUNITY_ID=1`, `R2_PRIVATE_BUCKET`, `R2_PRIVATE_ACCESS_KEY`, `R2_PRIVATE_SECRET_KEY`. Nunca registrar segredos em código, PR, chat ou log.
5. Confirmar que o bucket privado não tem `r2.dev` ou domínio público ativo; token R2 `Object Read & Write` apenas no bucket privado.
6. Testar em ambiente seguro: duas contas (proprietário/sem grant), produto e lote fictícios, contrato sintético, venda e retry UUID, disputa de estoque, cálculo bruto/fixo, cancelamento e inversão, criação/aprovação de fechamento passado, parcial e total externo. Confirmar que Community Admin de outra comunidade não vê o módulo.
7. Não habilitar deploy automático ou alterar dados reais antes da aprovação operacional e validação completa.

**Não marcar a feature completa de comércio como entregue em produção apenas porque a infraestrutura V1 e as PRs foram criadas.**
