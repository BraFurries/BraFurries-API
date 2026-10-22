# Recrutamento — inscrições dos candidatos, fatia V1

Stack: Database #12 -> #13 -> [#14](https://github.com/BraFurries/BraFurries-Database/pull/14);
API #27 -> #28 -> #29 -> #30 -> esta PR. O Front será uma PR independente.
Nenhuma migração será aplicada em produção sem uma autorização própria.

## Segurança
- Todos os caminhos passam por `RecruitmentFeatureGate` desabilitada por padrão e
  configurada com o **ID canônico** da Community BraFurries. Não inferir pelo nome.
- Candidato deve autenticar e ser membro **ACTIVE** conforme
  `CommunityAuthorizationService` / `CommunityMembershipStatusResolver`
  (Portaria, presença e banimento). Owner fora da Community não recebe bypass.
- Antes de gravar, a associação é verificada novamente sob lock da linha;
  dias mínimos (desde última entrada, se houver), VIP e parceiro são avaliados
  pelo backend a partir das regras **congeladas na versão publicada**.
- Só processos `ACTIVE/PUBLIC/OPEN` dentro da janela UTC aparecem para candidatos.
- A DTO pública traz somente nomes, perguntas, tipos, opções e limites:
  **nunca** gabaritos, pontuação, formulário da Staff, comentários ou decisões.
- O `termsHash` é o SHA-256 do JSON dos termos da versão publicada; a
  submissão exige o hash exato. Aceite fica persistido com timestamp e hash.
- O request aceita apenas respostas para as perguntas configuradas, valida
  required, tipos, limites, IDs de alternativas e consentimentos na API.
  O JSON persistido é limitado a 100000 caracteres.
- A chave única (Community, processo, usuário) previne duplicidade em
  concorrência. Respostas referenciam uma etapa instanciada por FK composta;
  JPA não cria DDL. A primeira etapa fica `AWAITING_REVIEW`, as demais `PENDING`.
- As respostas não são pontuadas nesta fatia. Enviar o formulário não
  aprova/reprova a pessoa nem publica resultados.

## Rotas

Para candidato ativo: `/user/me/communities/{communityId}/recruitment/open`
- GET `/`: lista processos públicos abertos dentro do prazo
- GET `/{processId}`: formulário público, termos e hash, status de elegibilidade
- POST `/{processId}/apply`: `{"termsHash":"<64 hex>","answers":{"motivation":"..."}}`;
  criado 201; duplicidade/termos defasados 409; entrada inválida 400.

Para Staff:
- PUT `/user/me/communities/{id}/recruitment/processes/{processId}/publication/terms`:
  requer `RECRUITMENT_MANAGE_PROCESSES`, expectedRevision e termsText (20..12000);
  somente versão DRAFT; salvar avança draftRevision.
- POST `/user/me/communities/{id}/recruitment/processes/{processId}/intake/open`:
  requer `RECRUITMENT_PUBLISH_PROCESSES`, expectedRevision e endsAt ISO UTC;
  somente processo ACTIVE/PRIVATE/CLOSED e versão PUBLISHED.
  Primeira etapa deve ser APPLICATION com formulário válido; termos válidos
  e elegibilidade configurada. Prazo entre 15 minutos e 90 dias.
  Abre `PUBLIC/OPEN` explicitamente. A UI não substitui a autorização.

## Limites intencionais da fatia
- Não inclui rascunho/resume de respostas, retries, avaliações, entrevista,
  recursos, publicação de notas, anti-cola ou etapa seguinte.
- Formulário de inscrição é a **primeira** etapa, com tipo APPLICATION; processos
  publicados sem essa etapa podem continuar privados, mas não abrir inscrições.
- Encerramento automático efetivo é por comparação UTC no GET/POST, mesmo sem
  job que altere enrollment_status fisicamente.
- A seleção pública não inclui status individual da candidatura nesta fatia.
- Nenhum novo dado candidato é retornado a terceiros; notificações futuras.

## Validações
- Database Flyway/MariaDB descartável com baseline 107, foundation 5 e
  answer table 1 (total 113), foreign keys e uniqueness.
- API suite Java + JDBC Testcontainers (SHA-pinned Database #14),
  testes de duplicação, integridade, prazos e respostas.
- CI e revisão do diff real antes de merge/deploy.
