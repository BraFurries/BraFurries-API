# Recrutamento — política de revisão/decisão por etapa (recorte incremental)

## Autoridade e compatibilidade

A API grava JSON na coluna **existente** `recruitment_stages.evaluation_config_json`.
Não há novo DDL, DML de produção, migration Flyway nem participação do Coddy.
A versão publicada é imutável; somente o processo/versão `DRAFT` admite
alterar política. Todas as queries usam `community_id`, `process_id` e
identidade de etapa. O front não interpreta regras para autorização.

## Contrato administrativo

`GET /user/me/communities/{communityId}/recruitment/processes/{processId}/stages/{stageId}/decision-policy`

`PUT` no mesmo caminho, com `RECRUITMENT_MANAGE_PROCESSES` e gate do
recrutamento habilitado apenas para a Community permitida.

```json
{
  "expectedRevision": 4,
  "policy": {
    "reviewMode": "COLLECTIVE",
    "decisionMode": "MANUAL",
    "minimumAssessments": 3
  }
}
```

`reviewMode`: INDIVIDUAL (obrigatoriamente um parecer) ou COLLECTIVE
(2 a 20 pareceres mínimos). O mínimo limita **pareceres distintos** na mesma
candidatura e etapa, já garantidos pela unique key de avaliações.
`decisionMode`: MANUAL ou AUTOMATIC_VOTE; votação automática só é válida
com COLLECTIVE, mas **ainda não executa**. A pré-validação da publicação
bloqueia `AUTOMATIC_VOTE` até votação, quórum e desempate existirem.
`AUTO_EVALUATION` não aceita política de revisão humana editável.

JSON ausente na etapa antiga equivale a INDIVIDUAL + MANUAL + 1; evita
quebrar processos legados sem inventar votos. JSON persistido inválido
falha de forma explícita. Não há conversão de nota em aprovação.

PUT usa lock do processo e `expectedRevision` otimista; avanço da revisão
é atômico. Resposta fornece `draftRevision`, `versionStatus` e política.
Alterações auditadas como `RECRUITMENT_DECISION_POLICY_UPDATED`, sem conteúdo
das avaliações. GET funciona também em versões publicadas para gestores,
sem permissão para editá-las.

## Integração no motor de decisão

A rota existente de decisão manual consulta a política **congelada** da etapa.
Exige ao menos `minimumAssessments` pareceres, além de autorização, anti-
self-decision, ordenação e transação já implementados na API #34.
Se `decisionMode=AUTOMATIC_VOTE`, retorna conflito e não muda candidatura.

## Ainda necessário antes do rollout

- Votos individuais, quórum, agregação, desempate e decisão automática.
- Visão administrativa das candidaturas e consolidação sigilosa de pareceres;
  tela específica para o gestor decidir aprovação/reprovação.
- Execução das etapas de candidato, avaliação objetiva, tentativas/prazos,
  publicação individual/em lote, recurso final e notificações.

Feature gate continua desabilitado; mudanças sob PRs draft e sem deploy.
