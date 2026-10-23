# Recrutamento — tentativas do candidato, recorte 1

## Autoridade e segurança

- Database #18 é autoridade da migration Flyway `20261023.002`, com política
  de tentativa por `(community_id,process_version_id,stage_id)` e tentativas
  individuais vinculadas à Community, candidatura e versão por FKs.
- A API valida titularidade do candidato, vínculo ativo, Community, status
  `IN_PROGRESS` da candidatura, status `AWAITING_CANDIDATE` da etapa,
  tipo `AUTO_EVALUATION` e processo/versão publicados e ativos.
- O Front ainda não foi implementado nesta fatia; Coddy não participa.
- Nenhuma definição com gabarito, pontos, formulário interno ou resposta
  correta é enviada ao candidato. A API só fornece a projeção segura
  de alternativas/perguntas.
- `SELECT ... FOR UPDATE` na candidatura serializa abertura de tentativas e
  envio concorrente, com unique em `(Community, application, stage, number)`.

## Política administrativa — somente rascunho e gestão ativa

`PUT /user/me/communities/{communityId}/recruitment/processes/{processId}/stages/{stageId}/attempt-policy`

```json
{
  "expectedRevision": 4,
  "maxAttempts": 2,
  "timeLimitSeconds": 1800,
  "deadlineAt": "2026-11-30T23:59:59Z",
  "hardDeadline": false,
  "maxFocusLosses": null,
  "blockCopy": false
}
```

A política admite 1–5 tentativas, 60–14.400 segundos por tentativa,
deadline opcional e rigidez por etapa. `hardDeadline:true` limita
a conclusão ao menor prazo entre relógio individual e encerramento da etapa;
`false` permite concluir uma tentativa **iniciada antes** do prazo final
até terminar o tempo individual. Requer revisão otimista do rascunho e
capacidade `RECRUITMENT_MANAGE_PROCESSES`. Grava auditoria de edição.

**Antifraude futuro:** `maxFocusLosses` e `blockCopy` existem no schema,
mas esta API recusa habilitá-los. Restringir cópia por JS/CSS é só
dissuasivo e não impede captura de tela ou uso externo de IA.

## Endpoints do candidato

`POST /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/attempts/start`

Inicia tentativa ou retoma a atual ainda válida. Responde `attemptId`,
`attemptNumber`, `expiresAt` e `form` seguro. Tentativas expiradas
consomem o limite configurado. Inscrição recém-enviada não libera
imediatamente a prova: a etapa precisa ser avançada para
`AWAITING_CANDIDATE` pela API.

`POST /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/attempts/{attemptId}/submit`

```json
{"answers":{"question_1":"option_b","question_2":true}}
```

Envia uma única vez as respostas validadas contra o formulário
congelado, dentro do prazo do **servidor**, e grava a tentativa como
`SUBMITTED`. Não permite enviar por outra Community, usuário, etapa ou
versão, nem repetir submissão. Nenhuma nota, gabarito, ranking ou
resultado administrativo é devolvido.

## Deliberadamente não entregue

- Motor de correção objetiva e critérios de aprovação (não há definição
  aprovada de nota de corte, repetição e melhor tentativa).
- Transição automática para a próxima etapa após correção; a candidatura
  permanece em espera e nunca é implicitamente aprovada.
- UI do candidato, retomada visual, cronômetro e autosave.
- Contagem real de perdas de foco, bloqueio opcional após três saídas,
  restrição de cópia e registro de eventos antifraude.
- Publicação individual/em lote de resultados e recurso final.

A V1 inteira continua bloqueada pelo feature gate e pelas PRs draft.
Sem produção, merge ou migração real.
