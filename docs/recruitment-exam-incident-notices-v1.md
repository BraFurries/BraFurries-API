# Recrutamento — comunicação de bloqueio e reabertura de provas (V1)

## Objetivo e canais
- Avisos **somente dentro do painel web**, sem e-mail, Discord, Telegram, push ou notificações de sistema operacional. Consultas explícitas ao abrir/atualizar candidatura ou mesa administrativa; sem polling.
- Eventos da prova `BLOCKED` e `REINSTATED` são gravados na mesma transação que a alteração do estado. Retentativas que não mudam o estado não inserem avisos. Um novo bloqueio **após** uma reabertura legítima gera um evento separado.
- Avisos são informativos: perda de foco não prova fraude; bloqueio não é resultado final, publicação de notas ou recurso formal.

## Fonte de autoridade
- Database PR #25: Flyway V20261023_009 cria `recruitment_exam_incident_notices` e `recruitment_exam_notice_reads`, com FKs compostas por Community. Nunca executar DDL em API/Coddy.
- API: `RecruitmentExamNoticeRepository` grava `occurred_at` em UTC e `RecruitmentExamNoticeService` valida participação ativa e elegibilidade de leitura por ator; os produtores são `recordIntegrityEvent` (apenas transição real) e `reinstate` (após reabertura).
- Front-End: `RecruitmentExamNoticesService` mostra avisos nos painéis de candidato e Staff, com ACK manual individual; datas e texto são interpretados pela UI, não armazenados como mensagem livre.
- Coddy: nenhuma mudança.

## Rotas protegidas
Candidato, sempre candidatura própria:
- `GET /user/me/communities/{communityId}/recruitment/applications/{applicationId}/exam-notices`
- `POST .../exam-notices/{noticeId}/read`

Staff, capability `RECRUITMENT_MANAGE_PROCESSES`, participação ativa e exclusivamente candidaturas **de outras pessoas** do processo escolhido:
- `GET /user/me/communities/{communityId}/recruitment/processes/{processId}/exam-notices`
- `POST .../exam-notices/{noticeId}/read`

GET retorna no máximo os 50 avisos mais recentes, em ordem decrescente. `{id,applicationId,stageId,attemptId,eventType,occurredAt,newExpiresAt,read}`. ACK idempotente devolve 204. A confirmação de uma pessoa não altera o `read` de outra; nenhum endpoint aceita `userId` no payload. Sem cache compartilhado: `Cache-Control: no-store`.

## Segurança/privacidade
- Revalidação de autorização **em cada GET e ACK**, inclusive após revogação de permissão. Nunca reutilizar autorização do Front.
- Toda leitura junta Community e candidatura/processo; nenhum aviso expõe respostas, gabaritos, nota, decisão final ou justificativa interna do desbloqueio.
- A Staff só acessa a justificativa pela rota de análise anterior, sujeita às verificações específicas. Aviso ao candidato é neutro e não gera reprovação/publicação.
- Histórico e recibos sobrevivem a refresh e mudanças de status; versão/expiração originais continuam no fluxo anterior.
- Interface limpa resultados ao trocar de Community e descarta callbacks obsoletos.

## Testes e rollout
Database: Flyway/MariaDB descartável e FKs cross-tenant; API: unit e integração JDBC com leitura por usuário, duplicidade legítima de bloqueio e auditoria transacional; Front: specs e build.
Dependências: PRs Database #25 sobre #24; API sobre #45; Front sobre #27. Todos ainda em draft. Gate do recrutamento OFF; **nenhum merge, deploy ou alteração em produção**.
