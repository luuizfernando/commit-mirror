# RUNBOOK: commit-mirror

Operação do dia a dia na VPS (`/projects/commit-mirror`, usuário `root`). Para testar antes de publicar, veja `TESTE-LOCAL.md`.

## 1. Configuração obrigatória no `.env` da VPS

Nenhum destes valores vai para o GitHub. Ficam só no `.env` (modo 600).

| Variável | Origem |
|---|---|
| `TELEGRAM_BOT_TOKEN` | BotFather (`/newbot`) |
| `TELEGRAM_WEBHOOK_SECRET` | você gera (ex.: `openssl rand -hex 32`); só letras, números, `_` e `-`, até 256 caracteres |
| `TELEGRAM_ALLOWED_USER_ID` | seu id numérico (ex.: via `@userinfobot`) |
| `APP_GITHUB_PAT` | PAT fine-grained, apenas este repositório, permissão *Contents: read/write* |
| `GITHUB_OWNER`, `GITHUB_REPO` | dono e nome do repositório público que recebe os logs |
| `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL` | e-mail **vinculado à sua conta GitHub** (senão não conta no gráfico) |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `DB_URL` | banco do compose (`DB_URL=jdbc:postgresql://db:5432/<POSTGRES_DB>`) |
| `IMAGE_TAG` | gerenciado pelo `deploy.sh` |

Opcionais (valores padrão no `.env.example`): `GITHUB_BRANCH`, `LOGS_DIRECTORY`, `APP_TIME_ZONE`, `RECOVERY_*`.

Condição para o commit contar no gráfico: o commit precisa estar na branch padrão do repositório e o e-mail do autor precisa estar verificado na conta.

## 2. Nginx e TLS (primeira instalação, o Nginx ainda não existe na VPS)

Pré-requisitos: o registro `A` (e `AAAA`, se houver IPv6) de `bot.luuizfernando.tech` apontando para o IP da VPS, criado no painel DNS do domínio e as portas 80 e 443 liberadas. A arquitetura da VPS é x86_64, compatível com a imagem `linux/amd64`.

```bash
apt update && apt install -y nginx certbot python3-certbot-nginx
ufw allow OpenSSH && ufw allow "Nginx Full" && ufw enable   # 80 e 443; o 8080 e o 8081 continuam só em 127.0.0.1

cp nginx/nginx.conf /etc/nginx/conf.d/commit-mirror.conf
nginx -t && systemctl reload nginx
certbot --nginx -d bot.luuizfernando.tech   # emite o certificado, ativa o 443 e o redirecionamento; renova sozinho
```

O arquivo versionado (`nginx/nginx.conf`) expõe apenas `POST /telegram/webhook` (com limite de taxa e corpo máximo de 64k) para `127.0.0.1:8080`; qualquer outro caminho retorna 404 e o actuator (8081) nunca é exposto. Como a VPS não clona o repositório, copie o arquivo para lá (por exemplo com `scp`).

Validação: `curl -i https://bot.luuizfernando.tech/telegram/webhook` (GET) deve dar 405/403, `curl -i https://bot.luuizfernando.tech/actuator/health` deve dar 404.

## 3. Registrar o webhook (uma vez, depois do primeiro deploy saudável)

O Telegram exige certificado válido, então faça isto só depois da seção 2.

```bash
export TELEGRAM_BOT_TOKEN=...        # sem eco no histórico: use `read -rs`
export TELEGRAM_WEBHOOK_SECRET=...   # o mesmo valor do .env
./deploy/setup-webhook.sh set https://bot.luuizfernando.tech/telegram/webhook
./deploy/setup-webhook.sh info       # confira: url correta, sem last_error_message, pending_update_count baixo
```

`setup-webhook.sh delete` remove o webhook (ex.: para voltar a testar localmente).

## 4. Verificação ponta a ponta em produção

1. `/commit teste` no bot: chega "Commit N registrado." e aparece a linha em `commit-logs/AAAA-MM.md` com o commit no gráfico.
2. Outro usuário mandando `/commit x`: nada acontece (ignorado em silêncio).
3. `curl -i -X POST https://bot.luuizfernando.tech/telegram/webhook -d '{}'` sem o header: 403.
4. PAT revogado de propósito: resposta "não foi registrado agora..." e, depois de 3 tentativas, "falhou definitivamente". Restaure o PAT e reinicie (ou aguarde): o registro `FAILED` mantém o número.

## 5. Atualização e rollback

- Atualização: automática pelo pipeline (push na `main` fora de `commit-logs/**` e documentação).
- Rollback manual: `/projects/commit-mirror/deploy.sh <tag anterior>` (tags nas Releases do GitHub). As migrations são sempre aditivas, então a imagem anterior continua compatível.

## 6. Diagnóstico

```bash
docker compose ps
docker compose logs --tail 100 app
docker compose exec db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "select sequence_number, status, attempt_count, updated_at from commit_log order by sequence_number desc limit 10;"
```

- `FAILED` com `attempt_count = 3`: falha definitiva. Corrija a causa (PAT, permissão, GitHub fora do ar) e reabra: `update commit_log set attempt_count = 0 where status = 'FAILED';` (o reprocesso automático roda a cada `RECOVERY_INTERVAL` e mantém o número).
- `PENDING` antigo: o processo caiu no meio; o reprocesso automático cuida disso na subida e a cada intervalo.
- App não sobe: `TELEGRAM_*`, `APP_GITHUB_PAT`, `GITHUB_OWNER`, `GITHUB_REPO`, `GIT_AUTHOR_*` ausentes ou em branco falham a subida de propósito (veja o log).

## 7. Rotação de segredos

| Segredo | Como |
|---|---|
| Token do bot | BotFather `/revoke`, atualizar `.env`, reiniciar o app e rodar `setup-webhook.sh set` |
| `TELEGRAM_WEBHOOK_SECRET` | novo valor no `.env`, reiniciar e rodar `setup-webhook.sh set` com o mesmo valor |
| PAT do GitHub | gerar novo, atualizar `APP_GITHUB_PAT`, reiniciar; anote a data de expiração |
| Tokens do Docker Hub | trocar o secret `DOCKERHUB_TOKEN` no GitHub e refazer `docker login` na VPS |
| Chave SSH do deploy | nova chave em `authorized_keys` (com o forced command) e no secret `VPS_SSH_KEY` |

## 8. Backup do banco

```bash
docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > backup-$(date +%F).sql.gz
```

O banco guarda o texto digitado e o contador global; perder o volume faz o contador recomeçar em 1 (e o arquivo de log já tem números maiores).
