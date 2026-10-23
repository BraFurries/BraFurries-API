# Recrutamento — autosave seguro da prova (V1)

## Escopo e autoridade
- Database #24 / Flyway `V20261023_008`: campos adicionais na tentativa; não altera `answers_json` definitivo.
- API: fonte autoritativa de expiração, elegibilidade, permissões, validação de resposta parcial e CAS.
- Front: restaura antes de permitir edição, debounce de 1,2s, solicita salvamento antecipado no blur quando viável e informa falhas; sem armazenar respostas em LocalStorage.
- Coddy: nenhuma mudança. `app.recruitment.enabled` permanece OFF.

## Contrato
`GET /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/attempts/{attemptId}/draft`
Retorna `{attemptId, revision, savedAt, answers}` (campos do próprio candidato apenas). Sem nota, gabarito ou decisões administrativas.

`PUT .../{attemptId}/draft`
Corpo: `{"expectedRevision":0,"answers":{"question":"a"}}`
Retorna o mesmo contrato com a revisão incrementada.

## Regras
- Leitura e gravação requerem usuário membro ativo, candidatura própria, processo ACTIVE, versão PUBLISHED, etapa AUTO_EVALUATION/AWAITING_CANDIDATE, tentativa STARTED não expirada. Prazo final rígido respeitado, exceto tentativa especificamente reaberta administrativamente.
- A mesma trava `recruitment_applications FOR UPDATE` usada por submit, evento de integridade, finalização e reinstatement serializa alterações. O UPDATE exige Community + candidatura + etapa + attempt + revisão esperada e estado STARTED.
- `409` em revisão obsoleta; o cliente não substitui automaticamente a versão mais recente com dados de outra aba. Falha de rede preserva respostas enquanto a página estiver aberta; retry explícito.
- Permite respostas incompletas e limpar um campo. Não aceita perguntas desconhecidas, alternativas inválidas ou payloads acima de 100.000 caracteres serializados. Validação obrigatória permanece integral em `/submit`.
- Envio final continua usando o payload de submit independente do autosave, com correção e auditoria na mesma transação. No commit, limpa `draft_answers_json`; autosaves tardios nunca podem alterar `answers_json` de uma tentativa SUBMITTED.
- Prova BLOCKED não permite autosave nem resposta; revisão administrativa conserva o rascunho até possível reabertura.
- Sem garantia de salvar uma edição que ocorrer imediatamente antes da queda de conexão ou encerramento abrupto do navegador: UI exibe estado salvo/não salvo explicitamente.

## Rollout
Dependência de Database #24, API draft anterior #44 e Front draft anterior #26. Validar Flyway/MariaDB, testes JDBC/unit, Angular specs/build; sem merge, deploy ou DML em produção até autorização.
