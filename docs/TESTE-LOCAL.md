# Teste local antes de subir

Objetivo: validar a imagem real (a mesma que o pipeline vai publicar) com Postgres de verdade e conversar com o bot no Telegram, sem tocar na VPS nem no repositório público. Pré-requisitos: Docker Desktop e JDK no `JAVA_HOME` (só para o modo com fakes).

Há dois modos. O **A** já foi executado com sucesso no desenvolvimento; o **B** exige as suas credenciais.

## Modo A: imagem real + GitHub e Telegram falsos (sem credenciais)

Os fakes (`src/test/java/.../support`) imitam a Contents API do GitHub (com validação do `sha`, 409 em conflito) e o `sendMessage` do Telegram, e imprimem no console o que receberiam.

```bash
# 1. imagem e fakes
docker build -t luizzfernando/commit-mirror:local --build-arg REVISION=0.2.0-local .
./mvnw -q test-compile
java -cp target/test-classes com.luiz.commit_mirror.support.FakeApis     # deixe rodando (portas 9101 e 9102)

# 2. ambiente fora do repositório
mkdir ../commit-mirror-local && cp docker-compose.yaml ../commit-mirror-local/ && cd ../commit-mirror-local
```

Crie `.env` nessa pasta:

```
IMAGE_TAG=local
POSTGRES_DB=commit_mirror
POSTGRES_USER=commit_mirror
POSTGRES_PASSWORD=localpass
DB_URL=jdbc:postgresql://db:5432/commit_mirror
TELEGRAM_BOT_TOKEN=123456:FAKE-LOCAL-TOKEN
TELEGRAM_WEBHOOK_SECRET=local-secret
TELEGRAM_ALLOWED_USER_ID=42
TELEGRAM_BASE_URL=http://host.docker.internal:9102
APP_GITHUB_PAT=fake-pat
GITHUB_OWNER=me
GITHUB_REPO=commit-mirror
GITHUB_API_URL=http://host.docker.internal:9101
GIT_AUTHOR_NAME=Local Tester
GIT_AUTHOR_EMAIL=local@example.com
```

```bash
docker compose -p cmlocal up -d      # espere ficar healthy: docker compose -p cmlocal ps
curl -i -X POST http://127.0.0.1:8080/telegram/webhook -H 'Content-Type: application/json' \
  -H 'X-Telegram-Bot-Api-Secret-Token: local-secret' \
  -d '{"update_id":1,"message":{"message_id":1,"from":{"id":42},"chat":{"id":42},"text":"/commit teste"}}'
```

O que conferir no console dos fakes: `commit 'Commit 1' in commit-logs/AAAA-MM.md`, autor/e-mail/data e `[telegram] ... Commit 1 registrado.`; no banco: `docker exec cmlocal-db-1 psql -U commit_mirror -d commit_mirror -c "select * from commit_log"`.

Resultados esperados: secret errado -> 403; outro `from.id` -> 200 sem efeito; `/commit` vazio -> resposta de uso; mesmo `update_id` reenviado -> ignorado; texto digitado ausente do `docker logs` e do arquivo; `http://127.0.0.1:8081` (actuator) inacessível do host.

Limpeza: `docker compose -p cmlocal down -v` e encerre os fakes.

## Modo B: bot real do Telegram + GitHub de teste

Para o Telegram alcançar a sua máquina precisa de uma URL HTTPS pública temporária (túnel):

1. **Bot de teste:** crie outro bot no BotFather (não use o de produção) e anote o token.
2. **GitHub de teste:** crie um repositório descartável (ou use o fake do modo A mudando só o Telegram) e um PAT fine-grained só para ele com *Contents: read/write*. Não use o repositório definitivo: o commit de teste entra no gráfico de verdade.
3. **`.env` local** como no modo A, trocando: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET` (gere um), `TELEGRAM_ALLOWED_USER_ID` (seu id), remova `TELEGRAM_BASE_URL`, e use `APP_GITHUB_PAT`, `GITHUB_OWNER`, `GITHUB_REPO`, `GIT_AUTHOR_*` reais (remova `GITHUB_API_URL`).
4. **Túnel:** `cloudflared tunnel --url http://localhost:8080` (ou `ngrok http 8080`); copie a URL `https://...`.
5. **Registrar o webhook** apontando para o túnel:
   ```bash
   export TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=...
   ./deploy/setup-webhook.sh set https://<url-do-tunel>/telegram/webhook
   ./deploy/setup-webhook.sh info
   ```
6. Envie `/commit teste` ao bot. Esperado: "Commit 1 registrado." e a linha em `commit-logs/AAAA-MM.md` do repositório de teste.
7. **Falha e recuperação:** troque `APP_GITHUB_PAT` por um valor inválido, `docker compose up -d`, envie `/commit x`: chega o aviso "vou tentar novamente"; restaure o PAT e reinicie: o registro `FAILED` é reprocessado com o mesmo número.
8. **Limpeza:** `./deploy/setup-webhook.sh delete`, encerre o túnel e `docker compose down -v`. Antes de registrar o webhook de produção, use o bot e os segredos definitivos (ver `RUNBOOK.md`).

## Antes do push

- `./mvnw verify` verde (unitários, integração e cobertura).
- `.env` da VPS com todas as variáveis do `RUNBOOK.md`: sem elas o app não sobe, o healthcheck falha e o rollback automático entra.
- Lembre que o `deploy.sh` da VPS só troca a tag da imagem: o `docker-compose.yaml` e o `.env` da VPS são atualizados à mão.
