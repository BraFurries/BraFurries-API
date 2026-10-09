# AGENTS.md

## Repositorio publico da API

Este repositorio publico e a autoridade do codigo da BraFurries-API apos o cutover de historico limpo. O repositorio historico permanece privado como arquivo, sem apagar branches, historico, PRs ou evidencias.

- Nunca tornar o repositorio historico publico, mesmo temporariamente; seus commits antigos permanecem privados no arquivo.
- O CI de PR usa runners hospedados pelo GitHub, com `contents: read`, para verificacao de workflow, Gitleaks, testes e empacotamento. Nao acessa o runner produtivo nem segredos.
- O workflow de deploy publico foi preparado **desligado por padrao**: `BRF_API_AUTO_DEPLOY_ENABLED=true` exige cutover explicito do repositorio privado. Deploy manual ou automatico requer runner dedicado `api-production`, Environment `Produção`, segredo `PROD_ENV_FILE` e permissao do novo repo no pacote GHCR existente. Nunca permitir PRs no runner de producao.
- Ate validar o novo caminho, o workflow do arquivo privado ainda pode publicar a API; nao habilitar deploy automatico nos dois repositorios ao mesmo tempo. Consulte `docs/production-deploy-cutover.md`. Infraestrutura de producao exige trust boundaries, pinning de actions, `main` protegida, environment restrito, menor privilegio e autorizacao de deploy separada.
- A API continua control plane, e o Database e autoridade do schema; nunca introduzir DDL runtime ou remover isolamento entre Communities durante a migracao.
- Nao assumir que redirecionamentos do GitHub ou secrets, rulesets, ambientes e permissao de pacotes sejam configurados automaticamente.
- Nao habilitar ou executar workflows de producao sem autorizacao expressa e sem os controles de cutover; alteracoes no workflow exigem revisao do diff e novos checks.
- Nao executar deploy, DML, migracoes ou rotacao de tokens por conta de manutencao deste repositorio.

## Como Trabalhar Neste Repositorio

Este projeto e uma API Spring Boot do ecossistema BraFurries. Trabalhe de forma conservadora, seguindo os padroes existentes e sem presumir propriedade exclusiva do banco.

## Stack Essencial

- Java 21, Spring Boot 3.5.0 e Maven Wrapper.
- Spring Web, Security, OAuth2 Client, Data JPA, Validation, Mail e Cache.
- MariaDB via Spring Data JPA.
- Caffeine cache.
- AWS SDK S3 para Cloudflare R2 ou storage S3-compatible.
- Springdoc OpenAPI em `/docs` e `/docs/openapi`.

## Arquitetura

- Entrypoint: `com.Brafurries.API.BraFurriesApiApplication`.
- Pacotes principais: `auth`, `user`, `event`, `admin`, `config`, `entity`, `repository`, `common/dto`, `meta`, `context`.
- Fluxo predominante: `Controller -> DTO record -> Service -> Repository -> Entity/Database`.
- Controllers devem ser finos quando possivel. Coloque regra de negocio em services.
- Excecao existente: `EventController` concentra mapeamento e composicao de DTOs. Ao alterar eventos, siga o padrao local desse modulo.

## HTTP

- Nao ha prefixo global `/api`.
- Rotas principais: `/auth`, `/oauth`, `/user`, `/events`, `/public`, `/invites`, `/admin`, `/internal`, `/meta`, `/third-party`.
- Nao introduza versionamento, novo prefixo global ou novo formato global de resposta sem decisao explicita.
- Use `@Valid` em DTOs quando houver validacao formal.
- Para erros, prefira `ResponseStatusException`, `IllegalArgumentException`, `BadCredentialsException` ou `AccessDeniedException`, pois ha `GlobalExceptionHandler`.
- Respostas de sucesso podem ser DTOs diretos, `ApiMessageResponse` ou `PagedResponseDto`; nao force envelope unico.

## Seguranca

- Nunca leia, imprima, copie, exponha ou commite valores existentes em `.env`.
- Uma `.env` local nunca deve ser presumida como ambiente de desenvolvimento; ela pode conter credenciais equivalentes as de producao.
- Pode documentar nomes de variaveis, mas nunca seus valores.
- A autenticacao principal usa Bearer JWT proprio via `TokenService` e `BearerTokenAuthenticationFilter`.
- Roles viram `ROLE_<NOME>`; permissoes sao strings diretas como `admin:full`, `events:manage`, `events:edit`.
- Nao interprete `permitAll` no `SecurityConfig` como ausencia de autenticacao. Alguns endpoints internos validam token proprio no controller ou em outra camada.
- Antes de alterar seguranca, investigue toda a cadeia de autenticacao do endpoint.
- Nao substitua mecanismos internos existentes pelo JWT principal sem solicitacao explicita.

## Banco E Schema

- Banco observado: MariaDB.
- `spring.jpa.hibernate.ddl-auto=none`.
- Nao ha Flyway/Liquibase nem migrations formais no repositorio.
- A existencia de uma Entity JPA nesta API nao significa que a API seja proprietaria exclusiva da tabela.
- O schema MariaDB e compartilhado com outras aplicacoes do ecossistema BraFurries.
- Nao crie, renomeie ou remova tabelas, colunas, indices ou constraints sem coordenacao explicita.
- Em workspace multi-repositorio, qualquer alteracao de schema em tabelas compartilhadas deve ser analisada contra as outras aplicacoes que leem ou escrevem nessas estruturas.

Para alteracoes de schema, sempre informe:

- SQL necessario.
- Tabelas, colunas e indices afetados.
- Consumidores conhecidos ou potenciais.
- Impacto sobre dados existentes.
- Compatibilidade retroativa.
- Ordem de implantacao entre aplicacoes.
- Risco operacional.

## Persistencia

- Use repositories existentes em `repository/<dominio>`.
- Evite queries nativas novas salvo necessidade clara.
- Cuidado especial com `UserAccountMergeService`, que executa SQL nativo em varias tabelas relacionadas a usuario.
- Trate tabelas de Discord, Telegram, bot, comandos, comunidade, voz, backup, portaria, formularios e stats como potencialmente compartilhadas.

## Integracoes

Nao execute integracoes externas sem pedido explicito:

- SMTP.
- Google Calendar.
- Google/Discord OAuth.
- Discord API.
- Bot status/logs.
- Cloudflare R2/S3.

## Testes E Runtime

- Nao execute a aplicacao como etapa padrao de validacao.
- Nao rode automaticamente a suite completa se ela puder inicializar Spring Context, datasource ou integracoes externas.
- Antes de executar `./mvnw test`, confirme que o datasource e isolado e nao produtivo, que nenhum segredo de producao sera usado, que dados reais/compartilhados nao poderao ser alterados e que integracoes externas nao serao chamadas de forma insegura.
- Quando isso nao puder ser confirmado, prefira testes unitarios isolados ou apenas informe quais validacoes devem ser executadas em ambiente seguro.
- Comandos mais seguros quando aplicaveis:
  - `./mvnw -DskipTests package`
  - `./mvnw -Dtest=NomeDoTeste test`

## Git

- Preserve alteracoes locais existentes.
- Nao reverta trabalho do usuario.
- Nao use `git reset --hard` ou checkout destrutivo sem pedido explicito.
- Nao faca commit ou push sem pedido explicito.
- Branches novas de agentes devem usar prefixo `codex/` quando necessario.

## Evite

- Assumir que esta API e dona do schema.
- Criar migrations inexistentes como se fossem padrao do projeto.
- Expor secrets.
- Rodar a aplicacao ou testes integrados contra ambiente desconhecido.
- Criar padroes paralelos de endpoints, DTOs, respostas ou seguranca.
- Corrigir inconsistencias arquiteturais fora do escopo solicitado.
