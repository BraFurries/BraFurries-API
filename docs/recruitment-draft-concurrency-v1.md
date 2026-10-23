# Recruitment V1 — concorrência de rascunhos

## Motivação

Formulários e etapas são editáveis por múltiplos gestores delegados.
Serializar writes somente no banco não impede **lost updates**: duas sessões
podem carregar o mesmo formulário e a segunda sobrescrever a primeira.

## Contrato

A coluna `recruitment_processes.draft_revision`, criada exclusivamente pela
[Database #13](https://github.com/BraFurries/BraFurries-Database/pull/13),
é a revisão **do processo inteiro**, compartilhada entre metadados, criação
de etapas, formulário do candidato, formulário da Staff, reordenação e remoção.

- `GET /user/me/communities/{communityId}/recruitment/processes/{id}`
  retorna `process.draftRevision`.
- `GET .../stages/{id}/definition` retorna `draftRevision` para a
  definição restrita da Staff.
- Todo `PUT` administrativo e `POST .../stages` exige
  `expectedRevision` não negativo no JSON.
- `PUT .../stages/order` exige `stageIds` e `expectedRevision`.
- `DELETE .../stages/{id}?expectedRevision=N` exige query param explícito.
- O campo não é um segredo nem um controle de autorização. Tenant gate,
  autenticação e `RECRUITMENT_MANAGE_PROCESSES` permanecem obrigatórios.
- O backend compara e incrementa `draft_revision` no mesmo commit
  transacional da alteração; writes de etapas também usam `FOR UPDATE`.
- Uma revisão antiga recebe HTTP **409 Conflict** sem persistir a operação.
- Revisão ausente, negativa ou inválida recebe HTTP **400 Bad Request**.
- Depois de gravar, o cliente deve usar a revisão atual devolvida ou
  buscar o processo novamente; nunca repetir automaticamente um write 409.
- O Front preserva o buffer local e exige clique explícito para descartar
  a edição e recarregar a versão remota.

## Testes e limitações

Testes unitários e MariaDB 10.6/Testcontainers verificam CAS em processo,
append de etapa, alteração de formulários, ordenação, tenants distintos e
salvamento idêntico com linha unchanged. O snapshot sintético da CI é formado
por 107 tabelas legadas, 5 tabelas da Database #12 e a migration da Database
#13, ambos SHA-pinned.

Não é lock de edição/presença, nem colaboração em tempo real, nem resolve
conflitos automaticamente. A V1 adota resolução por recarregamento e
edição manual, sem expor pareceres ou gabaritos a candidatos.

## Merge/rollout

Dependência: Database #12 → Database #13 → API #27 → API #28 → API #29 →
Front #15 → Front #16. A ordem de integração pode preservar o código
acumulado nas branches, mas as migrações necessárias **devem** chegar ao
Database antes da ativação de endpoints correspondentes.

As PRs stacked apontando para main devem ser rebased/retargeted após seus
parents e ter diffs, HEAD, reviews e CI revalidados. Não mergear nem
aplicar alterações de produção sem autorização explícita.
