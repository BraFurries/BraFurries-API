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

## CI público

Este repositório disponibiliza apenas validação em runners hospedados pelo GitHub: testes e empacotamento Maven. A infraestrutura, publicação de imagens e deploy de produção não fazem parte deste repositório público.
