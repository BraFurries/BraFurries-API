# Recrutamento — correção objetiva de tentativas (incremento V1)

## Separação de autoridades
- Database #19 mantém a política congelável na tabela
  `recruitment_stage_attempt_policies` e grades em
  `recruitment_candidate_attempt_grades`, uma linha por tentativa,
  FK tenant + tentativa e restrições reais no MariaDB.
- API calcula pontuação exclusivamente a partir do gabarito congelado e
  respostas já validadas, na mesma transação do envio da tentativa.
- Front e Coddy não calculam notas, não conhecem gabaritos e não publicam
  decisões administrativas.

## Política configurável por etapa
Somente gestor ativo com `RECRUITMENT_MANAGE_PROCESSES`, durante
`DRAFT`, e com revisão otimista:

`PUT /user/me/communities/{communityId}/recruitment/processes/{processId}/stages/{stageId}/grade-policy`

```json
{"expectedRevision":4,"passingPercentage":70.00,"resultStrategy":"BEST"}
```

- Nota de corte de 1,00 a 100,00% inclusive. Acerto >= corte aprova a
  tentativa (não o processo seletivo).
- `BEST`: melhor percentual entre tentativas enviadas, empate escolhe
  a primeira. `LAST`: última tentativa enviada.
- Uma etapa AUTO_EVALUATION não pode ser publicada se faltarem
  política de tentativas **ou** corte/estratégia; políticas legadas sem
  esses campos continuam editáveis até serem completadas.

## Pontuação
- SINGLE_CHOICE, MULTIPLE_CHOICE, YES_NO, NUMBER e SCALE, com validação
  do formulário/gabarito existente.
- Acerto integral vale todos os pontos, erro/omissão vale zero;
  MULTIPLE_CHOICE exige conjunto de opções exatamente igual ao gabarito,
  sem pontos parciais.
- Números são comparados como BigDecimal, portanto 2 e 2.00 são iguais.
- Percentual comparado sem arredondar por floats: earned*100 >=
  cutoff*possible. Nota total máxima de 1.000.000 pontos.
- Um registro imutável por tentativa SUBMITTED, persistido com a
  submissão e auditoria na mesma transação. Se qualquer etapa falhar,
  a transação inteira é revertida.

## Visibilidade e auditabilidade
A resposta pública a `POST .../attempts/{attemptId}/submit` permanece
**sem nota, acertos, pontuação ou gabarito**.

Apenas gestor ativo e que não seja o candidato:
`GET /user/me/communities/{communityId}/recruitment/applications/{applicationId}/stages/{stageId}/objective-grades`.
Retorna histórico das notas internas e tentativa atualmente selecionada,
**sempre marcada provisional=true**; resultados não são finais enquanto
as tentativas/prazos e a transição não forem consolidados.

Auditorias `RECRUITMENT_GRADE_POLICY_UPDATED`,
`RECRUITMENT_OBJECTIVE_ATTEMPT_GRADED` e
`RECRUITMENT_GRADE_SUMMARY_VIEWED`, sem registrar respostas/gabaritos.

## Ainda fora do escopo
- Encerramento/consolidação final da prova após última tentativa ou prazo,
  avanço/reprovação de etapa e publicação manual de resultados.
- Interface do candidato, salvamento de rascunhos, cronômetro e
  controles de foco/cópia (desabilitados até implementação efetiva).
- Nota mostrada ao candidato, recurso final e notificações.

Não liberar recrutamento em produção. Feature gate OFF; todas as PRs
draft, sem merge/deploy/DML/Flyway em produção.
