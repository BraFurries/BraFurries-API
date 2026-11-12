# Administração — Gestão de Armazenamento R2 (V1)

## Autoridade e segurança

- **Owner da BraFurries apenas**: a API exige `PlatformAuthorizationService.requireOwner()` em cada operação. Não delegar com cargo de Community e não inferir acesso de menu/Front.
- API é o control plane. Cloudflare R2 armazena bytes e metadados, não decide se um contrato/arquivo pode ser excluído. O schema MariaDB no Database continua sendo a autoridade dos vínculos.
- Nenhuma nova migration; nenhuma DDL/DML ao excluir um objeto órfão. Coddy não participa.
- Dois buckets lógicos (`PRIVATE` e `PUBLIC`) correspondem a configurações existentes com credenciais independentes. Nomes reais de bucket e segredos não saem da API.
- A lista/inspeção não lê conteúdo privado, só metadados R2. Não há upload, exclusão pública ou operação em lote nesta versão.

## Contrato HTTP

Rotas autenticadas sob `/user/me/administration/storage`:

- `GET /objects?bucket=PRIVATE|PUBLIC&prefix=...&limit=50&cursor=...`: lista paginada, entre 1 e 100 objetos por página. `cursor` é opaco. `pageBytes` é **somente da página**, não o consumo global do bucket.
- `POST /objects/inspect` com `{ "bucket":"PRIVATE|PUBLIC", "key":"..." }`: usa HEAD e retorna ETag, tamanho, content type e vínculo, sem colocar a chave privada na URL.
- `POST /objects/delete-orphan`: `{ "bucket":"PRIVATE","key":"commerce/contracts/{artworkId}/{uuid}.pdf","etag":"..." }`. Autorização de owner, HEAD/ETag atual, chave em prefixo aprovado, idade mínima e ausência comprovada de vínculos; verifica via novo HEAD que R2 não serve mais o objeto. Resposta 204.

### Estados do objeto

| Status | Interpretação | Excluível? |
| --- | --- | --- |
| LINKED | Referência presente no Database | Não |
| ORPHAN | Tipo de chave privada conhecido, nenhum vínculo nas tabelas conhecidas, idade maior que 10 minutos | Sim, após revalidação |
| RECENT | Upload possivelmente em curso: aguarda 10 minutos | Não |
| UNVERIFIED | Tipo de objeto/prefixo sem cobertura completa de vínculos (incluindo mídias públicas e Discord/legado) | Não |

Domínios cobertos pela verificação em MariaDB:

- `commerce/contracts/{artworkId}/{uuid}.pdf`: `commerce_artwork_contracts.signed_storage_key` e `commerce_contract_preparations.generated_storage_key`.
- `commerce/artworks/{artworkId}/{uuid}.{ext}`: `commerce_artwork_files.storage_key`.
- A verificação considera **todas as três tabelas** para ambos os tipos antes de excluir, sem supor que chaves de outra origem estejam livres.

As chaves públicas e prefixos privados desconhecidos são somente leitura. Futuras categorias exigem identificar sua autoridade real (Database, outros serviços, Discord, URLs legadas), testar isolamento de Community e só então liberar remoção.

## Rollout

1. Revisar PRs API #100 e Front #86 na HEAD atual, diffs e checks. Substituem o endpoint específico de limpeza do contrato de teste anterior, nunca publicado.
2. Confirmar que as credenciais existentes permitem `ListObjectsV2`, `HeadObject` e `DeleteObject` no bucket privado (e listagem HEAD no público). Não ampliar credenciais para todos os buckets.
3. Merge/deploy API e conferir saúde. Depois merge/preview/deploy Front. Não requer migration ou Coddy.
4. Homologar com owner: prefixo `commerce/contracts/`, seleção do arquivo órfão, status ORPHAN, ETag. Confirmar chave exata antes do POST. Testar 403 com usuário não-owner e visualizar outros tipos como UNVERIFIED.
5. A exclusão de R2 é irreversível; não é possível reverter por transação SQL. Fazer backup do arquivo se necessário. Não executar em lote.

## Limitações V1

- Listagem é por prefixo e paginação; não fornece varredura global nem custos Cloudflare.
- Prefixos não mapeados nunca são classificados como órfãos apenas pela ausência de referências SQL.
- Verificação de ETag + idade mínima reduz risco de corrida com uploads, mas não substitui um registro transacional de objeto; se houver fluxo que regrave a mesma chave, deverá possuir sua própria trava/reconciliação.
