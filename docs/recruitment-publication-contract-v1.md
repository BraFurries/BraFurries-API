# Processos Seletivos — validação e publicação administrativa (incremento V1)

Stack: Database [#12](https://github.com/BraFurries/BraFurries-Database/pull/12)
+ [#13](https://github.com/BraFurries/BraFurries-Database/pull/13);
API [#27](https://github.com/BraFurries/BraFurries-API/pull/27),
[#28](https://github.com/BraFurries/BraFurries-API/pull/28) e
[#29](https://github.com/BraFurries/BraFurries-API/pull/29).

**Esta PR depende integralmente da API #29 e não deve ser integrada antes dela.**
Não cria schema na API e não altera o Coddy.

## Objetivo desta fatia

Fechar a definição do processo de forma irreversível para inscrições futuras:
aplicar regras de elegibilidade tipadas, executar pré-validação no backend e
promover a versão DRAFT a PUBLISHED. A transição é atômica, com CAS da revisão
de rascunho e auditoria. Não existe endpoint para reabrir edição dessa versão.

**Publicar definição NÃO é abrir inscrições.** Nesta entrega o processo
permanece `visibility=PRIVATE`, `enrollment_status=CLOSED`, com
`process.status=ACTIVE` e `version.status=PUBLISHED`.
Isso protege candidatos enquanto o fluxo de respostas, tentativas e prazos
ainda não está implementado.

## Permissões

- `RECRUITMENT_MANAGE_PROCESSES`: configura elegibilidade, como já faz com
  as etapas e os formulários.
- `RECRUITMENT_PUBLISH_PROCESSES`: publica definição. Independente da
  capacidade de editar e delegável via cargo interno ou usuário.
- Ambos podem consultar o preview; nunca recebem acesso cross-community.
- As três rotas verificam feature gate BraFurries + Community auth na API.

## Endpoints — administrativos

Base: `/user/me/communities/{communityId}/recruitment/processes/{processId}/publication`

| Método | Rota | Corpo/efeito |
| --- | --- | --- |
| GET | `/preview` | `ready`, `blockers`, `processVersionId`, `draftRevision`, `stageCount` |
| PUT | `/eligibility` | `{"expectedRevision":0,"eligibility":{"minMembershipDays":30,"requireVip":false,"requirePartner":false}}` |
| POST | `/publish` | `{"expectedRevision":1}` — publicação irreversível da versão |

`minMembershipDays` tem intervalo 0..3650. VIP e parceria são exigências
opcionais; não substituem o requisito básico de membro ativo da BraFurries.
O contrato de candidaturas futuro deve aplicar tais critérios **usando**
`CommunityMembershipStatusResolver`, com Portaria e bans respeitados, e não
apenas presença de registro em `user_community_status`.

## Regras de pré-validação

- Somente um processo em `DRAFT` com exatamente uma versão editável pode publicar.
- Regras de elegibilidade explicitamente configuradas, parseáveis e válidas.
- Ao menos uma etapa, no máximo 60, sequência sem lacunas.
- Formulários de candidato e Staff são revalidados no backend para todas as
  etapas. Provas AUTO_EVALUATION exigem questões objetivas, gabaritos válidos
  e pontuações. A validação não confia somente em JSON_VALID do MariaDB.
- Não impõe etapa fixa de entrevista, análise ou decisão; a sequência de etapas
  é configurável pelo gestor.
- Operação de publicar exige revisão numérica atual e faz check sob
  `SELECT ... FOR UPDATE`. Revisão antiga/incompletude -> HTTP 409.
- Não há atualização estrutural após a publicação. Clonagem para uma nova
  versão será outra entrega.

## Separação crítica de segurança

Não há retorno de gabaritos, Staff forms ou respostas para candidatos. Não há
endpoints de inscrição, tentativa, nota, recurso ou publicação de resultados.
As regras de elegibilidade publicadas são uma configuração congelada que será
**aplicada** quando a implementação de inscrições estiver disponível.

## Verificação e rollout

- Testes de autorização por Community, permissão separada, pré-validação,
  etapa AUTO_EVALUATION inválida, revisão obsoleta, elegibilidade fora de
  intervalo e auditoria.
- MariaDB 10.6 descartável com fixture SHA-pinned baseline + Database #12/#13
  verifica transição real, `PRIVATE/CLOSED`, versão congelada, CAS e isolamento.
- CI Java completa + compatibilidade Coddy ↔ API, sem Discord real.
- Não mergear, não habilitar feature flag e não executar DML/DDL em produção
  sem autorização explícita e inspeção recente de HEAD/checks.
