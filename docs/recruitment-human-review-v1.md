# Recrutamento BraFurries — revisão humana por etapa (recorte incremental V1)

## Dependências e autoridade

- Database [#15](https://github.com/BraFurries/BraFurries-Database/pull/15):
  `V20261022_003` cria `recruitment_stage_reviewers` e
  `recruitment_stage_assessments`. A API nunca executa DDL.
- API [#32](https://github.com/BraFurries/BraFurries-API/pull/32):
  inscrições e triagem read-only. Este incremento **substitui** a leitura ampla
  por consulta restrita a etapas explicitamente designadas.
- Front [#20](https://github.com/BraFurries/BraFurries-Front-End/pull/20):
  formulário e parecer individual. Coddy não participa da autorização nem da decisão.
- A cadeia de PRs pai permanece draft. Nenhum deploy, DML ou rollout em produção.

## Regras de autorização

1. Feature gate `app.recruitment.enabled` permanece OFF e uma única
   `app.recruitment.community-id` pode ser habilitada explicitamente.
2. `RECRUITMENT_MANAGE_PROCESSES` permite gerir designações por etapa e
   versão do processo; os IDs de pessoa/cargo são validados dentro da Community.
3. `RECRUITMENT_REVIEW_APPLICATIONS` **não basta para ler respostas**:
   também exige membro ativo e designação direta na etapa ou atribuição atual
   de cargo interno **ativo** designado nessa etapa.
4. A fila só inclui candidaturas de processos/versões com pelo menos uma etapa
   designada **já disponível** (IN_PROGRESS/AWAITING_REVIEW/COMPLETED/AWAITING_PUBLICATION);
   etapas futuras PENDING, SKIPPED e BLOCKED não liberam a candidatura.
   A resposta detalhada devolve somente etapas designadas e disponíveis.
   Respostas da inscrição só aparecem para avaliador da primeira etapa de
   APPLICATION. Uma designação para etapa futura não libera todo o processo.
5. Candidato jamais pode ler sua candidatura pelas rotas privilegiadas nem
   avaliá-la mesmo que tenha a capability e cargo designados.
6. A revogação de designação ou perda da capability retira imediatamente acesso
   a formulários, avaliações e respostas nas próximas requisições.
7. Um parecer individual só pode ser lido por seu autor enquanto ele ainda
   estiver designado. Nenhuma avaliação, gabarito ou decisão é exposta ao candidato.

## Rotas administrativas

Base: `/user/me/communities/{communityId}/recruitment`

| Método | Caminho | Capacidade |
| --- | --- | --- |
| GET | `/processes/{processId}/reviewer-stages` | Gerir processos; versões DRAFT/PUBLISHED/SUPERSEDED |
| GET | `/stages/{stageId}/reviewers` | Gerir processos |
| POST | `/stages/{stageId}/reviewers` | Gerir processos |
| DELETE | `/stages/{stageId}/reviewers/{assignmentId}` | Gerir processos |
| GET | `/applications/{applicationId}/stages/{stageId}/assessments/form` | Revisão + designação |
| GET | `/applications/{applicationId}/stages/{stageId}/assessments/mine` | Revisão + designação + autoria |
| POST | `/applications/{applicationId}/stages/{stageId}/assessments/mine` | Revisão + designação |

A designação exige exatamente um campo:
```json
{"userId": 99, "roleId": null}
```
ou
```json
{"userId": null, "roleId": 15}
```

Um parecer individual aceita nota opcional de 0 a 100, com até duas casas
decimais; texto opcional até 4.000 caracteres; e respostas ao formulário
privado da Staff, validadas no backend com as perguntas congeladas da versão:
```json
{
  "score": 80.50,
  "opinion": "Boa comunicação, pendente entrevista",
  "staffAnswers": {"communication": "clear"}
}
```

É necessário informar ao menos nota, parecer ou resposta de formulário.
As respostas obrigatórias do formulário interno precisam estar completas.
A API não permite avaliar etapa PENDING, AUTO_EVALUATION nem candidatura própria.

## Concorrência, auditabilidade e sigilo

- Uma única avaliação por Community/candidatura/etapa/avaliador: índice único
  no MariaDB. POST repetido retorna conflito, nunca sobrescreve dados.
- O alvo da avaliação é lido sob `FOR UPDATE`; mudanças de etapa e parecer
  respeitam a transação. O envio não altera estado da etapa ou da candidatura.
- Auditados: `RECRUITMENT_REVIEWER_ASSIGNED`,
  `RECRUITMENT_REVIEWER_REVOKED`, `RECRUITMENT_ASSESSMENT_SUBMITTED`.
  O log registra IDs, não armazena as respostas.
- Chaves corretas, critérios objetivos e dados de outros pareceristas nunca
  entram nos DTOs de avaliação humana.
- Database e API testam vínculos Community/version e duplicidade em MariaDB
  descartável. Testes unitários cobrem autorizações e isolamento.

## Fora deste recorte / bloqueadores de liberação operacional

- A gestão administrativa inicial oferece seleção por cargo interno e designação
  por ID interno de pessoa. Busca nominal de membros depende de contrato próprio.
- Política individual **ou coletiva** por etapa, contagem de votos, quórum,
  desempate e decisão humana/automática.
- Avanço sequencial, formulários de etapas subsequentes, prazos, tentativas,
  publicações individual/em lote, recursos apenas no resultado final e avisos.
- Estratégias de prova/antifraude configuráveis. Nenhuma alegação de
  impossibilidade de cola baseada apenas em restrições do navegador.
- Integração end-to-end completa de decisão e publicação, antes de ativar o gate.

**Não integrar PRs fora da ordem nem habilitar o recurso até os controles de
acesso e os fluxos de decisão/publicação estarem completos e validados.**
