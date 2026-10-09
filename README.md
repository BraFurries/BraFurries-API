# BraFurries-API

## Recuperação de senha

Foram adicionados dois endpoints:

- `POST /api/auth/forgot-password` com payload `{ "email": "usuario@dominio.com" }`
- `POST /api/auth/reset-password` com payload `{ "token": "...", "password": "NovaSenha123" }`

O primeiro endpoint sempre retorna uma mensagem neutra para evitar enumeração de usuários.

### Configuração da conta SMTP (envio de e-mail)

Defina as variáveis de ambiente abaixo:

- `SMTP_HOST` (ex.: `smtp.gmail.com`)
- `SMTP_PORT` (ex.: `587`)
- `SMTP_USERNAME` (conta SMTP e e-mail remetente das mensagens do sistema)
- `SMTP_PASSWORD` (senha de app/token SMTP)
- `RESET_PASSWORD_BASE_URL` (URL do front-end para redefinir senha, ex.: `https://app.seudominio.com/reset-password`)

No arquivo `.env`, informe a senha sem aspas. Caracteres como `$` sao lidos literalmente pelo carregador de `.env` da aplicacao.

Exemplo (Linux/macOS):

```bash
export SMTP_HOST=smtp.gmail.com
export SMTP_PORT=587
export SMTP_USERNAME=exemplo@example.com
export SMTP_PASSWORD=sua-senha-de-app
export RESET_PASSWORD_BASE_URL=https://app.seudominio.com/reset-password
```

> Para Gmail/Outlook, use senha de aplicativo (App Password) quando MFA estiver habilitado.

## CI e preparação do deploy de produção

O CI público executa validação do workflow, Gitleaks no histórico, testes e empacotamento Maven em runners hospedados pelo GitHub. A publicação produtiva está definida em [`.github/workflows/prod-deploy.yaml`](.github/workflows/prod-deploy.yaml), mas **continua desabilitada por padrão** e exige cutover operacional explícito.

**Deploy controlado por variável:** `workflow_dispatch` na `main` sempre permite execução manual. Um `push` na `main` somente inicia o pipeline de produção quando a **repository variable** `BRF_API_AUTO_DEPLOY_ENABLED` tem valor exatamente `true`. Com valor ausente, `false` ou qualquer outro, os jobs produtivos são ignorados. O primeiro deploy público manual foi validado na [execução #37983891631](https://github.com/BraFurries/BraFurries-API/actions/runs/37983891631). **Manter a variável `false` durante o merge desta mudança, até aprovação separada da ativação automática.**

O runner `api-production`/label `BRFAPI` executa exclusivamente wrappers root-owned de deploy e staging na VM; não faz checkout, não recebe acesso genérico ao Docker e não executa SQL. A VM utiliza **sudo 1.9.9**, então o novo runner precisa do gateway `/usr/local/sbin/deploy-brafurries-api-public` (root-owned, sem argumentos CLI) e do sudoers específico em `BraFurries-Infrastructure`; o token GHCR segue apenas no stdin. O gateway foi instalado na VM em uma etapa operacional separada e utilizado com sucesso no primeiro deploy público manual; esta PR não instala ou altera arquivos na VPS. O GitHub Environment `Produção` contém o segredo `PROD_ENV_FILE` para o deploy. A permissão `packages: write` é necessária somente no job de build hospedado pelo GitHub, e o acesso ao pacote GHCR preexistente deve ser explicitamente concedido a este repositório.

**Atenção:** o `push` de produção do arquivo privado foi desativado via [PR #167](https://github.com/BraFurries/Archive-BraFurries-API/pull/167); o deploy manual de contingência foi preservado. **Não habilite a variável automática da API pública sem autorização específica para a mudança de `false` para `true`**, mesmo após o deploy manual validado. Consulte [o runbook de migração](docs/production-deploy-cutover.md) para dependências, verificações e rollback.

Mudanças de schema continuam sob responsabilidade exclusiva de `BraFurries-Database`. O deploy da API não executa migrations, não altera o schema e não garante rollback de dados.
