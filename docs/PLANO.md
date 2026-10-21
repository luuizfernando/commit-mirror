# Plano: commit-mirror (Telegram → GitHub) com TDD e CI/CD desde a Etapa 0

> **Estado atual (atualizado após a Etapa 0 local):** plano aprovado e em execução.
> - Etapa 0: arquivos e validações locais concluídos (`./mvnw verify` verde, actionlint/shellcheck ok, imagem Docker e smoke test ok). Faltam: preparar a VPS, cadastrar secrets (feito), primeiro push e verificação do pipeline.
> - Etapas 1 a 5: implementadas localmente (nada commitado). `./mvnw verify` verde (JDK 25 com `--release 21`): 110 testes unitários, 27 de integração, cobertura ok. Imagem construída e testada localmente com Postgres real e fakes de GitHub/Telegram (ver `docs/TESTE-LOCAL.md`). Q10 resolvida seguindo a recomendação (`attempt_count` e `updated_at` incluídos). Pendente: configurações da VPS/GitHub/Telegram descritas em `docs/RUNBOOK.md`, teste com o bot real e push.
> - O app agora exige `TELEGRAM_*`, `APP_GITHUB_PAT`, `GITHUB_OWNER`, `GITHUB_REPO` e `GIT_AUTHOR_*`; o `smoke-test.sh` passa valores fictícios.
> - Decisões do dono: Q7 Nginx ainda não existe (instalação em `docs/RUNBOOK.md` §2, config em `nginx/nginx.conf`, domínio `bot.luuizfernando.tech`, VPS x86_64); Q8 namespace `luizzfernando` confirmado; Q9 `main` sem proteção por enquanto; diretório dos logs é `commit-logs` (padrão no código, `.env.example` e `paths-ignore` do pipeline).
> - **Decisões que mudaram desde a aprovação** (valem sobre o texto abaixo quando houver conflito):
>   - Contador **global** (nunca reinicia) e linha do log e mensagem do commit apenas `Commit N`, sem data/hora. A coluna é `sequence_number`, com `UNIQUE(sequence_number)`.
>   - A pasta de ADR é `ADR/` (não `ADR/`).
>   - Na VPS: tudo como **root**, pasta `/projects/commit-mirror` (não `/opt`), sem usuário `deploy`, sem environments do GitHub (todos os secrets são de repositório, `VPS_USER=root`) e `PermitRootLogin prohibit-password` ou `forced-commands-only`.
>   - A VPS não clona o projeto: só tem `docker-compose.yaml`, `.env` e `deploy.sh`, copiados à mão. O pipeline só troca a tag da imagem.
>   - O arquivo do compose de produção chama-se `docker-compose.yaml`; o `compose.local.yaml` não existe mais.
>   - Resilience4j, WireMock e a troca do starter do Spring Cloud ficam para as etapas que os usam.
>   - Scripts `deploy/deploy.sh` (VPS) e `deploy/smoke-test.sh` (CI) mantidos.

## Context

Meus commits do trabalho ficam num não aparecem no gráfico do GitHub. `/commit <mensagem>` no bot do Telegram → webhook → API Spring Boot grava o registro no Postgres e acrescenta uma linha em `logs/AAAA-MM.md` neste repo público (author/committer/datas explícitos, para contar no gráfico). O texto digitado nunca vai ao repo nem aos logs.
Este plano cobre implementação em etapas pequenas, TDD obrigatório, e um pipeline CI/CD (GitHub Actions → Docker Hub privado → deploy SSH na VPS) que existe ANTES de qualquer regra de negócio. Nada será commitado por mim ao final (item 14).

---

## 1. O que encontrei no repositório

- Branch `main`, commits: `Initial commit`, `project created`. Na data do plano: `pom.xml` alterado e `ADR/ADR-01.md` não rastreado (PT-BR; descreve a arquitetura, ordem antiga com CI/CD por último → precisa atualização, item 13).
- **groupId `com.luiz`, artifactId `commit-mirror`, package base `com.luiz.commit_mirror`** (com underscore) → mantidos. Classes: `CommitMirrorApplication`, e em teste `CommitMirrorApplicationTests` (`contextLoads` + Testcontainers), `TestCommitMirrorApplication`, `TestcontainersConfiguration` (usa `postgres:latest`).
- `application.yaml` (extensão `.yaml`, mantida) só tem `spring.application.name`.
- **Spring Boot 4.1.1** (Spring Framework 7, Jackson 3, JUnit 6, starters modulares: `spring-boot-starter-webmvc`, `-flyway`, `-*-test`) + Spring Cloud `2025.1.3`. Isso muda detalhes: `@MockitoBean` no lugar de `@MockBean`; pacotes novos das test-slices (`@WebMvcTest`, `@DataJpaTest`); Jackson 3 (`tools.jackson`, anotações `@JsonProperty` continuam em `com.fasterxml.jackson.annotation`). Cada ponto será confirmado compilando na Etapa 0.
- Sem H2 (nada a remover). Sem Dockerfile, compose, `.github/`, `.env.example`, testes reais.
- Ambiente local: `java -version` = **1.8**; precisa de JDK 21 no `JAVA_HOME` para `./mvnw`. Docker 29.5 disponível (Testcontainers local OK).
- `.gitignore` não cobre `.env`, `*.log`, `dependency-check-report*`.

## 2. Ajustes no pom

**Corrigir/remover**
- Remover `junit-jupiter-engine:5.7.0` (alteração não commitada): conflita com o JUnit 6 gerenciado pelo Boot 4.
- Preencher `<name>`/`<description>`/`<url>`/`<scm>` reais e remover `<license/>`, `<developer/>` vazios.
- `<version>`: `${revision}` com `<revision>0.1.0-SNAPSHOT</revision>` (base de versionamento, seção 9.3).

**Dependências**
| Item | Decisão |
|---|---|
| Flyway | `spring-boot-starter-flyway` + `flyway-database-postgresql` já presentes ✔ |
| Resilience4j | O pom tem `spring-cloud-starter-circuitbreaker-resilience4j` (só circuit breaker/time limiter, sem retry). `resilience4j-spring-boot3` é para Boot 3 e é arriscado no Boot 4. **Recomendo usar os módulos core `resilience4j-circuitbreaker` + `resilience4j-retry` de forma programática** (beans `Retry`/`CircuitBreaker` em `config`, parametrizados por `@ConfigurationProperties`), sem AOP/anotações. Remove também o starter do Spring Cloud (e o BOM `spring-cloud-dependencies`) se nada mais o usar. Versão a confirmar na Etapa 0. Alternativa: `@Retryable` nativo do Spring Framework 7. |
| WireMock | `org.wiremock:wiremock-standalone` (test) — evita conflito de Jetty/Jackson com Boot 4 |
| Testcontainers | `testcontainers-postgresql` + `testcontainers-junit-jupiter` + `spring-boot-testcontainers` já presentes ✔; trocar `postgres:latest` por `postgres:17-alpine` (mesma major da produção) |
| Mockito/AssertJ | vêm dos starters `*-test` ✔ |
| Lombok | **Não usar.** Records cobrem DTOs; entidade pequena; evita annotation processor e acoplamento ao Boot 4. |
| Actuator | já presente; expor só `health`. |

**Plugins**
- `maven-surefire-plugin` (`*Test`, unitários) e `maven-failsafe-plugin` (`*IT`, Testcontainers/WireMock/`@SpringBootTest`), ligado ao `verify`.
- **JaCoCo** (`prepare-agent`, `prepare-agent-integration`, `merge`, `report`, `check` no `verify`). Regra: LINE ≥ **80%** nos pacotes `service` e `domain`; BRANCH ≥ 70% em `service`; bundle ≥ 70%. Exclusões justificadas: `CommitMirrorApplication` (bootstrap), `config` (beans/props sem lógica), `dto` (records), `exception` (classes sem lógica). `integration` entra no bundle (testada via WireMock).
- **OWASP Dependency-Check via plugin Maven** (versão 12.x fixa, confirmar), sem fase ligada (não pesa o `mvn verify` local): `failBuildOnCVSS=7.0` (propriedade configurável `dc.failBuildOnCVSS`), `suppressionFiles=.github/dependency-check-suppressions.xml`, formatos HTML+JSON, `nvdApiKey` lida de `${env.NVD_API_KEY}`, `dataDirectory` em `~/.m2/dependency-check-data`, analisadores desnecessários desligados (OSS Index exige credencial; RetireJS irrelevante), `skipTestScope=true`.
- `spring-boot-maven-plugin` com `layers` (já default) e `build-info` (versão no Actuator `info`).

**Dependency-Check: plugin Maven vs Action oficial → recomendo plugin.**
Resolve o grafo exato de dependências do Maven (menos falso positivo/negativo que escanear o jar), a mesma config roda localmente (`./mvnw dependency-check:check`), suppressions e limite CVSS ficam no pom, cache é só um diretório. A Action é um wrapper do CLI: mais fácil de rodar, porém sem resolução Maven e com duas fontes de config.

## 3. Estrutura de pacotes (base `com.luiz.commit_mirror`)

Dependências só de cima para baixo: `controller → service → (repository, integration) → domain`; `dto`, `config`, `exception` transversais; `domain` não depende de ninguém; `controller` nunca chama `repository`/`integration`.

```
controller/   WebhookController            POST /telegram/webhook; lê header + body; delega; sempre 200 vazio no fluxo normal
service/      WebhookSecretValidator       comparação em tempo constante (MessageDigest.isEqual sobre SHA-256 dos dois valores); 403 se ausente/inválido
              UpdateDispatcher             valida allowlist, ignora update sem message/texto, usa CommandParser+CommandRegistry, envia resposta
              CommandParser                texto → (nome, argumentos); trata "/commit@NomeDoBot", trim, não-comando
              UserAllowlist                id permitido (config)
              CommitLogService             idempotência por update_id, gera o número global do commit (retry de colisão), transições de status
              CommitProcessingService      orquestra gateway GitHub + status SUCCESS/FAILED + resposta
              CommitMessageFormatter       "Commit N" (mensagem do commit no Git)
              LogLineFormatter             "Commit N" (linha do arquivo, sem data)
              LogFilePathResolver          logs/AAAA-MM.md pela commit_date
              LogWriteSerializer           fila de 1 worker (seção 7)
              PendingRecoveryService       reprocessa PENDING velhos / FAILED (seção 7)
service/command/ BotCommand (interface: name(), execute(CommandContext))
              CommandContext (record)
              CommandRegistry              injeta List<BotCommand>, indexa por name(), falha ao subir se nome duplicado
              CommitCommand                único comando da v1 (LOGGER próprio)
repository/   CommitLogRepository          Spring Data JPA (+ máximo global de sequence_number, FAILED, PENDING velho)
domain/       CommitLog (entidade; transições de estado), CommitStatus (enum)
integration/  TelegramClient (interface), RestClientTelegramClient
              GitHubContentsGateway (interface), RestClientGitHubContentsGateway
              TelegramApiException, GitHubConflictException, GitHubTransientException, GitHubPermanentException
dto/          TelegramUpdate, TelegramMessage, TelegramUser, TelegramChat (records, @JsonProperty snake_case), GitHub*Request/Response (records)
config/       AppProperties (@ConfigurationProperties + @Validated), RestClientConfig (2 RestClient com timeouts), ResilienceConfig, ExecutorConfig, ClockConfig
exception/    GlobalExceptionHandler (@RestControllerAdvice: secret inválido→403, JSON inválido→400; nunca devolve detalhes), InvalidWebhookSecretException
```
Nomenclatura por comportamento, sem sufixo `Impl`. Para adicionar um comando: um novo `@Component implements BotCommand`; controller e registry não mudam (teste prova isso).

## 4. Banco e migration

`V1__create_commit_log.sql` (Etapa 2; nenhuma migration na Etapa 0):

```sql
CREATE TABLE commit_log (
  id                 BIGSERIAL PRIMARY KEY,
  telegram_update_id BIGINT       NOT NULL,
  message            TEXT         NOT NULL,
  commit_date        DATE         NOT NULL,
  sequence_number    BIGINT       NOT NULL CHECK (sequence_number > 0),
  created_at         TIMESTAMPTZ  NOT NULL,
  status             VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING','SUCCESS','FAILED')),
  github_commit_sha  VARCHAR(64),
  CONSTRAINT uq_commit_log_update_id UNIQUE (telegram_update_id),
  CONSTRAINT uq_commit_log_sequence_number UNIQUE (sequence_number)
);
CREATE INDEX idx_commit_log_status ON commit_log (status, created_at);
```
- **Contador global (decidido):** `sequence_number` cresce continuamente (Commit 1 … Commit 400…), nunca reinicia por dia/mês; a coluna `daily_sequence` do modelo original foi renomeada e a UNIQUE `(commit_date, daily_sequence)` virou `UNIQUE(sequence_number)`. Mensagem do commit e linha do log são só `Commit N` → reprocessar reproduz exatamente a mesma mensagem/linha.
- `created_at` define as datas author/committer enviadas ao GitHub (contagem no gráfico).- `commit_date` = data local (America/Sao_Paulo) de `created_at`, não UTC (teste para 23:30 local = 02:30Z do dia seguinte); serve para escolher o arquivo mensal `logs/AAAA-MM.md`. Número N continua entre meses.
- **Proposta de desvio (decisão sua):** acrescentar `attempt_count INTEGER NOT NULL DEFAULT 0` e `updated_at` para limitar tentativas do reprocessamento automático e achar PENDING "velho". Sem isso, um FAILED permanente (ex.: PAT expirado) seria reprocessado para sempre.
- JPA: `ddl-auto: validate`. Migrations sempre aditivas (expand/contract) por causa do rollback.

## 5. Configuração (`application.yaml`) e variáveis

`AppProperties` (`app.*`, `@Validated`, `@NotBlank`/`@NotNull`, falha na subida se faltar segredo; sem default para segredos):

| Propriedade | Variável de ambiente |
|---|---|
| `app.telegram.bot-token` | `TELEGRAM_BOT_TOKEN` |
| `app.telegram.webhook-secret` | `TELEGRAM_WEBHOOK_SECRET` |
| `app.telegram.allowed-user-id` | `TELEGRAM_ALLOWED_USER_ID` |
| `app.telegram.base-url` (default `https://api.telegram.org`) | `TELEGRAM_BASE_URL` |
| `app.github.token` (PAT) | `APP_GITHUB_PAT` (evita confusão com o `GITHUB_TOKEN` do Actions) |
| `app.github.owner` / `repo` / `branch` / `base-url` | `GITHUB_OWNER`, `GITHUB_REPO`, `GITHUB_BRANCH` (default `main`), `GITHUB_API_URL` |
| `app.github.logs-directory` (default `logs`) | `LOGS_DIRECTORY` |
| `app.github.author-name` / `author-email` | `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL` (e-mail vinculado à conta GitHub) |
| `app.time-zone` (default `America/Sao_Paulo`) | `APP_TIME_ZONE` |
| `app.recovery.*` (intervalo, idade do PENDING, máx. tentativas) | `RECOVERY_*` |
| datasource | `DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` |
| imagem (compose) | `IMAGE_TAG` |

Perfis: padrão = produção (tudo por env, `management.server.port=8081`, só `health` exposto, `logging.level.org.springframework.web.client=INFO` — ver risco de vazamento do token na seção 7); `local` (Postgres local à escolha, valores fictícios); `test` (Testcontainers + WireMock).
`.env.example` com todas as chaves vazias/fictícias; `.env` no `.gitignore`.

## 6. Fluxo principal (camada / classe)

1. Telegram → `POST /telegram/webhook` — `WebhookController`.
2. Valida `X-Telegram-Bot-Api-Secret-Token` (tempo constante); inválido/ausente → 403 — `WebhookSecretValidator` (+ `GlobalExceptionHandler`).
3. `UpdateDispatcher`: sem `message`/texto → ignora (200 vazio); `from.id` fora da allowlist → ignora em silêncio (200 vazio, log `warn` sem conteúdo).
4. `CommandParser` extrai `/commit` + argumento; `CommandRegistry` resolve `CommitCommand`. Comando desconhecido → `warn` + resposta curta ao dono.
5. `CommitCommand`: argumento vazio/em branco → `warn` + resposta "Uso: /commit <mensagem>".
6. `CommitLogService`: se `update_id` já existe → `warn` + ignora (idempotência). Senão calcula `commit_date` e o próximo `sequence_number` global (max+1; colisão da constraint → retry em nova transação) e grava `PENDING`.
7. (assíncrono a partir da Etapa 4) `CommitProcessingService` → `LogWriteSerializer` → `GitHubContentsGateway`: GET arquivo do mês (404 = novo), acrescenta a linha, PUT com `sha`, branch, author/committer + datas.
8. `CommitLog` → `SUCCESS` (guarda SHA) ou `FAILED`.
9. `TelegramClient.sendMessage`: "Commit N registrado" ou motivo do erro (sem segredos nem texto digitado).

## 7. Concorrência, idempotência, retry e (a)sincronia — decisões

- **Idempotência:** UNIQUE em `telegram_update_id`; `INSERT` que viola → ignora. Cobre reentrega do Telegram.
- **Sequência global:** `max(sequence_number)+1`, cada tentativa numa transação `REQUIRES_NEW` (colisão "envenena" a transação); retry com limite configurável. FAILED/PENDING mantêm o número → **sem buracos**; reprocessar reusa o registro.
- **Serialização da escrita no GitHub:** a instância é única → um **executor de 1 thread (fila)** (`LogWriteSerializer`) serializa todo read-modify-write do arquivo. É a mesma fila do processamento assíncrono, então uma só peça cobre as duas coisas. (Com mais de 1 instância seria `pg_advisory_lock`; fora de escopo, registrar no ADR.) O 409 continua tratado como rede de segurança (ex.: commit manual meu no mesmo arquivo).
- **409:** exceção `GitHubConflictException`; o retry reexecuta a unidade inteira GET+PUT (novo `sha`). 409 **não** conta como falha no circuit breaker.
- **Retry (Resilience4j):** backoff exponencial com jitter; tenta em 409, 5xx, I/O; **não** tenta em 401/403/404 do repo/422. Ordem: `Retry( CircuitBreaker( GET+PUT ) )`. Máx. 3–4 tentativas, configurável.
- **Circuit breaker:** janela por contagem, % de falha configurável; aberto → falha rápida → `FAILED` com motivo "GitHub indisponível".
- **Resposta perdida (PUT ok, timeout na resposta):** antes de acrescentar, o gateway verifica se uma linha **inteira** igual a `Commit N` já está no arquivo (comparação por linha, para `Commit 1` não casar com `Commit 10`) → trata como sucesso (evita linha duplicada no retry/reprocessamento).
- **Síncrono vs assíncrono → recomendo assíncrono.** O webhook só valida, grava `PENDING` (1 INSERT) e devolve 200 em milissegundos; o GitHub (lento/instável, retry com backoff pode levar dezenas de segundos) roda na fila, e o resultado chega por `sendMessage`. Evita reentrega do Telegram por timeout. Combina com `PENDING` + idempotência: a reentrega bate na UNIQUE e é ignorada; se o processo cair com `PENDING` pendente, o `PendingRecoveryService` (na subida e agendado) reprocessa PENDING mais velho que N minutos e FAILED com `attempt_count < máx`, notificando só na falha definitiva.
- **Vazamento do token em exceções (risco real):** mensagens de `RestClientException`/`ResourceAccessException` incluem a URL (`.../bot<TOKEN>/sendMessage`). Os adapters capturam, lançam `TelegramApiException`/`GitHub*Exception` com mensagem sanitizada e **sem `cause`** nos logs; nada de log de request/URL.
- **Logging:** cada `BotCommand` tem `private static final Logger LOGGER`; níveis: `info` execução/conclusão, `warn` mensagem vazia/duplicado/allowlist, `error` falha inesperada (com exceção), `debug` diagnóstico. Só `updateId`, nome do comando, `sequenceNumber`, `status`; ingles, parametrizado.

## 8. Estratégia de testes / TDD

- **Convenção:** classe `<Subject>Test` (unitário, surefire) ou `<Subject>IT` (integração, failsafe); métodos `shouldXxxWhenYyy` em inglês; Given/When/Then em comentários só se ajudar; AssertJ; pastas espelham `src/main`. Slices: `@WebMvcTest` (controller), `@DataJpaTest`/`@SpringBootTest` + Testcontainers (repo/migration), WireMock (gateway GitHub e Telegram).
- **Regra de trabalho:** um teste por vez; mostro o teste **vermelho** (e o motivo da falha) antes de escrever o código mínimo; refatoro no verde. Critério de pronto de toda etapa: testes novos verdes, anteriores intactos, nenhum código de produção sem teste que o motive, `./mvnw verify` verde e pipeline verde.
- Teste transversal "texto digitado nunca aparece nos logs" (appender em memória do Logback / `OutputCaptureExtension`) no `CommitCommandTest` e num `WebhookFlowIT` ponta a ponta (inclui caminho de erro do GitHub/Telegram).

## 9. Pipeline CI/CD

### 9.1 Arquivos
`.github/workflows/pipeline.yml` (um workflow, jobs com `needs` → "CD só se todo CI passar"), `.github/dependabot.yml` (maven, github-actions, docker; semanal), `.github/dependency-check-suppressions.xml`, `Dockerfile`, `docker-compose.yaml` (produção), `deploy/deploy.sh` (roda na VPS), `deploy/setup-webhook.sh` (Etapa 5), `docs/RUNBOOK.md`.

### 9.2 Gatilho e conflito com os commits do bot
```yaml
on:
  push:
    branches: [main]
    paths-ignore: ['logs/**', 'ADR/**', '**/*.md', 'LICENSE']
  pull_request:          # só CI, ver pergunta Q4
    branches: [main]
  workflow_dispatch:
concurrency:
  group: pipeline-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}   # nunca cancelar um deploy em andamento
permissions: { contents: read }
```
- `paths-ignore` só pula quando **todos** os arquivos do push casam; commit misto roda normal. O bot só mexe em `logs/**` → nenhum pipeline por `/commit`.
- **Sem loop:** nenhum job faz push na `main`. Tag e Release criadas pelo `GITHUB_TOKEN` não disparam workflows; o gatilho é só `branches: [main]` (não escuta tags). Dependabot semanal e um `schedule` opcional só para Dependency-Check (CVEs novos sem commit), sem CD.
- `ADR/**`/`*.md` ignorados para não gerar release por edição de documentação (decisão sua, Q5).

### 9.3 Jobs e dependências
```
actionlint ──┐
version ─────┤
build-test ──┼──► publish ──► release ──► deploy
dep-check ───┘
```
1. **actionlint** (paralelo): lint do workflow + `shellcheck` do `deploy.sh`.
2. **build-test** (paralelo): JDK 21 (temurin, `cache: maven`), `./mvnw -B verify` (unit + IT com Testcontainers — o runner `ubuntu-latest` tem Docker) + JaCoCo check; sobe relatório JaCoCo como artefato.
3. **dep-check** (paralelo): `./mvnw -B dependency-check:check`, `NVD_API_KEY` via env, cache de `~/.m2/dependency-check-data` (restore-keys + save com chave de data), falha com CVSS ≥ 7.0, publica HTML/JSON como artefato (`if: always()`). Não roda em fork/Dependabot sem secret.
4. **version** (paralelo, `contents: read`, `fetch-depth: 0`): lê `revision` do pom → X.Y; Z = (maior tag `vX.Y.*`) + 1 → `X.Y.Z`. Só calcula, não escreve.
5. **publish** (`needs` todos acima; só em `push`; secrets Docker Hub): `buildx` com cache `type=gha`, **linux/amd64** (confirmar `uname -m` da VPS, Q7), constrói **uma vez** a imagem (export para tar), `docker load`, **smoke test** (sobe com Postgres e espera `/actuator/health` = UP) e então **publica exatamente essa imagem** (`docker push` de `X.Y.Z` e `sha-<7>`). Labels OCI: `org.opencontainers.image.version/revision/source/created`. Sem rebuild entre teste e publicação.
6. **release** (`needs: publish`; único com `contents: write`): `gh release create vX.Y.Z --target $GITHUB_SHA` (cria a tag sem commit), notas = `git log` do intervalo **excluindo `logs/`** e commits `Commit N` (as notas automáticas do GitHub só listam PRs, e push direto ficaria vazio), anexa o relatório do Dependency-Check.
7. **deploy** (`needs: release`; sem environment, usa secrets de repositório): ver 9.6.

### 9.4 Versionamento (SemVer) — comparação
| Opção | Prós | Contras |
|---|---|---|
| Conventional Commits + semantic-release/release-please | bump automático | release-please comita/abre PR na `main` (proibido); exige que TODOS os meus commits sigam o padrão; commits do bot não seguem |
| `pom` + `github.run_number` | trivial, sem estado | run_number conta PRs/execuções falhas → saltos |
| **`X.Y` no pom + `Z` = próxima tag (recomendado)** | SemVer legível; controle manual de major/minor (edito o pom); sem commit na `main`; independe do formato das mensagens e dos commits do bot | patch derivado de tags (a concorrência é serializada por `concurrency`) |

Imagem: tags imutáveis `X.Y.Z` e `sha-<curto>`. **Não publicar `latest`**: o deploy e o rollback usam tag explícita; `latest` móvel só criaria ambiguidade num repositório privado de um único consumidor (Q6). Mesma versão: tag Git `vX.Y.Z` = Release = tag da imagem = label OCI = `info.build.version`.
Se o deploy falhar e fizer rollback, a tag/imagem já existe: o step de falha marca a Release como `prerelease` com nota "deploy falhou, rollback executado" (a versão fica "consumida", nunca reutilizada → imutabilidade preservada).

### 9.5 Segredos e variáveis (onde cadastrar)
GitHub → *Settings → Secrets and variables → Actions*:
- Repository secrets: `NVD_API_KEY`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` (access token **read/write** só do repo `luizzfernando/commit-mirror`).
- Secrets de repositório do deploy (sem environments): `VPS_HOST`, `VPS_USER` (`root`), `VPS_SSH_KEY` (chave privada dedicada ao deploy), `VPS_KNOWN_HOSTS` (linha `IP ssh-ed25519 AAAA...` obtida na VPS em `/etc/ssh/ssh_host_ed25519_key.pub`; sem `StrictHostKeyChecking=no`), `VPS_PORT` (opcional).
- Nada do bot (token do Telegram, PAT do GitHub, secret do webhook, DB) no GitHub: ficam só no `.env` da VPS (modo 600).
- Settings do repo: *Actions → General*: `GITHUB_TOKEN` default read-only; aprovar workflows de colaboradores externos; exigir actions fixadas por SHA; sem `pull_request_target`.
- Actions de terceiros fixadas por **SHA completo** (resolver via `gh api` na implementação) + comentário da versão; Dependabot as atualiza. Preferir `docker/*` e `actions/*` oficiais; SSH com `ssh` nativo (sem action de terceiros).
- Dependabot não recebe secrets do Actions por padrão: o job `dep-check` é pulado nele (ou cadastrar `NVD_API_KEY` como Dependabot secret).

### 9.6 Deploy automatizado (VPS)
- Job: `ssh -i <chave temporária 0600> -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=<VPS_KNOWN_HOSTS> user@host X.Y.Z`. No servidor, `authorized_keys` com **forced command** (`command="/projects/commit-mirror/deploy.sh",restrict`): a chave só consegue executar o `deploy.sh`, com a tag como único argumento (validada por regex `^[0-9]+\.[0-9]+\.[0-9]+$`).
- `deploy.sh`: grava a tag atual como `previous`; troca `IMAGE_TAG` no `.env`; `docker compose pull app && docker compose up -d app`; espera até N s o healthcheck do container ficar `healthy`; se falhar → volta `IMAGE_TAG` para `previous`, `up -d`, sai com código ≠ 0 (pipeline falha). Primeira instalação (sem `previous`): apenas falha.
- Mesmo script serve ao **rollback manual de emergência**: `./deploy.sh 0.1.4` (migrations aditivas garantem compatibilidade com a imagem anterior).
- Docker Hub privado: na VPS, `docker login` uma única vez com access token **somente leitura**, distinto do token de escrita do pipeline.
- **Implementado:** tudo como `root` (decisão do dono). O `authorized_keys` de root tem a chave do pipeline com `command="/projects/commit-mirror/deploy.sh",restrict`; `PermitRootLogin prohibit-password` (ou `forced-commands-only`) e `fail2ban`. A porta SSH precisa ficar acessível aos IPs variáveis dos runners. **Risco aceito:** a chave pertence a root, mitigado pelo forced command e pelo `restrict`; manter `deploy.sh` com dono root e `chmod 755`.
- Aprovação manual vs automático → ver Q2 (recomendo automático).

## 10. Containerização e VPS

**Dockerfile (multi-stage):** (1) `maven:3-eclipse-temurin-21` — `mvn -B -DskipTests package` com cache de dependências (camada separada do `pom.xml`); (2) `java -Djarmode=tools -jar app.jar extract --layers --destination extracted` (layered jar); (3) runtime `eclipse-temurin:21-jre-alpine` pinada, usuário não-root, COPY das camadas (dependencies → spring-boot-loader → snapshot-dependencies → application), `HEALTHCHECK` via `wget` em `http://localhost:8081/actuator/health`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`. Imagem de build e da publicação são a mesma construída uma vez (9.3).
**Observação:** o Maven do estágio 1 compila o mesmo commit testado; se quiser garantia estrita do *artefato*, a alternativa é o Dockerfile só copiar o jar do `build-test` (perde o build reproduzível localmente). Mantive o multi-stage conforme pedido.
**docker-compose.yaml (prod):** `app` (`image: luizzfernando/commit-mirror:${IMAGE_TAG}`, `env_file: .env`, porta `127.0.0.1:8080:8080`, `restart: unless-stopped`, healthcheck) e `db` (`postgres:17-alpine`, volume nomeado, `pg_isready`, sem porta publicada); `depends_on: db: condition: service_healthy`.
**Host:** Nginx (host) com um `server` para o subdomínio, TLS por Certbot/Let's Encrypt (Telegram exige certificado válido e porta 443/80/88/8443), `location = /telegram/webhook` → `127.0.0.1:8080`, qualquer outro caminho → 404, `limit_req`, `client_max_body_size` pequeno; actuator (8081) nunca exposto. UFW: 80/443/SSH.
**Webhook (Etapa 5, uma vez, fora do deploy):** `setWebhook` com `url`, `secret_token`, `allowed_updates=["message"]`; verificar com `getWebhookInfo`. O script lê segredos do ambiente (sem eco no histórico).
**Atualização:** automática pelo pipeline. **Rollback manual:** na VPS, `/projects/commit-mirror/deploy.sh <tag anterior>` (lista de tags: Releases do GitHub / Docker Hub).

## 11. Etapas (TDD; testes em ordem de escrita, do mais simples ao mais complexo)

### Etapa 0 — Fundação e pipeline (walking skeleton) — sem regra de negócio
Itens: corrigir pom (seção 2), `.gitignore`, `.env.example`, `application.yaml` mínimo (datasource por env, actuator só health na 8081), trocar `postgres:latest`, Dockerfile, compose, `deploy.sh`, workflow, Dependabot, suppressions, VPS (usuário `deploy`, Docker, Nginx+TLS, `docker login` read-only, `authorized_keys` com forced command, `.env`, `fail2ban`), cadastrar secrets, primeiro push.
Testes (primeiros, antes de qualquer código):
1. `contextLoads` (existente, `CommitMirrorApplicationTests`) — contexto sobe com Postgres Testcontainers.
2. `shouldExposeHealthEndpointAsUp` (`HealthEndpointIT`) — `/actuator/health` = UP na porta de management.
Validações (substituem teste unitário): `actionlint`, `shellcheck deploy/deploy.sh`, `docker build` local, `docker compose config`, `mvnw verify` verde com JDK 21, pipeline verde.
**Pronto:** `mvnw verify` e `dependency-check` verdes na `main`; imagem `luizzfernando/commit-mirror:0.1.1` e `sha-xxxxxxx` no Docker Hub com labels OCI; Release `v0.1.1`; deploy concluído com healthcheck `healthy`; um push só em `logs/` **não** dispara o pipeline; teste de falha proposital (tag inexistente) comprova o rollback.

### Etapa 1 — Webhook, validações e Command Pattern (eco)
Testes, em ordem:
- `WebhookSecretValidatorTest`: `shouldAcceptWhenSecretMatches`, `shouldRejectWhenSecretDiffers`, `shouldRejectWhenHeaderIsMissing`, `shouldRejectWhenSecretHasDifferentLength`.
- `UserAllowlistTest`: `shouldAllowConfiguredUser`, `shouldRejectOtherUser`.
- `CommandParserTest`: `shouldParseCommandNameAndArguments`, `shouldTrimArguments`, `shouldReturnEmptyArgumentsWhenOnlyCommandIsSent`, `shouldStripBotSuffixFromCommandName`, `shouldReturnEmptyWhenTextIsNotACommand`.
- `CommandRegistryTest`: `shouldResolveCommandByName`, `shouldReturnEmptyForUnknownCommand`, `shouldFailWhenTwoCommandsShareTheSameName`, `shouldResolveNewCommandWithoutChangingTheRegistry`.
- `CommitCommandTest` (eco): `shouldReplyUsageWhenMessageIsEmpty`, `shouldReplyUsageWhenMessageIsBlank`, `shouldLogWarnWhenMessageIsEmpty`, `shouldEchoMessageWhenProvided`, `shouldLogInfoOnExecution`, `shouldNeverLogTypedText`.
- `UpdateDispatcherTest`: `shouldIgnoreUpdateWithoutMessage`, `shouldIgnoreMessageWithoutText`, `shouldIgnoreUserOutsideAllowlistSilently`, `shouldReplyWhenCommandIsUnknown`, `shouldExecuteResolvedCommandAndSendReply`.
- `WebhookControllerTest` (`@WebMvcTest`): `shouldReturn403WhenSecretIsInvalid`, `shouldReturn403WhenSecretHeaderIsMissing`, `shouldNotCallServiceWhenSecretIsInvalid`, `shouldReturnEmpty200WhenUserIsNotAllowed`, `shouldReturnEmpty200WhenMessageHasNoText`, `shouldReturn200AndDispatchOnHappyPath`, `shouldReturn400WhenBodyIsMalformed`.
- `RestClientTelegramClientTest` (WireMock **primeiro** define o contrato): `shouldPostSendMessageWithChatIdAndText`, `shouldThrowTelegramApiExceptionOnServerError`, `shouldNotLeakBotTokenInExceptionMessage`.
- `AppPropertiesValidationTest`: `shouldFailStartupWhenWebhookSecretIsBlank`.
- `WebhookFlowIT`: `shouldNeverLogTypedTextAcrossTheWholeFlow`.
Implementação mínima: só o necessário para cada teste. **Pronto:** `/commit` com texto ecoa; sem texto devolve uso; demais cenários ignorados/403; tudo verde no pipeline e deploy ok.

### Etapa 2 — Persistência e contador global
Testes, em ordem:
- `CommitMessageFormatterTest`: `shouldFormatMessageAsCommitN`, `shouldNotIncludeDateOrTimeInMessage`, `shouldNotIncludeTypedText`.
- `CommitLogTest` (domínio): `shouldStartAsPending`, `shouldMarkSuccessWithCommitSha`, `shouldMarkFailed`, `shouldRejectSuccessAfterSuccessTransitionMisuse`, `shouldAllowReprocessingFailedRecordKeepingSequence`.
- `CommitDateResolverTest`: `shouldResolveLocalDateInConfiguredZone`, `shouldKeepLocalDateNearMidnightUtcBoundary`.
- `CommitLogRepositoryIT` (Testcontainers): `shouldApplyFlywayMigration`, `shouldPersistAndLoadCommitLog`, `shouldRejectDuplicateTelegramUpdateId`, `shouldRejectDuplicateDateAndSequence`, `shouldReturnZeroWhenNoCommitsOnDate`, `shouldReturnMaxSequenceForDate`, `shouldFindFailedAndStalePendingRecords`.
- `CommitLogServiceTest` (mocks): `shouldAssignSequenceOneOnFirstCommit`, `shouldIncrementSequenceForEachCommit`, `shouldKeepIncrementingAcrossDaysAndMonths`, `shouldReturnDuplicateWhenUpdateIdWasAlreadyProcessed`, `shouldRetryWhenSequenceCollides`, `shouldGiveUpAfterMaxCollisionRetries`, `shouldKeepSameSequenceWhenReprocessingFailedRecord`.
- `CommitLogServiceIT`: `shouldGenerateDistinctSequencesUnderConcurrentCommits`.
- `CommitCommandTest` (evolui): `shouldSaveRecordAsPendingBeforeReplying`, `shouldReplyWithRegisteredSequenceNumber`, `shouldIgnoreDuplicateUpdateWithWarnLog`, `shouldNeverLogTypedText` (permanece).
**Pronto:** `/commit x` grava `PENDING` com sequência correta; reenvio do mesmo `update_id` não duplica; contador global contínuo (nunca reinicia); migration aplicada no deploy.

### Etapa 3 — Integração com o GitHub
Ordem: contrato WireMock primeiro, depois o adapter.
- `LogLineFormatterTest`: `shouldFormatLineAsCommitN`, `shouldNotIncludeDate`, `shouldNeverContainTypedText`.
- `LogFilePathResolverTest`: `shouldResolveMonthlyFilePath`, `shouldUseConfiguredDirectory`, `shouldUseCommitDateMonthAtMonthBoundary`.
- `RestClientGitHubContentsGatewayTest` (WireMock): `shouldCreateFileWhenMonthFileDoesNotExist`, `shouldAppendLineKeepingPreviousContent`, `shouldDecodeBase64ContentWithLineBreaks`, `shouldAddTrailingNewlineWhenPreviousContentLacksIt`, `shouldSendCurrentShaWhenUpdatingFile`, `shouldSendAuthorAndCommitterWithExplicitDates`, `shouldSendConfiguredBranchAndCommitMessage`, `shouldSendBearerToken`, `shouldReturnCommitShaFromResponse`, `shouldNotAppendWhenLineAlreadyPresent`, `shouldMapConflictToGitHubConflictException`, `shouldMapServerErrorToTransientException`, `shouldMapUnauthorizedToPermanentException`, `shouldNotLeakTokenInExceptionMessage`.
- `CommitProcessingServiceTest`: `shouldMarkSuccessWithShaWhenGatewaySucceeds`, `shouldMarkFailedWhenGatewayFails`, `shouldReplyConfirmationOnSuccess`, `shouldReplyFailureReasonWithoutSecretsOrTypedText`, `shouldReuseSequenceWhenReprocessingFailedRecord`, `shouldLogErrorWithExceptionOnUnexpectedFailure`.
- `CommitCommandTest` (evolui): `shouldProcessCommitAfterPersisting`.
- `CommitFlowIT` (WireMock stateful, Postgres real): `shouldCreateMonthFileOnFirstCommit`, `shouldAppendOnSecondCommit`, `shouldNeverSendTypedTextToGitHub`.
**Pronto:** `/commit` real cria `logs/AAAA-MM.md` (síncrono ainda) com a linha esperada, commit com autor/data corretos; SHA salvo.

### Etapa 4 — Resiliência (em quatro mini-passos)
- 4a Retry: `shouldRetryOnConflictRereadingSha` (WireMock scenario 409→GET→200), `shouldRetryOnServerErrorWithBackoff`, `shouldNotRetryOnUnauthorized`, `shouldStopAfterMaxAttemptsAndMarkFailed`.
- 4b Circuit breaker: `shouldOpenAfterFailureRateThreshold`, `shouldFailFastWhenOpen`, `shouldNotCountConflictsAsFailures`, `shouldCloseAfterSuccessfulHalfOpenCalls`.
- 4c Serialização: `LogWriteSerializerTest`: `shouldRunSubmissionsOneAtATime`, `shouldPreserveSubmissionOrder`; `ConcurrentCommitsIT`: `shouldWriteTenConcurrentCommitsWithoutLossOrDuplication`.
- 4d Assíncrono + recuperação: `shouldReturn200BeforeGitHubCallCompletes`, `shouldReplyAfterProcessingCompletes`, `PendingRecoveryServiceTest`: `shouldReprocessStalePendingOnStartup`, `shouldNotReprocessFreshPending`, `shouldReprocessFailedRecordsUpToMaxAttempts`, `shouldNotifyUserOnlyOnFinalFailure`, `shouldKeepSameSequenceWhenReprocessing`, `shouldBeIdempotentWhenLineAlreadyInFile`.
**Pronto:** 10 `/commit` simultâneos geram 10 linhas, sequências 1..10 sem buracos; GitHub fora do ar → `FAILED` + aviso + reprocesso mantém o número.

### Etapa 5 — Webhook em produção e operação
Sem regra nova de negócio (sem testes unitários novos); validação: `setup-webhook.sh` aplicado, `getWebhookInfo` sem erros, **teste ponta a ponta real** (`/commit teste` → linha em `logs/`, commit no gráfico, resposta no Telegram; usuário não permitido ignorado; secret errado → 403 via `curl`). Documentar em `docs/RUNBOOK.md`: atualização, rollback manual, rotação (token do bot via BotFather + `setWebhook`; secret do webhook; PAT; tokens Docker Hub; chave SSH; `NVD_API_KEY`), backup do volume Postgres.

**Ordem:** concordo com a sequência dada. Único ajuste: a assincronia entra na Etapa 4 (não antes), porque só faz sentido depois que há GitHub; a Etapa 3 já nasce atrás de uma interface que facilita essa troca. E o ADR é atualizado ao fim de cada etapa.

## 12. Riscos, dúvidas e decisões

**Decididas até agora:** contador global e linha `Commit N`; VPS como root em `/projects/commit-mirror` e sem environments (Q2/Q3 resolvidas assim: deploy automático, sem aprovação manual); scripts de `deploy/` mantidos. **Ainda em aberto (a confirmar):** Q4 a Q7, Q9 a Q12 e o nome do diretório de logs (existe um `commit-logs/` local; o plano e o `paths-ignore` usam `logs/`).

Lista original:

- **Q1 – Gatilho × commits do bot:** adotar `paths-ignore: ['logs/**', ...]` + sem escrita na `main` pelo pipeline? (recomendo sim; riscos: um commit seu que mexa só em `logs/` não roda pipeline — aceitável).
- **Q2 – Deploy:** totalmente automático ou com aprovação no environment `production`? Automático: rápido, rollback automático cobre falha, projeto pessoal de baixo impacto; contra: um bug que passe nos testes vai ao ar sem olhos humanos. Aprovação: mais seguro, porém trava a entrega e exige que você clique a cada push. **Recomendo automático**, com o environment pronto para ligar *required reviewers* depois.
- **Q3 – Segurança do SSH na VPS:** chave dedicada + forced command + `restrict`, usuário sem sudo, porta acessível a IPs de runners (não restringível), fail2ban. Usuário no grupo `docker` ≈ root → aceitar com essas mitigações ou usar Docker rootless? (recomendo forced command; rootless se a VPS permitir).
- **Q4 – Pull requests:** rodar CI (build-test, actionlint, dep-check sem secrets de fork) em PRs? Recomendo sim, só CI; publish/release/deploy exigem `push`; `pull_request_target` nunca; forks não recebem secrets.
- **Q5 – `*.md`/`ADR/**` no `paths-ignore`:** editar documentação não deve gerar release? (recomendo ignorar).
- **Q6 – Tag `latest`:** não publicar (recomendo) ou publicar também?
- **Q7 – Arquitetura da VPS:** confirmar `linux/amd64` (`uname -m`). Nginx já existe na VPS ou instalo/documento a instalação? Qual é o subdomínio e o provedor?
- **Q8 – Namespace Docker Hub:** usei exatamente `luizzfernando/commit-mirror`, mas seu usuário do GitHub é `luuizfernando` (u duplo vs z duplo). Está correto mesmo? Confirmar antes de criar os secrets, para não publicar no lugar errado.
- **Q9 – Branch protection na `main`:** exigir PR ou checks obrigatórios **bloquearia** as escritas do bot via PAT e os seus pushes diretos. Recomendo **não** proteger a `main` (ou um ruleset em que o seu usuário esteja na lista de bypass). Quer proteger?
- **Q10 – Colunas extras `attempt_count`/`updated_at`** no `commit_log` (desvio do modelo pedido) para limitar o reprocessamento automático. Aprova? Alternativa: reprocessar só PENDING velho e FAILED manualmente (exigiria um comando/endpoint fora do escopo).
- **Q11 – Cabeçalho do arquivo mensal:** o arquivo terá apenas linhas `Commit N` (sem data nem título). Ok?
- **Q12 – Resilience4j:** módulos core programáticos (recomendado) ou tentar `resilience4j-spring-boot3`/`@Retryable` do Spring 7?
- **Riscos técnicos:** (a) Boot 4/Jackson 3/test-slices novos — validados na Etapa 0; (b) PAT expira → FAILED permanentes (aviso no Telegram + calendário de rotação); (c) vazamento do token do bot em mensagens de exceção do `RestClient` (mitigado e testado); (d) primeiro download do NVD é lento mesmo com chave (cache resolve); (e) SHA das actions precisa ser resolvido na hora de implementar; (f) JDK 21 local para rodar o Maven; (g) contagem do gráfico exige e-mail do autor vinculado à conta e commit na branch padrão.

## 13. ADR-01

Atualizar `ADR/ADR-01.md` (PT-BR, mantendo o estilo): §2.2 stack (Spring Boot 4.1, Resilience4j core, WireMock/JaCoCo/Dependency-Check), nova seção de arquitetura em camadas e Command Pattern, §2.10 com as decisões de concorrência/assincronia/recuperação, nova seção de CI/CD + versionamento + deploy/rollback + segurança do pipeline, TDD como metodologia, e §3 reordenada (Etapa 0 = pipeline). Registrar as respostas às Q1–Q12. Atualização incremental a cada etapa, consolidada ao fim. **Nada será commitado por mim** (item 14).

## Verificação (ponta a ponta)

1. Local: JDK 21 + `./mvnw verify` (unit + IT + JaCoCo check); `./mvnw dependency-check:check`; `actionlint`; `docker build` + `docker compose config`.
2. Pipeline: todos os jobs verdes na `main`; imagem e tags no Docker Hub; Release `vX.Y.Z`; deploy com healthcheck; push só em `logs/` não dispara nada; falha forçada de deploy faz rollback.
3. Produção (Etapa 5): `getWebhookInfo` limpo; `/commit` real cria a linha, o commit aparece no gráfico, resposta no Telegram; usuário fora da allowlist ignorado; secret errado → 403; GitHub indisponível (PAT revogado de teste) → `FAILED` + aviso + reprocesso mantém o número.
