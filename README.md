# Commit Mirror

Serviço Spring Boot que espelha commits de trabalho em um log público no GitHub, para que eles contem no gráfico de contribuições.

Muitos projetos de trabalho vivem em repositórios privados, e essas contribuições não aparecem no perfil público. O Commit Mirror resolve isso: você manda `/commit <mensagem>` para um bot do Telegram e o serviço registra o commit no banco e acrescenta uma linha em `commit-logs/AAAA-MM.md` neste repositório, usando a GitHub Contents API com autor, committer e datas explícitos.

> O texto digitado na mensagem **nunca** é gravado no repositório nem nos logs. A linha do log e a mensagem do commit são apenas `Commit N`, onde `N` é um contador global que nunca reinicia.

## Como funciona

```
Telegram (/commit ...) ──webhook──▶ API Spring Boot ──▶ PostgreSQL (sequence_number UNIQUE)
                                           │
                                           └──▶ GitHub Contents API ──▶ commit-logs/AAAA-MM.md
```

1. O bot do Telegram recebe `/commit <mensagem>` e dispara o webhook, validado por um secret.
2. Apenas o usuário autorizado (`TELEGRAM_ALLOWED_USER_ID`) pode usar o bot.
3. A API registra o commit no Postgres com um `sequence_number` único.
4. A linha `Commit N` é acrescentada ao arquivo do mês no GitHub, com o e-mail do autor vinculado à sua conta, o que faz o commit contar no gráfico.
5. O bot responde confirmando o registro.

### Resiliência

- As escritas no GitHub passam por um serializador (`LogWriteSerializer`, worker único), evitando conflitos de SHA.
- Resilience4j (retry, circuit breaker) configurado em `config/ResilienceConfig`.
- Commits não finalizados são reenfileirados pelo `PendingRecoveryService`.

## Stack

- Java 21, Spring Boot 4.1 (Spring Framework 7, Jackson 3)
- PostgreSQL com Flyway (`ddl-auto: validate`)
- `RestClient` para HTTP externo, sem biblioteca de Telegram e sem Lombok
- Resilience4j
- Testes: JUnit 6, Testcontainers, JaCoCo (≥ 80% em `service` e `domain`)
- Docker, GitHub Actions e deploy via SSH em uma VPS

## Configuração

Copie `.env.example` para `.env` e preencha. A aplicação se recusa a iniciar sem os secrets do Telegram e do GitHub.

| Grupo | Variáveis |
| --- | --- |
| Banco | `DB_URL`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` |
| Telegram | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, `TELEGRAM_ALLOWED_USER_ID` |
| GitHub | `APP_GITHUB_PAT` (fine-grained, só este repositório, Contents: read/write), `GITHUB_OWNER`, `GITHUB_REPO`, `GITHUB_BRANCH`, `LOGS_DIRECTORY` |
| Autor | `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL` (precisa estar vinculado à sua conta do GitHub) |
| Outros | `APP_TIME_ZONE`, `SERVER_PORT`, `MANAGEMENT_PORT` |
| Recuperação | `RECOVERY_ENABLED`, `RECOVERY_INTERVAL`, `RECOVERY_MIN_AGE`, `RECOVERY_MAX_ATTEMPTS` |

O Actuator roda em porta separada (`MANAGEMENT_PORT`, padrão 8081) e expõe apenas `health` e `info`.

## Desenvolvimento

Requer **JDK 21** no `JAVA_HOME` e Docker em execução (os testes de integração usam Postgres real via Testcontainers).

```bash
./mvnw verify                             # testes unitários + integração + checagem do JaCoCo
./mvnw test                               # somente unitários
./mvnw test -Dtest=ClasseTest#metodo      # um teste unitário
./mvnw verify -Dit.test=HealthEndpointIT  # um teste de integração
```

## CI/CD

O pipeline (`.github/workflows/pipeline.yml`) executa: lint → `./mvnw verify` → cálculo de versão → build da imagem Docker (uma vez) → smoke test → push para o Docker Hub → tag/release → deploy via SSH. A VPS não clona o repositório; ela guarda apenas `docker-compose.yaml`, `.env` e `deploy.sh`, e o pipeline só troca a tag da imagem.

## Estrutura

```
src/main/java/com/luiz/commit_mirror/
├── config/       # propriedades, clock, RestClient, Resilience4j
├── controller/   # webhook do Telegram
├── domain/       # entidade CommitLog e status
├── dto/          # records de Telegram e GitHub
├── integration/  # gateways HTTP (GitHub, Telegram)
├── repository/   # acesso ao banco
└── service/      # parser de comandos, escrita do log, recuperação
deploy/           # scripts de deploy, webhook e smoke test
ADR/              # decisões de arquitetura
commit-logs/      # logs mensais gerados pelo serviço
```

## Licença

[MIT](LICENSE)
