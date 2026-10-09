# ARCHITECTURE.md

## Proposito

BraFurries-API e uma API Spring Boot do ecossistema BraFurries. Pelo codigo existente, ela atende autenticacao local e social, usuarios, eventos/meets, agenda, convites de transferencia, area administrativa, parceiros, metricas, logs/status de bot, integracoes com Google Calendar, Discord/Google OAuth, SMTP e storage R2/S3.

Este documento descreve o funcionamento observado no repositorio. Ele nao define arquitetura nova.

## Stack

- Java 21.
- Spring Boot 3.5.0.
- Maven com Maven Wrapper.
- Spring Web MVC.
- Spring Security.
- Spring OAuth2 Client.
- Spring Data JPA.
- Jakarta Validation.
- Spring Mail.
- Spring Cache.
- Caffeine.
- Springdoc OpenAPI.
- MariaDB JDBC driver.
- `dotenv-java`.
- AWS SDK S3 e Auth para storage S3-compatible.
- `webp-imageio` para conversao de imagens para WebP.

Dependencias principais estao em `pom.xml`.

## Entrypoint

O entrypoint e `com.Brafurries.API.BraFurriesApiApplication`.

A aplicacao:

- procura `.env` no diretorio atual e nos diretorios pais;
- carrega variaveis via `dotenv-java`;
- injeta valores em `System.properties`, preservando variaveis de ambiente ja definidas;
- remove aspas externas de alguns valores;
- inicia o Spring Boot;
- habilita scheduling com `@EnableScheduling`.

## Configuracao

A configuracao principal esta em `src/main/resources/application.properties`.

Grupos de variaveis observados:

- Aplicacao e frontend: `BASE_URL`, `FRONTEND_URL`.
- CORS: `CORS_ALLOWED_ORIGINS`.
- Banco: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`.
- JWT: `JWT_BASE_KEY`, `JWT_EXPIRATION`, `JWT_REFRESH_EXPIRATION`, `JWT_ROTATION_INTERVAL`.
- SMTP: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`.
- Verificacao de e-mail: `EMAIL_VERIFICATION_EXPIRATION_HOURS`, `EMAIL_VERIFICATION_CLEANUP_MS`.
- R2/S3: `R2_ENDPOINT`, `R2_BUCKET`, `R2_PUBLIC_BASE_URL`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`.
- Google Calendar/service account: `GOOGLE_PROJECT_ID`, `GOOGLE_TOKEN_URI`, `GOOGLE_SERVICE_ACCOUNT_EMAIL`, `GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY`, ids de calendarios.
- Discord: `DISCORD_TOKEN`.
- Bot/admin: `BOT_BASE_URL`, `BOT_STATUS_TOKEN`, `BOT_LOGS_BUFFER_SIZE`, `BOT_LOGS_BATCH_MAX_SIZE`.
- Anuncios: flags e nomes de destino para Discord/Telegram.
- OAuth social: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`.
- Cache de dashboard.

Nao foram observados profiles Spring especificos como `application-dev.properties`, `@Profile` ou `SPRING_PROFILES_ACTIVE` no codigo lido.

## CORS

`SecurityConfig` registra `CorsConfigurationSource` para `/**`.

Configuracao observada:

- origins via `app.cors.allowed-origins`;
- metodos `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`;
- headers permitidos `*`;
- headers expostos `Authorization` e `Location`;
- `allowCredentials=false`;
- `maxAge=3600`.

## Segurança

`SecurityConfig` usa Spring Security com:

- CORS habilitado;
- CSRF desabilitado;
- filtro customizado `BearerTokenAuthenticationFilter`;
- `@EnableMethodSecurity`;
- HTTP Basic habilitado;
- OAuth2 login quando ha client registrations configurados;
- handlers JSON para 401/403 usando `ApiErrorResponse`.

Regras globais observadas:

- `OPTIONS /**`: permitido.
- `/auth/**`, `/oauth/token`: permitido.
- `/oauth2/**`, `/login/oauth2/**`: permitido.
- `/public/**`: permitido.
- `POST /internal/bot/logs` e `/internal/bot/logs/batch`: permitido no SecurityConfig.
- `GET /meta/**`: permitido.
- `GET /invites/event-transfer/*`: permitido.
- `GET /events/public`: permitido, embora nao tenha sido observado controller correspondente.
- `GET /events/{id}` numerico: permitido.
- Swagger/OpenAPI: exige role `ADMIN` ou `DEVELOPER`.
- `/user/**`: autenticado.
- `/events/**`: autenticado.
- `/third-party/**`: authorities `CLIENT_PARTNER`, `CLIENT_BOT`, `CLIENT_SERVICE` ou `CLIENT_INTERNAL`.
- `/admin/**`: role `ADMIN`.
- Demais rotas: autenticadas.

Importante: `permitAll` nao significa necessariamente endpoint sem autenticacao. `InternalBotLogsController` valida `Authorization: Bearer <token>` proprio com `app.bot.status-token`, usando comparacao constante via `MessageDigest.isEqual`.

## Autenticacao E Autorizacao

### JWT Local

`TokenService` gera JWT HMAC SHA-256 manualmente com:

- `sub`;
- `roles`;
- `permissions`;
- `exp`.

O filtro Bearer valida assinatura, expiracao e confirma que a conta nao possui token pendente de `EMAIL_VERIFICATION`.

Roles vindas de `api_user_roles` sao convertidas para authorities `ROLE_<ROLE_EM_UPPERCASE>`. Permissoes vindas de `api_role_permissions` sao authorities diretas.

### Login Local

`AuthController` expoe:

- `POST /auth/login`;
- `POST /auth/register`;
- `POST /auth/confirm-email`;
- `POST /auth/forgot-password`;
- `POST /auth/reset-password`;
- `POST /auth/refresh`;
- `POST /auth/logout`;
- `POST /oauth/token`.

`AuthService`:

- normaliza e-mail para lowercase;
- usa BCrypt para senha local;
- cria tokens de verificacao e reset em `user_tokens`;
- cria refresh tokens em `user_refresh_tokens`;
- revoga refresh token antigo no refresh;
- envia e-mails via `JavaMailSender`;
- tem job agendado para remover cadastros pendentes expirados e tokens expirados/usados/revogados.

`/oauth/token` existe, mas retorna uma resposta fixa/stub para `client_credentials`; nao foi observado uso real de `api_clients`.

### Social/OAuth

`AuthSocialController` expoe login/cadastro por Google e Discord e endpoints de redirect OAuth2:

- `GET /auth/oauth2/discord`;
- `GET /auth/oauth2/google`;
- `POST /auth/login/google`;
- `POST /auth/login/discord`;
- `POST /auth/register/google`;
- `POST /auth/register/discord`;
- `POST /auth/social-register`.

`SocialOAuth2ClientConfig` registra clients Google/Discord somente se as credenciais existirem.

`AuthSocialService` valida tokens Google/Discord, extrai perfil, cria usuario social sem senha local, vincula Discord em `user_discord` e remove pendencia de verificacao de e-mail. Cadastros pendentes OAuth2 ficam em memoria por 15 minutos.

Quando e-mail e provedor social resolvem para Users diferentes, `AuthSocialService` interrompe o login com erro seguro e exige regularizacao administrativa. A autenticacao permanece vinculada ao User real do provedor; ela nao cria vinculos automaticamente nem consolida Users, Discords, roles ou tokens.

Vinculos administrativos entre Users sao arestas nao direcionadas modeladas por `UserIdentityLinkService` e `user_identity_links`, com pontas equivalentes `user_a_id` e `user_b_id`. `ConfirmedIdentityClusterService` percorre em lotes todas as arestas ativas `CONFIRMED`, suporta cadeias e ciclos e retorna o componente conexo completo sem eleger conta principal. `SUSPECTED` permanece apenas como contexto administrativo e nao participa do cluster, da agregacao de warnings ou de efeitos de ban. Cada User e cada registro de moderacao preserva sua proveniencia.

A agregacao de warnings e bans do cluster exige `communityId` explicita e consulta somente registros dessa Community. Notas nao possuem `community_id` no schema compartilhado e, por isso, nao sao propagadas entre identidades. O Coddy consulta a autoridade de identidade da API por `GET /internal/identity/users/{userId}` ou `GET /internal/identity/discord-users/{discordUserId}`, usando o token interno `app.bot.status-token`; a resposta fornece as identidades confirmadas, seus Discord IDs, a quantidade de outras contas e, quando solicitada, a moderacao agregada da Community.

Esta versao da API exige que a migration Database `V20260930_003__make_user_identity_links_undirected.sql` ja esteja aplicada. Ela nao possui aliases para os nomes fisicos antigos. O Coddy deve migrar para a superficie interna da API antes de remover sua leitura direta legada da tabela.

## Tratamento De Erros

`GlobalExceptionHandler` retorna `ApiErrorResponse`:

- `timestamp`;
- `status`;
- `error`;
- `message`;
- `path`;
- `details`.

Casos tratados:

- `MethodArgumentNotValidException`;
- `HttpMessageNotReadableException`;
- `MissingServletRequestParameterException`;
- `IllegalArgumentException`;
- `BadCredentialsException`;
- `AccessDeniedException`;
- `ResponseStatusException`;
- fallback `Exception`.

Respostas de sucesso nao seguem envelope unico. O codigo retorna DTOs diretos, `ApiMessageResponse` e `PagedResponseDto`.

## Banco De Dados

Banco observado: MariaDB.

Configuracao JPA:

- `spring.jpa.hibernate.ddl-auto=none`;
- `spring.jpa.show-sql=false`;
- `spring.jpa.open-in-view=false`.

Nao ha Flyway, Liquibase ou diretorio formal de migrations. A unica referencia a `ALTER TABLE` observada esta em teste para preparar colunas de imagem de perfil em `users`.

Conclusao operacional: o schema nao e gerenciado automaticamente por esta API. Alteracoes de schema devem ser coordenadas fora do Hibernate e tratadas como mudancas compartilhadas.

## Banco Compartilhado

A existencia de uma Entity JPA nesta API nao significa que a API seja proprietaria exclusiva da tabela.

O schema MariaDB e compartilhado com outras aplicacoes do ecossistema BraFurries. Existem fortes indicios de tabelas usadas por bot, Discord, Telegram, comandos, comunidades, formularios, portaria, backup, estatisticas, voz e eventos.

Quando este repositorio estiver em workspace multi-repositorio, qualquer alteracao de schema em tabelas compartilhadas deve ser analisada tambem contra as outras aplicacoes que leem ou escrevem nessas estruturas.

Para alteracoes de schema, a proposta deve informar:

- SQL necessario;
- tabelas, colunas e indices afetados;
- consumidores conhecidos ou potenciais;
- impacto sobre dados existentes;
- compatibilidade retroativa;
- ordem de implantacao entre aplicacoes;
- risco operacional.

Nao ha evidencia no repositorio de um processo formal de introducao de schema alem de alteracoes manuais/coordenadas.

## Entidades E Tabelas

Grupos principais mapeados:

- Auth/API: `api_clients`, `api_client_permissions`, `api_roles`, `api_permissions`, `api_role_permissions`, `api_user_roles`.
- Usuarios: `users`, `user_tokens`, `user_refresh_tokens`, `user_discord`, `user_telegram`, `user_locale`, `user_birthday`, `user_community_status`, `user_economy`, `user_level`, `user_inventory`, `user_permissions`, `user_records`, `user_bans`, `user_warnings`, `user_notes`, `user_patreon`, `user_temp_roles`, `user_custom_roles`.
- Eventos e convites: `events`, `event_scheduling`, `event_staffs`, `event_transfer_requests`, `event_transfer_logs`, `invites`.
- Comunidades/bot: `communities`, `community_discord`, `community_telegram`, `community_store`, `community_expedition`, `community_boss_event`, `community_boss_contribution`, `community_boss_attack_cooldown`, `allowed_feature_channels`, `active_call_logs`, `blacklisted_games`, `bot_sensitive_permission_whitelist`.
- Configuracao/comandos: `config_economy`, `config_levels`, `config_logs`, `config_server_settings`, `config_warnings_settings`, `config_command_uses`, `commands`, `discord_server_messages`.
- Parceiros: `partners`, `partner_links`, `partner_external_links`, `partner_metrics`, `partner_token`.
- Formularios: `form_flows`, `form_questions`, `form_submissions`, `form_decisions`, `form_published_messages`.
- Outros dominios: `telegram_event_messages`, `backup_*`, `portaria_*`, `stats_*`, `voice_*`, `locale`, `minigame_scores`.

Duplicidades/inconsistencias observadas:

- `minigame_scores` e mapeada em `entity/user` e `entity/misc`.
- `telegram_event_messages` e mapeada em `entity/telegram` e `entity/misc` com nomes de entidade distintos.

## Repositories

Repositories ficam em `repository/<dominio>` e estendem `JpaRepository`.

Padroes observados:

- derived queries (`findBy...`, `existsBy...`, `countBy...`);
- JPQL com `@Query`;
- algumas queries nativas, especialmente em eventos/agregados;
- projections por interface;
- `Pageable` em listagens e rankings;
- `@EntityGraph` pontual.

## DTOs E Mapeamento

DTOs sao majoritariamente `record`s internos em classes por modulo:

- `AuthDtos`;
- `AuthSocialDtos`;
- `UserProfileDtos`;
- `UserPermissionDtos`;
- `UserGeneralInfoDtos`;
- `UserDashboardDtos`;
- `EventDtos`;
- `AdminDtos`;
- `AdminMetricsDtos`;
- `AdminUserReliabilityDtos`;
- `MetaDtos`;
- `common/dto`.

O mapeamento e manual. Nao ha MapStruct ou framework equivalente.

Em muitos modulos, controller chama service e retorna DTO. Em eventos, o controller contem grande parte do mapeamento de entidade para DTO.

## Endpoints E Contratos HTTP

Nao ha prefixo global `/api` nem versionamento de URL observado.

### Contexto

- `GET /public/ping`
- `GET /user/ping`
- `GET /third-party/ping`
- `GET /admin/ping`

### Meta

- `GET /meta/locales`

Le `locale` via `LocaleRepository`.

### Auth

- `POST /auth/login`
- `POST /auth/register`
- `POST /auth/confirm-email`
- `POST /auth/forgot-password`
- `POST /auth/reset-password`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /oauth/token`
- endpoints sociais em `/auth/login/google`, `/auth/login/discord`, `/auth/register/google`, `/auth/register/discord`, `/auth/social-register`.

### Usuario

- `GET /user/me`
- `GET /user/me/permissions`
- `GET /user/me/general-info`
- `GET /user/me/dashboard`
- `PUT /user/me/profile-image`
- `DELETE /user/me/profile-image`

Fluxos leem principalmente `users`, `user_community_status`, `community_discord`, `user_economy`, `event_staffs`, `events`, `user_discord`, `user_telegram`, `user_birthday`, `user_locale`, `user_level`.

Upload de imagem grava metadados em `users` e objeto no R2/S3.

### Eventos

`EventController` expoe:

- `POST /events`;
- `GET /events/{id}`;
- `GET /events`;
- `GET /events/availability`;
- `GET /events/partner`;
- `GET /events/common`;
- `GET /events/managed`;
- `GET /events/pending-approval`;
- `GET /events/minidash`;
- `GET /events/minidash/admin`;
- `PATCH /events/{id}`;
- `PUT /events/{id}/logo`;
- `DELETE /events/{id}/logo`;
- `POST /events/{id}/staff`;
- `GET /events/{id}/staff`;
- `PATCH /events/{id}/staff/{userId}`;
- `DELETE /events/{id}/staff/{userId}`;
- `PATCH /events/{id}/approve`;
- `PATCH /events/{id}/reject`;
- `POST /events/{id}/partner`;
- `DELETE /events/{id}/partner`;
- `PATCH /events/{id}/schedule`;
- `POST /events/{id}/transfer-request`.

Criacao de evento:

`Controller -> CreateEventRequestDto -> EventManagementService -> EventRepository/EventSchedulingRepository/EventStaffRepository -> events/event_scheduling/event_staffs`

O evento inicia com `approved=null`, `partnerEvent=false`, cria schedule e adiciona o host como staff com permissoes totais e cargo `Dono`.

Edicao de evento usa `JsonNode`, nao DTO formal. Campos de agenda acionam upsert de schedule.

Agenda:

- se nao ha schedule ou se schedule atual esta em andamento/passado, cria novo registro em `event_scheduling`;
- se schedule futuro existe, atualiza;
- pode sincronizar com Google Calendar se credenciais e calendar id estiverem configurados.

Paginacao:

- eventos usam `PagedResponseDto`;
- page e zero-based;
- size e limitado a 50.

Filtros observados:

- `state`;
- `local`;
- `name`;
- `month` no formato `yyyy-MM`;
- `weekStart` no formato `yyyy-MM-dd`, exigindo segunda-feira;
- `type` com `meet` ou `evento`;
- `approved`;
- `scope` com `managed`, `partner`, `common`;
- `upcoming`;
- `idUsuario` em algumas rotas.

Autorizacao de eventos:

- algumas rotas usam regra global autenticada;
- algumas usam `@PreAuthorize`;
- `EventAccessService` verifica permissoes globais, dono do evento ou staff com flags especificas.

### Publico E Convites

- `GET /public/events`: lista eventos publicos filtrados.
- `GET /invites/event-transfer/{token}`: valida convite de transferencia.
- `POST /invites/{token}/accept`: aceita convite.
- `POST /invites/{token}/reject`: recusa convite.

Convites de transferencia usam `invites`, `event_transfer_requests`, `event_transfer_logs`, `events` e `event_staffs`.

### Admin

Rotas observadas:

- `/admin/session`
- `/admin/overview`
- `/admin/metrics`
- `/admin/metrics/general`
- `/admin/users`
- `/admin/users/{id}/status`
- `/admin/users/{id}/role`
- `/admin/users/reliability`
- `/admin/users/{id}/reliability`
- `/admin/partners`
- `/admin/partners/{id}`
- `/admin/partners/{id}/status`
- `/admin/bot/status`
- `/admin/bot/logs`
- `/admin/bot/logs/stream`
- `/admin/announcements/config`
- `/admin/announcements`

Admin le agregados de usuarios, comunidades Discord, usuarios Discord/Telegram, comandos, parceiros, warnings, bans e eventos.

Escritas observadas:

- `api_user_roles` para alteracao de papel administrativo;
- `partners`, `partner_links`, `partner_external_links`;
- buffer de logs do bot em memoria.

Limites/stubs:

- `AdminUsersService.updateStatus` so aceita `active`; mute/ban retorna erro informando que exige dados de moderacao por comunidade.
- `AdminAnnouncementsService` valida destinos/imagem e retorna status, mas nao foi observada integracao real de envio para Discord/Telegram.

### Interno

- `POST /internal/bot/logs`
- `POST /internal/bot/logs/batch`

Esses endpoints sao `permitAll` no SecurityConfig, mas fazem autenticacao propria por Bearer token em `InternalBotLogsController`.

Logs ficam em buffer em memoria (`ArrayDeque`) e podem ser transmitidos por SSE em `/admin/bot/logs/stream`.

## Cache

`CacheConfig` habilita cache Caffeine com TTL e tamanho maximo configuraveis.

Caches nomeados:

- `dashboardAnalytics`;
- `dashboardEventCards`;
- `dashboardManagedEvent`;
- `dashboardServers`;
- `dashboardUserStatus`;
- `eventAvailability`;
- `adminAnnouncementsConfig`;
- `adminBotStatus`;
- `adminGeneralMetrics`;
- `adminMetrics`;
- `adminOverview`;
- `adminPartners`;
- `adminSession`;
- `adminUsers`.

Alteracoes em parceiros, usuarios admin e eventos usam `@CacheEvict` em pontos especificos.

## Integracoes Externas

### SMTP

Usado para:

- confirmacao de e-mail;
- recuperacao de senha;
- convite/recusa de transferencia de evento.

### Google Calendar

`EventManagementService` usa service account para obter access token e criar/atualizar/remover eventos em calendarios configurados. Calendario depende do status de aprovacao:

- pendente;
- aprovado;
- rejeitado.

Timezone usado para payload: `America/Sao_Paulo`.

### OAuth Google/Discord

Configurado condicionalmente. Discord usa endpoints oficiais de OAuth/API de usuario. Google usa `CommonOAuth2Provider`.

### Cloudflare R2/S3

Usado para:

- fotos de perfil;
- logos de evento.

Arquivos aceitos: JPEG, PNG, WebP. Tamanho maximo observado: 5 MB. Imagens sao convertidas para WebP e salvas em chaves como:

- `users/{userId}/profile/{uuid}.webp`;
- `events/{eventId}/logo/{uuid}.webp`.

### Bot

Admin consulta status em `app.bot.base-url/status` com Bearer token opcional.

`GET /user/me/managed-servers` identifica o usuario pelo JWT, resolve seu vinculo em `user_discord` e consulta o Coddy por `app.bot.base-url/managed-guilds/{discordUserId}` com o mesmo token interno. O cliente nunca informa permissao ou guild ao endpoint: o Coddy retorna apenas guilds nas quais ele esta instalado e o membro e dono, administrador ou possui `manage_guild`. Indisponibilidade do Coddy retorna 503; ausencia de vinculo ou de guild elegivel retorna lista vazia.

Logs podem ser ingeridos pela API e servidos via SSE. O buffer e em memoria, nao persistido no banco.

## Testes

Testes ficam em `src/test/java`.

Padroes observados:

- JUnit 5;
- Mockito;
- `@ExtendWith(MockitoExtension.class)`;
- `MockMultipartFile`;
- `@SpringBootTest` pontual;
- `@Sql` pontual para preparar colunas de imagem de perfil em teste.

Areas cobertas:

- carregamento/normalizacao de `.env`;
- auth local;
- social OAuth handler e conflito seguro entre identidades;
- vinculos administrativos de identidade e historico de moderacao agregado;
- eventos;
- disponibilidade de eventos;
- transferencia de eventos;
- user dashboard;
- user general info;
- profile image service/storage.

Risco de validacao:

- nao se deve executar a aplicacao como etapa padrao;
- nao se deve executar automaticamente a suite completa se ela puder inicializar Spring Context, datasource ou integracoes externas;
- uma `.env` local pode conter credenciais reais;
- antes de `./mvnw test`, e necessario confirmar datasource isolado, ausencia de secrets produtivos, impossibilidade de alterar dados reais/compartilhados e ausencia de chamadas externas inseguras.

Quando isso nao for confirmavel, prefira testes unitarios isolados ou documente as validacoes pendentes para ambiente seguro.

## Deploy E CI/CD

A producao usa Docker/GHCR como unico runtime da API.

O workflow `.github/workflows/prod-deploy.yaml`:

- dispara em push relevante para `main` ou por `workflow_dispatch`;
- roda `./mvnw test` em runner GitHub-hosted;
- constroi a imagem pelo `Dockerfile` e publica no GHCR;
- valida o digest SHA256 retornado pelo registry;
- envia ao runner self-hosted `BRFAPI` somente a referencia imutavel da imagem;
- prepara o ambiente de producao pelo wrapper root-owned `/usr/local/sbin/update-brafurries-api-env`;
- implanta pelo wrapper root-owned `/usr/local/sbin/deploy-brafurries-api`;
- valida readiness e `/meta/locales` diretamente em `127.0.0.1:18080`;
- valida o CORS dos Firebase Preview Channels no runtime e atraves de `https://api.brafurries.com.br`.

O container de producao usa `network_mode: host`, escuta somente em `127.0.0.1:18080` e e publicado externamente por Apache/Cloudflare. O rollback de runtime e de ambiente e responsabilidade do wrapper Docker; PM2 nao faz parte da arquitetura vigente.

Mais detalhes estao em [`docs/docker-migration.md`](docs/docker-migration.md).

## Convencoes De Codigo Observadas

- Pacote base com capitalizacao `com.Brafurries.API`.
- Services anotados com `@Service`.
- Controllers com `@RestController` e `@RequestMapping`.
- DTOs como `record`s agrupados em classes por modulo.
- Lombok usado em entidades e alguns controllers/services (`@Getter`, `@Setter`, `@AllArgsConstructor`, `@RequiredArgsConstructor`, etc.).
- Validacao por annotations Jakarta em DTOs, mas tambem muita validacao manual em services.
- Erros de negocio frequentemente usam `ResponseStatusException`.
- Datas usam `LocalDateTime`, `LocalDate`, `Instant` conforme contexto.
- Mapeamento entidade-DTO manual.
- OpenAPI via annotations `@Operation`, `@Tag` em varios controllers.

## Pontos De Cuidado

- Schema MariaDB compartilhado com outras aplicacoes.
- Ausencia de migrations formais.
- `.env` pode conter credenciais reais; valores nunca devem ser expostos.
- `permitAll` pode conviver com autenticacao propria no controller.
- A resolucao definitiva de conflitos entre e-mail e provedor social exige redesign especifico; vinculo de identidade nao compartilha autenticacao.
- `EventController` concentra logica alem do padrao controller fino.
- Edicao de eventos usa `JsonNode`, permitindo patch parcial sem DTO validado formalmente.
- Google Calendar e R2 podem causar efeitos externos se a aplicacao ou testes integrados forem executados em ambiente real.
- Cache pode esconder alteracoes em leituras administrativas/dashboard se invalidacao nao for considerada.
- `AccessTokenService` guarda tokens em memoria, mas a autenticacao Bearer observada usa `TokenService`; pode ser legado ou uso incompleto.
- `/oauth/token` parece placeholder/stub.

## Community lifecycle V1

Community deixa de ser criada ou reivindicada pelo usuário. Sua disponibilidade comum depende de uma integração Discord ativa, e a API aplica observações autenticadas do runtime para estado vivo, ownership, snapshots completos de membros e lifecycle dos sync runs. A semântica detalhada, inclusive a regra futura de network split sem cópia de dados entre tenants, está em [`docs/community-lifecycle-v1.md`](docs/community-lifecycle-v1.md).
- Entidades duplicadas para algumas tabelas podem indicar legado ou separacao parcial de dominios.

## Informacoes Nao Determinadas Com Seguranca

- Dono formal do schema MariaDB.
- Processo real usado pela equipe para aplicar alteracoes de schema.
- Quais outras aplicacoes leem ou escrevem cada tabela.
- Se `/oauth/token` e contrato legado, stub temporario ou feature incompleta.
- Ambiente de testes seguro/isolado.
- Politica formal de versionamento de API.
- Contratos completos esperados pelo frontend para todos os endpoints.
- Estrategia de rollback de schema/dados.
