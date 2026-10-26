# ADR-01: Arquitetura do commit-mirror

- **Status:** Aceito
- **Data:** 2026-10-19
- **Autor:** Luiz

## 1. Contexto

Os commits do trabalho ficam em um repositório de código privado da empresa e, por isso, não aparecem no gráfico de contribuições pessoais do GitHub. O objetivo do projeto é registrar no GitHub uma atividade equivalente a cada commit real feito no trabalho, disparada por uma mensagem simples no Telegram.

## 2. Decisão

### 2.1 Visão geral

Um bot do Telegram recebe o comando `/commit <mensagem>` por webhook. A API Spring Boot valida a origem, registra o commit no Postgres e atualiza um arquivo de log mensal no próprio repositório `commit-mirror` por meio da API do GitHub.

```
Telegram (chat com o bot)
   -> Webhook HTTPS (proxy reverso)
   -> API Spring Boot (commit-mirror)
        -> Postgres (registro, contador global, idempotência)
        -> GitHub Contents API (commit em commit-logs/AAAA-MM.md)
   <- Resposta de confirmação ou erro no Telegram
```

### 2.2 Stack

| Item | Decisão |
|---|---|
| Linguagem / build | Java 21, Maven |
| Framework | Spring Boot 4.1 (Spring Framework 7, Jackson 3) |
| Banco | PostgreSQL, com migrations via Flyway |
| Resiliência | Resilience4j (módulos core `retry` e `circuitbreaker`, configurados por código; o starter Spring Boot 3 não é usado) |
| Testes | JUnit, Mockito, AssertJ, Testcontainers (Postgres real) e WireMock (GitHub/Telegram simulados). Sem H2 |
| Qualidade | JaCoCo com verificação no build e OWASP Dependency-Check (plugin Maven). Sem análise estática (SonarQube) na v1 |
| HTTP externo | `RestClient` do Spring, sem biblioteca específica do Telegram; DTOs como `record`; sem Lombok |
| Containerização | Docker (multi-stage, layered jar) e Docker Compose |
| CI/CD | GitHub Actions, Docker Hub privado, deploy por SSH |

### 2.3 Recebimento de mensagens: webhook

O Telegram chama a API por HTTPS (webhook), em vez de a API consultar o Telegram. A requisição é autenticada pelo header `X-Telegram-Bot-Api-Secret-Token`.

### 2.4 Padrão de comandos

Os comandos são implementados com o **Command Pattern**: cada um é um `BotCommand` resolvido pelo `CommandRegistry`. Na v1 existia apenas o `/commit`; depois foi adicionado o `/status` (seção 5).

### 2.5 Repositório e arquivo de log

- Os logs ficam em `commit-logs/AAAA-MM.md`, um arquivo por mês, com uma linha por commit.
- O commit é feito na branch `main`.

### 2.6 Formato do commit

- Mensagem do commit e linha do log: apenas `Commit N`, em que N é um **contador global** que nunca reinicia (Commit 1, Commit 2, ... Commit 400). Não há data na linha do log.
- O fuso (`America/Sao_Paulo`, configurável) define a `commit_date` (escolha do arquivo mensal) e as datas author/committer.
- As datas author/committer são definidas explicitamente na chamada ao GitHub, com o e-mail vinculado à conta, para contar no gráfico de contribuições.
- O texto digitado no `/commit` **não** vai para o repositório. Fica apenas no Postgres.
- `/commit` sem texto é rejeitado, com resposta de uso (`Uso: /commit <mensagem>`).

### 2.7 Modelo de dados

Tabela `commit_log`:

| Coluna | Descrição |
|---|---|
| `id` | Chave primária |
| `telegram_update_id` | Único. Garante idempotência |
| `chat_id` | Chat de origem, usado para responder também no reprocessamento |
| `message` | Texto digitado no comando |
| `commit_date` | Data local do commit (define o arquivo `commit-logs/AAAA-MM.md`) |
| `sequence_number` | Número global do commit, único |
| `created_at` | Timestamp de criação (vira a data author/committer no GitHub) |
| `updated_at` | Última transição de estado (espaça as tentativas de recuperação) |
| `status` | `PENDING`, `SUCCESS` ou `FAILED` |
| `github_commit_sha` | Preenchido em caso de sucesso (nulo se a linha já existia no arquivo) |
| `attempt_count` | Falhas acumuladas; limita o reprocessamento automático (`RECOVERY_MAX_ATTEMPTS`, padrão 3) |

### 2.8 Fluxo principal

1. O usuário envia `/commit mensagem` ao bot.
2. O Telegram faz POST no webhook da API.
3. A API valida o secret do header e o `user_id` do remetente.
4. O `CommandRegistry` resolve o `/commit`.
5. Se o `update_id` já foi processado, a requisição é ignorada.
6. A API gera o próximo número global e grava o registro como `PENDING`.
7. A API atualiza o arquivo do mês no GitHub.
8. O registro passa a `SUCCESS` ou `FAILED`.
9. A API responde no Telegram com a confirmação ou o motivo do erro.

### 2.9 Segurança

- **Secret do webhook:** requisição sem o secret válido é rejeitada com 403.
- **Allowlist:** apenas o `user_id` do dono é atendido. Mensagens de outros usuários são ignoradas com resposta 200 vazia.
- **PAT do GitHub:** fine-grained, restrito ao repositório `commit-mirror`, com permissão de escrita em conteúdo.

### 2.10 Resiliência

- **Idempotência** por `telegram_update_id`, para evitar commits duplicados em reentregas do Telegram.
- **Retry com backoff** e circuit breaker nas chamadas ao GitHub.
- **Serialização** da escrita no arquivo de log, já que a Contents API exige o `sha` atual e commits simultâneos geram conflito (409). Em caso de 409, há nova tentativa.
- **Colisão do contador:** a restrição única em `sequence_number` gera retry em nova transação.
- **Assincronia:** o webhook valida, grava `PENDING` e responde 200 imediatamente; o GitHub é processado em uma fila de um único worker (que também serializa a escrita no arquivo) e o resultado chega por mensagem no Telegram. Registros `PENDING` antigos e `FAILED` são reprocessados por um job de recuperação, mantendo o mesmo número.
- **Resposta perdida:** antes de acrescentar, verifica se a linha `Commit N` já existe no arquivo, evitando duplicata em retry.
- **Falha definitiva:** o registro permanece `FAILED` e mantém o número. O usuário recebe um aviso na primeira falha ("vou tentar novamente") e outro na falha definitiva, depois de `RECOVERY_MAX_ATTEMPTS` tentativas; as intermediárias são silenciosas. Depois de corrigida a causa, zerar `attempt_count` reabre o reprocessamento (ver `docs/RUNBOOK.md`).
- **Circuit breaker:** só falhas transitórias (5xx, 429, I/O) contam. 409 e recusas (401/403/404/422) não contam, pois não indicam indisponibilidade. Aberto, as chamadas falham rápido com "GitHub indisponível".
- **Concorrência:** a serialização cobre uma única instância. Com mais de uma, seria necessário um lock distribuído (por exemplo `pg_advisory_lock`); fora do escopo da v1. O 409 continua tratado como rede de segurança (por exemplo, um commit manual no mesmo arquivo).

### 2.11 Infra e deploy

- Docker Compose com API e Postgres (volume persistente, healthcheck pelo Actuator, `restart: unless-stopped`), usando a imagem versionada do Docker Hub (`luizzfernando/commit-mirror`, privado).
- Nginx no host com TLS (Let's Encrypt) em um subdomínio; só `/telegram/webhook` é exposto. O Actuator fica em porta separada (8081), não exposta.
- Atualização e rollback usam o mesmo script (`deploy/deploy.sh <versão>`) na VPS.

### 2.12 Arquitetura em camadas e Command Pattern

`controller` -> `service` -> (`repository`, `integration`) -> `domain`; `dto`, `config` e `exception` são transversais. O Command Pattern vive em `service`: interface `BotCommand`, `CommandRegistry` (injeta todas as implementações) e uma classe por comando (`CommitCommand` na v1). Integrações externas (Telegram, GitHub) ficam atrás de interfaces. Todo o código em inglês; respostas ao usuário no Telegram em português.

### 2.13 Metodologia: TDD

Red -> Green -> Refactor, um teste por vez; nenhuma regra de negócio sem teste prévio. Testes unitários `*Test` (surefire) e de integração `*IT` (failsafe); nomes `shouldXxxWhenYyy`. Cobertura mínima de 80% de linhas em `service` e `domain` (JaCoCo, o build falha abaixo disso).

### 2.14 CI/CD (GitHub Actions)

- Workflow único (`.github/workflows/pipeline.yml`): `actionlint`, `build-test` (`mvn verify`), `dependency-check` (plugin Maven, falha com CVSS >= 7.0) e `version` em paralelo; depois `publish` -> `release` -> `deploy`. O CD só roda se todo o CI passar.
- **Gatilho:** push na `main` com `paths-ignore` em `commit-logs/**` (e docs), porque os commits do bot usam um PAT e disparariam o pipeline. Nenhum job escreve na `main`. Pull requests rodam apenas o CI, sem secrets de fork; `pull_request_target` não é usado.
- **Versionamento SemVer:** `X.Y` vem do `revision` do `pom.xml`; `Z` é a próxima tag `vX.Y.*`. Sem commit do pipeline e sem depender de Conventional Commits. Imagem com tags imutáveis `X.Y.Z` e `sha-<commit>`, sem `latest`, e labels OCI. Tag Git, GitHub Release e tag da imagem usam a mesma versão.
- A imagem é construída uma vez, passa por smoke test e é essa mesma imagem que é publicada.
- **Deploy:** SSH com chave dedicada, forced command e host key fixada; healthcheck e rollback automático para a tag anterior. As migrations do Flyway são sempre aditivas, porque o rollback da aplicação não desfaz o banco.
- Segredos do pipeline: `NVD_API_KEY`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` e, no environment `production`, `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS`. Segredos do bot (token, PAT, secret do webhook, banco) ficam só no `.env` da VPS. Actions fixadas por SHA, atualizadas pelo Dependabot.

## 3. Ordem de implementação

0. Fundação e pipeline completo (walking skeleton): pom, Dockerfile, compose, CI/CD, release e deploy com healthcheck. Nenhuma regra de negócio antes disso.
1. Webhook, validações e Command Pattern (resposta de eco).
2. Persistência e contador global.
3. Integração com o GitHub.
4. Resiliência (retry, circuit breaker, serialização, assincronia e recuperação).
5. Registro do webhook no Telegram, teste ponta a ponta e documentação operacional.

## 4. Escopo da v1

Somente o comando `/commit`.

## 5. Evoluções

### 5.1 Comando `/status`

O `/status` responde quantos commits já foram salvos no gráfico desde o início do uso do serviço. O valor é um `COUNT` dos registros de `commit_log` com `status = SUCCESS`, ou seja, apenas os que chegaram ao GitHub. Registros `PENDING` e `FAILED` não entram na contagem.

- Resposta em frase única: `Nenhum commit salvo ainda.` (0), `Você já salvou 1 commit com o Commit Mirror.` (1) ou `Você já salvou N commits com o Commit Mirror.` (N).
- Argumentos após o comando são ignorados.
- É somente leitura: não grava no banco nem no GitHub.
- Passa pelas mesmas validações do webhook (secret e allowlist).