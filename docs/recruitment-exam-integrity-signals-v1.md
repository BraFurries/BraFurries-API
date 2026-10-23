# Recrutamento — sinais de integridade da prova (incremento V1)

## Autoridades e limite da solução

Database PR #22: Flyway V20261023_006, eventos por Community e tentativa,
UUID idempotente, FK ao attempt, horário do MariaDB. Schema já possuía
`max_focus_losses`, `block_copy`, `focus_losses` e status `BLOCKED`.
API: única autoridade dos limites, bloqueio, autorização e auditoria.
Front: dissuasão visual e **sinais de navegador não confiáveis**;
Coddy sem participação.

Este recurso NÃO comprova fraude e não impede screenshot, fotos,
manipulação do JavaScript, outra aba/dispositivo ou uso de IA. Navegadores
podem atrasar ou descartar requests quando a aba é ocultada. Evitar rotular
os sinais registrados como prova de má-fé.

## Configuração de rascunho

`PUT /user/me/communities/{communityId}/recruitment/processes/{processId}/stages/{stageId}/attempt-policy`

Agora aceita `maxFocusLosses` null (desabilitado) ou 1–10
(3 recomendado quando explicitamente desejado), além de `blockCopy`
boolean (false padrão). Regras validadas pela API: gestor ativo, tipo
`AUTO_EVALUATION`, versão DRAFT, revisão otimista e auditoria.

Políticas publicadas são congeladas. Não converter `NULL` legado
em bloqueio automático.

## Contrato do candidato

O POST de início/retomada devolve `maxFocusLosses`, `blockCopy` e
`focusLosses`, sem respostas nem gabarito. O GET de exame atual devolve
`blocked` derivado do servidor, não do navegador.

`POST /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/attempts/{attemptId}/integrity-events`

Exemplo:
```json
{
  "eventId": "00000000-0000-4000-8000-000000000001",
  "eventType": "FOCUS_LOST",
  "source": "TAB_HIDDEN"
}
```

Tipos e origens:
- `FOCUS_LOST`: `TAB_HIDDEN`, `WINDOW_BLUR`;
- `COPY_PREVENTED`: `COPY`, `CUT`, `CONTEXT_MENU`.

A API valida a titularidade da candidatura, Community, fase e
tentativa STARTED dentro do prazo sob o MESMO `FOR UPDATE` usado por
início, envio e consolidação. Mesmo UUID de evento retransmitido retorna
contagem anterior sem penalidade dupla. A gravação do evento, incremento
de `focus_losses`, transição para BLOCKED e auditoria são uma única
transação. Cópia impedida é evento informativo, nunca conta foco.

Se `focus_losses >= max_focus_losses`, a tentativa passa a BLOCKED:
não pode mais enviar respostas nem iniciar nova tentativa na etapa,
mesmo se ainda restarem oportunidades do limite `maxAttempts`.
Nenhum resultado é mostrado ou publicado ao candidato por causa disso.

O gestor pode finalizar explicitamente a etapa bloqueada; pela
política V1, a avaliação bloqueada é REJECT para a etapa, mesmo se
houve nota alta em tentativa anterior. Não há reprovação *publicada*
automaticamente. O resultado final e eventuais recursos seguem
as regras de publicação manual. Recomenda-se informar essa política
claramente ao candidato antes de iniciar.

## UI e confiabilidade

O navegador usa `window.blur` e `document.visibilitychange`, com
deduplicação de um evento por ciclo fora da prova e evento UUID em fila
para retransmissão. Fila e respostas ficam apenas na memória
da sessão atual; não há armazenamento de clipboard ou texto copiado.

A restrição de cópia aplica `user-select:none` e `preventDefault`
somente ao conteúdo da tentativa. Nunca é uma garantia antifraude.

## Pendências / rollout

Ainda sem autosave, supervisão antifraude externa, inspeção de imagem,
revisão/disputa independente de bloqueios ou monitoramento server-push.
Antes de habilitar em produção, validar consentimento/acessibilidade,
comunicação da política e capacidade de suporte para falsos positivos.
Gate `app.recruitment.enabled` permanece OFF. Não mergear/deployar
ou executar migration/DML em produção sem autorização explícita.
