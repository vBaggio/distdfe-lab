# Validação da entrega — 26/09/2026

Escopo: implementação local pronta para teste com PFX real. Nenhuma consulta ao
serviço de distribuição da SEFAZ foi executada durante o desenvolvimento.

## Etapas verificadas

| Etapa | Evidência | Resultado |
| --- | --- | --- |
| Transporte/PFX | `SefazClientTest` — 2 testes | SOAP 1.2 via servidor HTTP local, HTTP de erro, PFX sintético legível e senha incorreta rejeitada |
| Parser | `DistDfeParserTest` — 4 testes | Tipos de documentos, bytes preservados, campos ausentes, 137/138/656/outros, XXE, SOAP Fault, base64/GZIP e nomes de schema inválidos |
| Persistência | `FileStoreTest` — 6 testes | CSV, reabertura, identidade, lock, estado perdido/corrompido, recuperação idempotente e falha de escrita depois de avançar cursor |
| Coordenação | `ConsultaServiceTest` — 6 testes | Limite exato de 60 minutos e +1 ns, reinício, concorrência, timeout, 30 lotes, cursor sem avanço e ausência de certificado |
| API integrada | `ApiIntegrationTest` — 1 teste de fluxo | Aplicação HTTP real com transporte falso, página/JS, validação sem consulta, 202/409, índice e recusa de origem externa |
| Interface | `npm test` — 4 testes DOM | Botão manual, configuração ausente, cooldown, filtro, dados tratados como texto e perda de conexão |
| Empacotamento | `./gradlew test bootJar` | BUILD SUCCESSFUL; JAR executável em `build/libs/distdfe-lab-0.0.1.jar` |
| JAR sem configuração | HTTP local em porta temporária | Estado configurado=false e POST de consulta com 422; sem última consulta registrada |
| JAR com PFX sintético via variáveis de ambiente | POST `/api/certificado/validar` | 200; estado configurado=true, última consulta nula e histórico vazio |

**Total local: 19 testes Java e 4 testes de interface aprovados.** O certificado
usado nos testes foi gerado pelo keytool em diretório temporário; não é certificado
de empresa e não foi publicado. Os XMLs em `src/test/resources/fixtures/` são sintéticos.

O workflow `.github/workflows/ci.yml` executa os mesmos testes e o empacotamento
em cada push/PR, com JDK 25 e Node 22. O estado remoto deve ser consultado no GitHub;
os resultados da tabela acima são da execução local.

## Decisões verificadas

- Sem scheduler e sem consulta na inicialização.
- POST recusado não altera horário e não chama o transporte.
- A referência de tempo é o término da última chamada; paginação na mesma execução
  pode continuar imediatamente. A nova execução exige tempo estritamente >60 minutos.
- Falha com chamada em andamento reinicia uma espera conservadora na recuperação.
- Lote durável precede atualização do cursor. Falha de parse/materialização preserva
  a resposta e bloqueia a coleta até recuperação/diagnóstico.
- A senha não faz parte do SOAP, API, índice ou histórico. `SpikeProperties.toString()`
  é redigido e erros do PFX não propagam a mensagem original com dados sensíveis.

## Limites e teste real pendente

- **TLS com a SEFAZ, autorização do CNPJ e aceitação do SOAP/schema** dependem do
  certificado real e do serviço remoto; os testes locais não comprovam essa integração.
- O leitor local do PFX verifica senha, validade do certificado e chave privada;
  não comprova revogação, vínculo do CNPJ ou confiança da SEFAZ na cadeia.
- O navegador de automação não estava disponível nesta sessão. Comportamento da
  tela foi verificado via DOM e endpoints HTTP; inspeção visual em navegador real
  fica para a abertura local pelo usuário.
- A v1 valida o CNPJ interessado como 14 dígitos, conforme o escopo original.
  Não implementa suporte de entrada a CNPJ alfanumérico. Campos dos documentos
  recebidos são preservados como texto.
- A listagem oficial indica revisão 1.40 da NT 2014.002; o download oficial apresentou
  redirecionamento circular nesta sessão. Regras de consumo foram conferidas no
  [informe oficial](https://www.nfe.fazenda.gov.br/portal/informe.aspx?AspxAutoDetectCookieSupport=1&Informe=0cu%2FyBLKrCs%3D&ehCTG=false).
  O [documento oficial da revisão 1.40](https://www.nfe.fazenda.gov.br/portal/exibirArquivo.aspx?conteudo=uWO2d%2FgTuWg%3D)
  permanece uma referência para eventual divergência no teste real.

O roteiro de configuração e da primeira execução está no [README](../README.md).
Não apague o estado para repetir testes reais e alinhe o uso com outros consumidores
do mesmo CNPJ antes da consulta.
