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

## Ambientes

Cada ambiente corresponde a uma branch. As mudanças são promovidas por PR na ordem `dev` → `homolog` → `main`.

| Ambiente | Branch | Pipeline | Versão / imagem | Onde roda |
| --- | --- | --- | --- | --- |
| Desenvolvimento | `dev` | lint + testes, sem deploy | — | local |
| Homologação | `homolog` | lint + testes + imagem + deploy | `X.Y.Z-rc.N` (sem tag/release) | VPS, `/projects/commit-mirror-homolog` |
| Produção | `main` | lint + testes + imagem + release + deploy | `X.Y.Z` (tag `vX.Y.Z` e release) | VPS, `/projects/commit-mirror` |

A versão de homolog é um release candidate da próxima versão de produção (`0.1.3-rc.42`, onde `42` é o número da execução do pipeline). O Dependabot abre os PRs contra `dev`.

### Isolamento entre homolog e produção

Os dois stacks rodam na mesma VPS e não compartilham nada além da máquina:

| Recurso | Produção | Homologação |
| --- | --- | --- |
| Diretório | `/projects/commit-mirror` | `/projects/commit-mirror-homolog` |
| `COMPOSE_PROJECT_NAME` | `commit-mirror` | `commit-mirror-homolog` (containers e volume do Postgres separados) |
| `APP_HOST_PORT` | `8080` | `8090` |
| Domínio | `bot.luuizfernando.tech` | `bot-homolog.luuizfernando.tech` |
| Bot do Telegram | bot de produção | outro bot (o Telegram aceita um webhook por bot) |
| Repositório de log | este repositório | um repositório separado, para que commits de teste não entrem no log de produção |
| Chave SSH de deploy | `VPS_SSH_KEY` do repositório | `VPS_SSH_KEY` do environment `homolog` |

### Configurar homologação (uma vez)

1. **DNS e nginx**: crie o registro A de `bot-homolog.luuizfernando.tech`, instale `nginx/commit-mirror-homolog.conf` em `/etc/nginx/conf.d/` (arquivo separado, para não sobrescrever o TLS que o certbot gravou no de produção) e rode `certbot --nginx -d bot-homolog.luuizfernando.tech`.
2. **Stack na VPS**: crie `/projects/commit-mirror-homolog` com `docker-compose.yaml`, `deploy.sh` (executável) e um `.env` próprio (`APP_HOST_PORT=8090`, `COMPOSE_PROJECT_NAME=commit-mirror-homolog`, banco, bot e repositório de log de homolog). O `deploy.sh` usa o próprio diretório como `APP_DIR`.
3. **Chave SSH**: gere um par de chaves só para homolog e adicione a pública no `authorized_keys` do usuário de deploy, com o comando forçado do stack de homolog:
   ```
   command="/projects/commit-mirror-homolog/deploy.sh",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA... deploy-homolog
   ```
   Não reaproveite a chave de produção: o comando forçado dela faria o deploy do release candidate em produção.
4. **GitHub**: em *Settings → Environments*, crie `homolog` com o secret `VPS_SSH_KEY` (a chave privada do passo 3). Os demais (`VPS_HOST`, `VPS_USER`, `VPS_PORT`, `VPS_KNOWN_HOSTS`, Docker Hub) continuam no nível do repositório. Crie também `production` (pode ficar sem secrets, eles vêm do repositório) e, se quiser, adicione regras de proteção, como aprovação manual e restrição à branch `main`.
5. **Webhook**: `TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... deploy/setup-webhook.sh set https://bot-homolog.luuizfernando.tech/telegram/webhook`, com o token e o secret do bot de homolog.

### Desenvolvimento local

`dev` não tem deploy. Para rodar a aplicação localmente com Postgres via Testcontainers, execute `TestCommitMirrorApplication` (em `src/test`). Os fakes `FakeGitHubServer` e `FakeTelegramServer` permitem testar o fluxo sem tocar nas APIs reais.

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

## Implementações futuras

### API genérica e multiusuário

Hoje o serviço atende um único dono, definido por variáveis de ambiente. A meta é torná-lo genérico o suficiente para que outros devs usem a mesma API, bastando um pequeno login via bot do Telegram:

- **Onboarding pelo bot**: um comando como `/start` ou `/login` identifica o usuário pelo ID do Telegram e inicia o cadastro, substituindo o `TELEGRAM_ALLOWED_USER_ID` fixo.
- **Repositório e credenciais por usuário**: cada dev informa o repositório de destino e autoriza o acesso ao GitHub (OAuth ou GitHub App, em vez de um PAT global), com tokens armazenados criptografados.
- **Autoria por usuário**: nome e e-mail de autor configuráveis por pessoa, garantindo que os commits contem no gráfico de cada um.
- **Isolamento de dados**: contador `sequence_number` e logs por usuário, em vez de um contador global.
- **Serialização de escrita por repositório**: trocar o worker único global por uma fila por repositório.
- **Limites e proteção**: rate limit por usuário e validação do webhook mantida para todos.

## Licença

[MIT](LICENSE)
