# DistDFe Lab — implementação

Atualizado em 26/09/2026. Execução direta no harness, sem Superpowers.
Base: [SPEC.md](SPEC.md). Consulta somente manual; sem agendamento.

## Etapas

- [x] **1. Configuração, PFX e transporte.** PKCS12, validação local, SOAP 1.2,
  HttpClient com TLS mútuo e truststore padrão. Timeout total de 60 s, conexão de
  15 s, respostas limitadas em tamanho, sem retry implementado pela aplicação.
  Validado por `SefazClientTest`, com servidor local e PFX sintético.
- [x] **2. Parser.** XML seguro e namespace-aware, base64/GZIP, bytes preservados,
  resumos, notas completas, eventos e schemas desconhecidos seguros.
  Validado por `DistDfeParserTest`, com fixtures sintéticas.
- [x] **3. Persistência.** Lote pendente durável antes do cursor, substituições
  atômicas, índice CSV/JSON, histórico e lock do diretório. Recuperação idempotente;
  identidade CNPJ/ambiente protege contra reutilização de cursor.
  Validado por `FileStoreTest`, incluindo falha de escrita depois de avançar o NSU.
- [x] **4. Coordenação.** Uma execução por vez; até 30 chamadas sequenciais.
  Bloqueio enquanto tempo decorrido <=60 minutos; liberação somente depois disso.
  Horário persistido após erros e reinícios; recusa não renova a espera.
  Validado por `ConsultaServiceTest`, usando relógio controlado e transporte falso.
- [x] **5. API e interface.** HTTP local, 202/409/422, validação de PFX sem rede,
  estado, histórico, índice, totais por schema e filtro. Dados tratados como texto.
  Validado por `ApiIntegrationTest` e testes DOM (`npm test`).
- [x] **6. Entrega local.** README com configuração via Bash, build/JAR e roteiro de
  teste real. Evidências e limites em [docs/VALIDACAO.md](docs/VALIDACAO.md).
- [ ] **7. Teste real com o usuário.** Disponibilizar PFX/configuração localmente,
  verificar se outro sistema usa o CNPJ, validar o arquivo e disparar a coleta.
  Confirmar TLS, autorização, cStat 137/138 e bloqueio posterior. Fora da validação
  automatizada; nenhuma consulta real foi feita durante a implementação.

## Decisões fechadas

- O horário normal é o término da última chamada, inclusive em erro; queda durante
  chamada exige espera conservadora a partir da reabertura.
- Cursor inválido/regressivo não é adotado silenciosamente. Lote inconsistente
  permanece em `pendentes/` e bloqueia novas consultas até diagnóstico.
- O mesmo CNPJ pode ser consultado por outro sistema: o lock local não o coordena.
- Sem manifestação e sem consulta por chave nesta versão. Não prometer retroativo.
- JUnit/jsdom são dependências de teste; execução da aplicação requer somente Java.
- O projeto agora é versionado em https://github.com/vBaggio/distdfe-lab.
