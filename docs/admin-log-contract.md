# Follow-up do contrato de logs administrativos

Após a PR #62, `GET /admin/bot/logs` passa a propagar `nextSequence` do Coddy,
sem recalcular o cursor. `oldestSequence`, `latestSequence` e `cursorExpired`
continuam sendo repassados. `latestSequence` é o head do snapshot; o próximo
`after` deve usar `nextSequence`. Não há campo `hasMore`.

`nextSequence` corresponde ao último registro retornado quando existem mais
registros do filtro em outra página. Sem matching omitido, pode avançar até o
head, inclusive numa página vazia. Full load retorna os registros recentes e
inicializa o cursor no head. Um cursor expirado exige full reload sem `after`.

`after` é opcional e aceita inteiros não negativos. Um valor negativo resulta em
HTTP 400 antes de consultar o Coddy. O overload de serviço sem cursor permanece.

`status=available|unavailable` representa disponibilidade dos logs. O campo
`botStatus`, anteriormente sempre nulo, foi removido após busca nos três
repositórios: o único consumidor encontrado copiava esse campo para estado não
usado pelo template. O estado operacional continua exclusivamente em
`GET /admin/bot/status`; buscar logs não faz chamada adicional de status.

Na falha upstream, a resposta permanece `unavailable`, com itens vazios e cursores
nulos. O token interno fica somente na requisição ao Coddy. Se um produtor antigo
não enviar `nextSequence`, a API o mantém nulo, sem fingir que o head é seguro.
Coordenar a disponibilização do contrato Discord → API → Frontend.

Validação isolada, sem iniciar a aplicação ou datasource:

```text
mvnw -Dtest=AdminBotStatusServiceTest,AdminGeneralControllerTest test
mvnw -DskipTests package
```
