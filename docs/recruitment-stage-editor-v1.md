# Processos seletivos — editor de etapas e formulários (API V1)

Esta entrega é uma **PR dependente da API #27** (rascunhos), que por sua
vez depende da [Database #12](https://github.com/BraFurries/BraFurries-Database/pull/12).
O Front ainda não está incluído nesta PR.

## Segurança e rollout

- `RECRUITMENT_ENABLED=false` e `RECRUITMENT_COMMUNITY_ID=0` mantêm
  todos os endpoints **indisponíveis por padrão**.
- Acesso a definições é **somente Staff com**
  `RECRUITMENT_MANAGE_PROCESSES`; retorno inclui gabarito e formulário
  interno. **Não expor esse DTO em rotas do candidato.**
- Nenhuma atualização de processos publicados ou de versões publicadas.
- SQL parametrizado com `community_id`, `process_id` e, ao modificar,
  `process_version_id`. Lock `FOR UPDATE` no processo serializa append,
  edit, reorder e remoção.
- Alteração auditada em `community_audit_log`. Nenhum DDL na API.

## Rotas internas

Base: `/user/me/communities/{communityId}/recruitment/processes/{processId}/stages`

| Método | Rota | Efeito |
| --- | --- | --- |
| GET | `/{stageId}/definition` | Lê formulários e gabaritos restritos |
| PUT | `/{stageId}/definition` | Edita nomes, tipo e formulários independentes |
| PUT | `/order` | Reordena uma permutação exata dos IDs da versão draft |
| DELETE | `/{stageId}` | Remove etapa draft e compacta posições |

Exemplo de edição:
```json
{
  "internalName": "Prova de regras",
  "publicName": "Avaliação de conhecimento",
  "stageType": "AUTO_EVALUATION",
  "candidateForm": {
    "questions": [
      {
        "id": "rule_question_1",
        "label": "Qual alternativa está correta?",
        "type": "SINGLE_CHOICE",
        "required": true,
        "options": [
          {"id": "a", "label": "Alternativa A"},
          {"id": "b", "label": "Alternativa B"}
        ],
        "points": 10,
        "correctAnswers": ["a"]
      }
    ]
  },
  "staffForm": {
    "questions": [
      {
        "id": "notes",
        "label": "Observações da Staff",
        "type": "LONG_TEXT",
        "required": false
      }
    ]
  }
}
```

## Contrato do builder

Tipos: `SHORT_TEXT`, `LONG_TEXT`, `SINGLE_CHOICE`,
`MULTIPLE_CHOICE`, `YES_NO`, `NUMBER`, `SCALE`, `DATE`,
`LINK` e `CONSENT`.

- Até 60 perguntas por formulário, IDs únicos com padrão estável.
- Alternativas exclusivas de SINGLE_CHOICE e MULTIPLE_CHOICE,
  2–20 opções com IDs únicos.
- Escalas numéricas de 0 a 10; NUMBER aceita limites opcionais.
- Respostas corretas e points só são aceitos no formulário do
  candidato numa etapa AUTO_EVALUATION.
- Em AUTO_EVALUATION, questões precisam ser objetivas e ter gabarito,
  sem respostas duplicadas, com pontuação positiva.
- O formulário da Staff padroniza a avaliação **geral**; não exige
  análise individual de cada resposta do candidato.
- Formulários são validados e serializados pela API antes do
  armazenamento em colunas JSON textuais da migration Database #12.

## Ordenação e versionamento

A constraint única `(community_id,process_version_id,position)` impede
trocas diretas de posições no MariaDB. A reordenação utiliza posições
temporárias livres em duas passagens, no mesmo lock/transação. A deleção
compacta posições, sem gaps; FKs impedem remover etapa já instanciada.

Uma edição posterior à publicação exige criação de **outra versão**
em uma próxima entrega. Esta PR somente altera a versão DRAFT, sem
implementar publicação, clonagem de versões, avaliação nem inscrições.

## Evidências

- Testes unitários de tipos, gabaritos, entradas inválidas,
  feature gate, autorização e auditoria.
- Testes JDBC em MariaDB 10.6 descartável com o snapshot de 107 tabelas
  e a migration proposta pela Database #12, incluindo ordenação real
  e negação de atualização cross-tenant/published.
- GitHub Actions valida CI; nenhuma produção ou Database compartilhado.

## Fora desta fatia

CRUD visual completo no Front (PR própria), modelo de execução das
tentativas, correção de respostas, políticas avançadas de prazo,
votação, agendamento, divulgação e publicação de seleções.
