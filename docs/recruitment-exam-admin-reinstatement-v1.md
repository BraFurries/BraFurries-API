# Recrutamento — revisão de bloqueio por falso positivo (V1)

## Autoridades
- Database #23: Flyway `V20261023_007`, tabela `recruitment_exam_reinstatements` com PK Community + attempt, FKs de Community/candidatura/etapa/tentativa e gestor, razão auditável 20–2000 caracteres. Nenhum dado legado modificado.
- API: regra, autorização, serialização, expiração e auditoria. Front: mesa administrativa e confirmação. Coddy sem alteração.

## Contrato administrativo
Apenas membro ativo com `RECRUITMENT_MANAGE_PROCESSES`; nunca na própria candidatura. Todas as operações usam `app.recruitment.enabled` e a mesma trava de candidatura `FOR UPDATE` das submissões, consolidações e eventos.

`GET /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/integrity-review`

Retorna tentativas BLOCKED e historicamente reabertas, até 100 sinais mais recentes por tentativa e indicador de truncamento. **Sem respostas, gabaritos ou notas.** Exige candidatura `IN_PROGRESS`, estágio `AUTO_EVALUATION` / `AWAITING_CANDIDATE` e nenhuma decisão da etapa.

`POST /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/integrity-review/attempts/{attemptId}/reinstate`

```json
{"reason":"Perda de foco provocada pelo sistema operacional, confirmada na revisão"}
```

## Regras
- Só reabre a tentativa exata BLOCKED por atingir o limite configurado, com sinais de foco persistidos e sem decisão definitiva na etapa.
- Uma única reabertura por tentativa; retries concorrentes falham sem duplicação. Justificativa obrigatória, autor e prazo renovado registrados em tabela separada.
- **É a mesma tentativa, com seu ID e número originais**, estado STARTED e contador corrente de foco zerado; recebe o tempo integral configurado a partir do horário do MariaDB. Os eventos antigos continuam imutáveis.
- Se o prazo rígido original já venceu, a exceção registrada vale **apenas para essa tentativa** e seu novo `expires_at`. Nenhum outro candidato ou tentativa ganha prazo extra.
- Ao ocorrer outro BLOCKED da mesma tentativa, não há segunda reabertura administrativa nesta V1; gestor pode apenas finalizar a etapa pelos critérios anteriores.
- O resultado da seleção nunca é publicado ou alterado pelo fluxo de reabertura; outras notas e tentativas permanecem inalteradas.
- O sinal do navegador **não prova fraude**: acessibilidade, troca de aplicativo do sistema, notificações e perda de conexão podem causar falsos positivos.

## Pendências
Autosave de respostas; processo de contestação iniciado pelo próprio candidato; revisão formal por múltiplos gestores; comunicação/notificação; configuração de limites de reaberturas e suporte operacional. Feature gate OFF até rollout autorizado, todas as PRs permanecem draft.
