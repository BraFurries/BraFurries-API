# Recrutamento — decisões administrativas e avanço sequencial (recorte V1)

## Autoridade e dependências

- Schema imutável em [Database #16](https://github.com/BraFurries/BraFurries-Database/pull/16)
  (depende #15/#14/#13/#12); exclusivamente Flyway no repositório Database.
- API sobre [#33](https://github.com/BraFurries/BraFurries-API/pull/33).
  Front, Coddy e candidatos NÃO são autoridades de decisões.
- Feature gate permanece OFF; sem deploy ou migração em produção nesta entrega.

## Contrato interno

Base:
`/user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/decision`

| Método | Ação |
| --- | --- |
| GET | Somente gestor ativo com `RECRUITMENT_MANAGE_PROCESSES`, lê decisão existente |
| POST | Somente gestor ativo, grava decisão uma única vez |

POST:
```json
{"outcome":"APPROVE","reason":"Apto para próxima etapa"}
```
ou
```json
{"outcome":"REJECT","reason":"Não atendeu os critérios documentados"}
```

`reason` é opcional na aprovação (máximo 2000 caracteres) e obrigatório na
reprovação (mínimo 10, máximo 2000). GET/POST não são rotas do candidato.
POST devolve decisão, status administrativo da candidatura e próxima etapa.

## Semântica

1. Decisão sempre explícita, por gestor com vínculo ativo na Community e sem
   conflito de interesse (proibido decidir candidatura própria).
2. O alvo deve estar `AWAITING_REVIEW`, a candidatura `IN_PROGRESS`, todas
   as etapas anteriores `COMPLETED` e futuras `PENDING`.
3. Pelo menos **um parecer individual já registrado** é obrigatório.
   Não existe cálculo de média, quórum, voto majoritário ou aprovação por nota.
   Uma decisão manual não modifica os pareceres e é independente da nota.
4. `AUTO_EVALUATION` não pode receber decisão humana neste recorte.
5. `APPROVE`: conclui a etapa atual; próxima etapa sem formulário do candidato
   e não automática passa a `AWAITING_REVIEW`; próxima etapa com formulário
   ou automática passa a `AWAITING_CANDIDATE` para execução futura (não é
   dispensada automaticamente).
6. `REJECT`: conclui a etapa e marca futuras como `BLOCKED`.
   Reprovação e conclusão da última etapa configuram candidatura para
   `AWAITING_PUBLICATION`.
7. `AWAITING_PUBLICATION` **não significa que o candidato recebeu o resultado**:
   a leitura do candidato segue retornando `RECEIVED`. Publicação manual
   individual/em lote permanece uma entrega posterior.

## Concorrência e segurança

- `SELECT ... FOR UPDATE` na candidatura, transação Spring envolvendo inserção
  da decisão, mudança de etapa, da candidatura e auditoria.
- Índice único `(community_id,application_id,stage_id)`, sem overwrite.
  Duplicidade ou estágio desatualizado = 409; não se publica implicitamente.
- Chaves estrangeiras com `community_id` impedem vínculo a outra Community;
  queries sempre filtradas pela Community. Auditoria
  `RECRUITMENT_STAGE_DECIDED` registra IDs, não respostas nem justificativa.
- O estado do candidato, pareceres de terceiros e gabaritos não são expostos.
- Os testes Java cobrem autorização, anti-self-decision, concorrência lógica,
  pré-condições, avanço e não-publicação; testes JDBC e Flyway usam MariaDB
  10.6 descartável, versão de migration fixada por SHA na CI.

## Não implementado — bloqueadores do rollout

- Política configurável individual/coletiva, quorum e empate; votos e
  consolidação de decisão coletiva; regras por etapa.
- Resposta, tentativa e correção automática de etapas com candidato;
  desbloqueio de `AWAITING_CANDIDATE` pela execução real.
- Resultado final e recurso exclusivamente nesse resultado, publicação manual
  individual/em lote, notificações e UI de tomada de decisão.
- Controle de prazo, tentativas e estratégias antifraude.
- Rollout completo com testes end-to-end e contratos Front/API.

**Não habilitar o gate nem integrar a stack fora da ordem.**
