# BraFurries — configuração privada da Gestão Comercial

Esta documentação é operacional. **Não** habilitar o módulo por meio de CommunityCapability ou conceder acesso implícito a Community Admin.

## Identificadores confirmados pelo proprietário (03/11/2026)

- `COMMERCE_OWNER_USER_ID=2` — ID **interno** do usuário BraFurries em `users.id`.
- `COMMERCE_OFFICIAL_COMMUNITY_ID=1` — Community oficial da BraFurries, usada somente para validar cargos do Time.
- Sem qualquer `community_id` nas tabelas comerciais; os dados são exclusivos da organização BraFurries.

## Pré-requisitos R2

1. Em <https://dash.cloudflare.com/>, acesse **Storage & Databases → R2 Object Storage → Overview**, no **mesmo Cloudflare Account** do `R2_ENDPOINT` usado pela API (mesma jurisdição).
2. **Create bucket**, nome sugerido **`brafurries-contratos-privados`** (exato e único na conta), Standard; mantenha **Public Development URL / r2.dev OFF**, sem **Custom Domains** nem acesso público. Buckets são privados por padrão. Contratos não podem ser publicados no bucket das imagens.
3. Em R2 Overview → **Manage R2 API Tokens**, crie um token **Object Read & Write**, opção **Apply to specific buckets only**, marque **somente** `brafurries-contratos-privados`.
4. Armazene **Access Key ID** e **Secret Access Key** no gerenciador de segredos. O Secret só aparece na criação. **Nunca** registre essas chaves em issue, PR, log, commit, chat ou valor público.
5. Confirme o endpoint S3 da conta existente (`R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com`). Uma jurisdição diferente exige endpoint diferente; manter o bucket na mesma conta/jurisdição evita adaptar a API.

### Configuração da API

Os seguintes valores devem fazer parte do arquivo privado de ambiente da API:

```dotenv
COMMERCE_OWNER_USER_ID=2
COMMERCE_OFFICIAL_COMMUNITY_ID=1
COMMERCE_PRIVATE_R2_BUCKET=brafurries-contratos-privados
COMMERCE_PRIVATE_R2_ACCESS_KEY=<ACCESS_KEY_ID_DO_TOKEN_PRIVADO>
COMMERCE_PRIVATE_R2_SECRET_KEY=<SECRET_ACCESS_KEY_DO_TOKEN_PRIVADO>
```

Os três primeiros valores **não** são credenciais. Os dois últimos são confidenciais e devem ficar exclusivamente no ambiente privado de deploy.

O serviço privado lê `R2_ENDPOINT` existente e suas **credenciais comerciais próprias**; não reutiliza `R2_ACCESS_KEY` e `R2_SECRET_KEY` da mídia pública. A ausência de bucket ou de uma credencial gera falha fechada (503) apenas para a operação com PDFs. Não configurar políticas públicas nem URLs R2 para este bucket.

**Deploy real:** a API usa o segredo do GitHub Environment **Produção** denominado `PROD_ENV_FILE`, cujo conteúdo completo é instalado no servidor pelo workflow `.github/workflows/prod-deploy.yaml`. Alterar esse segredo preservando **todas as demais linhas existentes**; não substituir o arquivo inteiro só pelos novos valores. Na atualização desse segredo, não incluir aspas/strings no arquivo. O usuário administrador deve fazê-lo em interface segura; este PR não acessa o conteúdo desse segredo.

## Ordem de rollout (não executado por este PR)

1. Conferir backup, migrações existentes e impacto no MariaDB/Flyway; aplicar Database #41 depois Database #42.
2. Confirmar bucket privado e credenciais, verificar ausência de URL pública. Validar que o token consegue ler/escrever exclusivamente objetos no bucket novo (sem executar testes com contratos reais).
3. Revisar diff/threads/checks/HEAD atualizado; integrar API #63 depois API #64, garantindo que todos os commits de #64 cheguem a `main`.
4. Atualizar `PROD_ENV_FILE` e executar deploy controlado da API (autodeploy não deve ser habilitado sem autorização).
5. Verificar `/actuator/health/readiness` e usar conta do proprietário para testar GET de acesso, criação de contrato sintético, download autenticado e autorização negativa para outra conta. Sem dados privados reais nos testes.
6. Integrar Front #47 e depois Front #48, verificar menu e formulários com conta autorizada e não autorizada.
7. **Não** habilitar vendas/royalties/fechamentos até as migrations e regras financeiras adicionais.

## Referências

- https://developers.cloudflare.com/r2/buckets/create-buckets/
- https://developers.cloudflare.com/r2/buckets/public-buckets/
- https://developers.cloudflare.com/r2/api/tokens/
