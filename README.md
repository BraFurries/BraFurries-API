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

**Durante o cutover, o workflow de produção é somente manual (`workflow_dispatch` na `main`).** Não há gatilho `push` no workflow produtivo, mesmo que alguma variável do repositório esteja configurada. Depois de congelar o deploy do arquivo privado e validar o primeiro deploy manual, uma **PR separada** habilitará o gatilho automático, sob revisão.

O runner `api-production`/label `BRFAPI` executa exclusivamente wrappers root-owned de deploy e staging na VM; não faz checkout, não recebe acesso genérico ao Docker e não executa SQL. A VM utiliza **sudo 1.9.9**, então o novo runner precisa do gateway `/usr/local/sbin/deploy-brafurries-api-public` (root-owned, sem argumentos CLI) e do sudoers específico em `BraFurries-Infrastructure`; o token GHCR segue apenas no stdin. O gateway **não está instalado na VM** por efeito desta PR e precisa ser validado antes do primeiro deploy manual. O GitHub Environment `Produção` contém o segredo `PROD_ENV_FILE` para o deploy. A permissão `packages: write` é necessária somente no job de build hospedado pelo GitHub, e o acesso ao pacote GHCR preexistente deve ser explicitamente concedido a este repositório.

**Atenção:** enquanto o cutover não for concluído, `BraFurries/Archive-BraFurries-API` continua com o deploy automático de produção. Não habilite o automático do público até remover a autoridade de deploy do arquivo privado e validar manualmente o novo runner/caminho. Consulte [o runbook de migração](docs/production-deploy-cutover.md) para dependências, verificações e rollback.

Mudanças de schema continuam sob responsabilidade exclusiva de `BraFurries-Database`. O deploy da API não executa migrations, não altera o schema e não garante rollback de dados.
