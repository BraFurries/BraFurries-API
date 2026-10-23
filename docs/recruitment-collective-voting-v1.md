# Recrutamento — votação coletiva com quórum e desempate (motor API, incremento V1)

## Arquitetura e dependências

- Database #17 cria `recruitment_stage_votes` com unique
  `(community_id,application_id,stage_id,voter_user_id)`, FK composta
  para **parecer do próprio eleitor na mesma etapa**, e coluna
  `recruitment_stage_decisions.decision_source` com default MANUAL.
  Banco é autoridade do schema/Flyway, não API/Coddy.
- API estende `RecruitmentDecisionPolicy` por etapa, gravada no JSON já
  existente, congelado na publicação da definição de processo.
- Front atual não oferece votação nem consolidação; o gate permanece OFF,
  publicação de processo com `AUTOMATIC_VOTE` segue **bloqueada** até
  existir interface e fluxo integrado. Não executar DML em produção.

## Política por etapa

```json
{
  "reviewMode": "COLLECTIVE",
  "decisionMode": "AUTOMATIC_VOTE",
  "minimumAssessments": 2,
  "minimumVotes": 3,
  "tiePolicy": "MANAGER_OVERRIDE"
}
```

- `minimumAssessments`: quantidade de **pareceres** exigidos,
  2 a 20 quando COLLECTIVE.
- `minimumVotes`: quórum de **votos distintos**, 2 a 20.
- `tiePolicy`: `REJECT_ON_TIE` ou `MANAGER_OVERRIDE`.
- Votação exige revisão coletiva; decisão manual continua aceitando
  o formato legado de 3 campos.
- O quórum não determina encerramento imediato: gestor solicita
  consolidação explicitamente para não encerrar ao entrar o N-ésimo
  voto enquanto outros avaliadores estão participando.
- A preferência/nota do parecer NÃO implica voto; é uma ação separada.

## API

Base:
`/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/votes`

| Método | Rota | Permissão |
| --- | --- | --- |
| GET | `/mine` | Avaliador designado e com capacidade de revisar; mostra apenas o próprio voto |
| POST | `/mine` | Avaliador designado, ativo e com parecer já submetido; `{"choice":"APPROVE"}` ou `{"choice":"REJECT"}` |
| GET | `/tally` | Gestor ativo; apenas totais, quórum e política de empate |
| POST | `/finalize` | Gestor ativo; calcula maioria com lock da candidatura e consolida sem divulgar resultado |

`POST /finalize` com maioria ou `REJECT_ON_TIE` não exige body.
Em empate com `MANAGER_OVERRIDE`, exige body como
`{"outcome":"APPROVE","reason":"Critérios documentados para o desempate"}`;
a justificativa precisa ter 10–1800 caracteres.
Override jamais pode alterar um resultado majoritário.

A decisão preserva `decision_source`:
`VOTE_MAJORITY`, `VOTE_TIE_REJECT`, `VOTE_TIE_OVERRIDE`.
Auditorias: `RECRUITMENT_BALLOT_CAST`,
`RECRUITMENT_VOTE_DECISION_FINALIZED`,
`RECRUITMENT_BALLOTS_CONSOLIDATED`.

## Garantias

- Community em todas as consultas/constraints. Mesmo membro pode atuar
  em múltiplas comunidades sem vazamento entre elas.
- Voto imutável, único por pessoa e etapa e impossível sem parecer
  correspondente; designação e capacidade são revalidadas na API.
- Membro/candidato não pode votar ou decidir a própria candidatura.
- Leitura individual nunca expõe o voto de terceiros. Gestores consultam
  apenas somatório, não a identidade de cada votante.
- Votação e consolidação usam `SELECT ... FOR UPDATE` na candidatura,
  a mesma trava das decisões manuais. Atualização de etapa, candidatura,
  decisão única e auditoria ocorrem na mesma transação.
- Votos não são interpretados como publicação; candidatos continuam
  recebendo `RECEIVED` até a publicação manual futura.
- MariaDB e CI descartáveis: validações reais de UNIQUE, FK, isolamento,
  fonte da decisão e testes de regras.

## Limites para a próxima PR

- Interface para configurar quórum/desempate; voto privado na triagem;
  placar restrito ao gestor e confirmação de consolidação.
- Revalidar pré-publicação para liberar `AUTOMATIC_VOTE` somente quando
  o fluxo end-to-end estiver pronto. Hoje ainda é bloqueado.
- Execução de provas/formulários de candidato, publicação individual/lote,
  recursos somente finais, prazos/antifraude e notificações.
