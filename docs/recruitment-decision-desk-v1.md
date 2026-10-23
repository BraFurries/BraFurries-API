# Recrutamento V1 — mesa de decisões e consolidação de pareceres

## Escopo

Esta entrega complementa as PRs de Database #12–#16, API #27–#35 e Front #19–#21,
mantidas em draft. Não cria tabelas, altera migration nem autoriza deploy:
usa as avaliações, decisões, candidaturas e políticas por etapa existentes.

**Autoridade**: API decide, autoriza, audita e controla a transição de estados.
O Front apresenta a mesa de decisões e envia ações explícitas. Coddy não
administra regras de recrutamento. Community é o tenant, inclusive nas FKs.

## Contratos novos — somente gestão

Base:
`/user/me/communities/{communityId}/recruitment/processes/{processId}/decisions`

| Método | Endpoint | Projeção |
| --- | --- | --- |
| GET | `/queue?beforeId={id}` | Últimas 100 candidaturas por página; exclui candidatura própria |
| GET | `/applications/{applicationId}` | Candidatura, etapas com contagem/política/decisão e respostas já submetidas na primeira etapa |
| GET | `/applications/{applicationId}/stages/{stageId}/evidence` | Pareceres individuais confidenciais da etapa disponível, incluindo nota, opinião e respostas do avaliador (até 20, com `truncated` se houver mais) |

Todos requerem **RECRUITMENT_MANAGE_PROCESSES**, pertencimento **ativo** à
Community, feature gate específico da BraFurries e consistência
`communityId/processId/applicationId/processVersionId/stageId`. A leitura
da própria candidatura é proibida, inclusive para proprietários/gestores.
As permissões `RECRUITMENT_REVIEW_APPLICATIONS` e designação de etapa,
isoladamente, **não concedem acesso** à mesa de decisões.

A consulta de etapa futura (`PENDING`, `BLOCKED`, `SKIPPED`) não
retorna pareceres. Não envia gabaritos, definições de prova, formulário
interno ou respostas de outras etapas. Campos JSON retornados são apenas
respostas efetivamente submetidas. O cliente Angular renderiza os textos
como texto, sem interpretar HTML.

Auditoria transacional por ação:
`RECRUITMENT_MANAGER_QUEUE_VIEWED`,
`RECRUITMENT_MANAGER_APPLICATION_VIEWED` e
`RECRUITMENT_MANAGER_EVIDENCE_VIEWED`.
A auditoria registra apenas identificadores, não o conteúdo privado.

## Decisões

O Front utiliza POST já existente em:
`/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/decision`

Exige confirmação explícita, justificativa de 10–2000 caracteres para
reprovação, e mostra a quantidade mínima de pareceres configurada.
**Todas as regras são revalidadas na API**: identidade do gestor, veto à
candidatura própria, estado `AWAITING_REVIEW`, ordem das etapas,
mínimo de pareceres da política congelada, ausência de decisão anterior e
operação atômica com lock de candidatura.

`AWAITING_PUBLICATION` permanece interno: a API pública do candidato
continua retornando `RECEIVED`. Não envia notificações nem publica
resultados implicitamente. Ao concluir uma ação, o Front limpa respostas,
pareceres e justificativa da memória e atualiza a fila. Fechar detalhe,
mudar Community/processo ou revogar acesso também limpa esse conteúdo.

## Limites e sequência

- O painel atual não soma votos nem calcula decisão por média de notas;
  as recomendações são individuais, não equivalem a votos.
- A política AUTOMATIC_VOTE continua bloqueada na API e na publicação;
  faltam motor de votação, quórum e desempate.
- Não existem ainda liberação/correção de provas do candidato, publicação
  individual/em lote, recursos finais, avisos, prazos ou antifraude.
- Esta entrega não habilita recrutamento em produção; feature gate
  permanece OFF e o rollout depende da stack completa validada.
