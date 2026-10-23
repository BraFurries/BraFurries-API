# Recrutamento V1 — consolidação de etapa objetiva

## Escopo e arquitetura

Database #20 é autoridade do schema de proveniência, via Flyway
`V20261023_004`. API é autoridade da autorização, das regras de
consolidação, do avanço e da auditoria. Front apenas consultará/solicitará
consolidação futura; Coddy não participa.

A API existente corrige cada tentativa enviada **no mesmo commit da resposta**.
Este incremento decide a etapa somente por ação administrativa explícita,
sem divulgar resultado ao candidato e sem corrigir respostas em browser.

## Contrato privado

`POST /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/objective-finalization`

Requer feature gate habilitado somente na BraFurries, capacidade
`RECRUITMENT_MANAGE_PROCESSES` e vínculo ativo. É proibido decidir a
própria candidatura, mesmo com perfil de gestor. Não aceita resultado
ou nota no body.

A transação bloqueia `recruitment_applications` com `FOR UPDATE` —
mesmo lock de iniciar/enviar tentativas e decidir etapas humanas — e
confere Community, versão da candidatura, estágio `AUTO_EVALUATION`,
estado `AWAITING_CANDIDATE`, anterior `COMPLETED`, posterior `PENDING`,
ausência de decisão anterior e política congelada.

## Quando pode finalizar

- Nenhuma tentativa `STARTED` ainda válida. Tentativas expiradas
  são marcadas `EXPIRED`, consumindo oportunidade configurada.
- Prazo final de início `deadlineAt` já passou **ou** limite de
  `maxAttempts` já foi consumido.
- Todas as tentativas `SUBMITTED` têm nota persistida. Se faltar nota,
  a consolidação bloqueia (não trata a falta como nota zero).
- Prazo flexível: a tentativa iniciada antes do encerramento pode
  continuar até `expires_at`, mesmo se `deadlineAt` já tiver passado.
- Prazo rígido: `expires_at` já é limitado pelo prazo final ao iniciar.
- Sem qualquer submissão válida após esgotadas as oportunidades:
  reprovação fundamentada, sem inventar tentativa/nota.

O seletor comum do painel interno e da finalização escolhe **BEST** pelo
maior quociente exato de pontos obtidos/possíveis (empate mantém a primeira)
ou **LAST** pela última tentativa enviada. O resultado da política
`passingPercentage` é o booleano calculado pela API e persistido junto à
nota, nunca aceito do candidato.

## Efeitos atômicos

- Decisão única por Community/candidatura/etapa, com
  `decision_source='OBJECTIVE_GRADE'` e FK de
  `objective_attempt_id` para a tentativa selecionada **do mesmo
  tenant, candidatura e etapa**; NULL apenas se não houve nota elegível.
- Status da etapa concluída `COMPLETED`.
- Aprovação libera exatamente a próxima etapa
  (`AWAITING_CANDIDATE` para avaliação automática/formulário de
  candidato, senão `AWAITING_REVIEW`); se era a última, candidatura fica
  `AWAITING_PUBLICATION`.
- Reprovação bloqueia futuras etapas `PENDING` e marca a candidatura
  `AWAITING_PUBLICATION`.
- Auditoria `RECRUITMENT_OBJECTIVE_STAGE_FINALIZED` na mesma transação;
  confirmação administrativa pode apresentar a nota, mas tracking público
  continua retornando `RECEIVED`.

Outra solicitação sobre etapa já decidida recebe 409 e não altera nada.
Tentativas e decisões concorrentes são serializadas pelo mesmo lock.

## Fora desta entrega

- Não há publicação do resultado individual/em lote nem recurso final.
- Não há interface de prova/cronômetro/retomada no Front.
- Não há conclusão automática em background: a gestão precisa finalizar.
- Antifraude opcional permanece indisponível até implementação real.
- Não habilitar feature gate ou publicar processos reais.

Tudo fica em PR draft, sem merge, deploy ou migration/DML de produção.
