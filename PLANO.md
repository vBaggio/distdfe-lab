# Preparação do spike DistDFe v1

Revisão em 25/09/2026. Base: `SPEC.md` e esqueleto existente.
Status: planejamento preparado; implementação e consultas reais aguardam o sinal do usuário.
Execução direta no harness, sem Superpowers.

## Direção

Manter o protótipo descartável: uma aplicação local, Java 25, Spring Boot 4.1,
arquivos e uma página estática. Sem banco, Docker, autenticação, manifestação
ou consulta por chave. O objetivo é obter dados reais, não antecipar a arquitetura do Notron.

## Pendências e ajustes propostos

1. **NSU e durabilidade — resolver antes de implementar a persistência.**
   A regra de salvar o NSU antes de qualquer outra coisa permite perder documentos
   se houver queda antes de gravar os XMLs. Proposta: gravar a resposta bruta em
   `pendentes/` de forma durável, persistir o estado retornado, depois materializar
   XMLs e índice. Na inicialização, recuperar pendências localmente antes de consultar.
   É uma exceção explícita à ordem literal da spec; mantém o cursor recebido sem
   depender de nova consulta para recuperar o lote. Escritas por arquivo temporário
   e substituição atômica; falha de armazenamento interrompe a coleta.

2. **Primeira consulta — não prometer retroativo.**
   A NT 2014.002 consultada descreve geração de NSU a partir do primeiro acesso para
   novos usuários, sem geração retroativa, e retorno inicial 137. A mesma ressalva
   vale para retomada após mais de 60 dias de inatividade. Portanto, a afirmação de
   “últimos ~90 dias” não deve ser uma garantia para um CNPJ novo no serviço.
   O portal indexado lista v1.40 de 03/07/2026; conferir seu texto integral antes da
   integração real, pois não foi possível abrir a listagem (redirecionamento circular).

3. **Outro consumidor do mesmo CNPJ — confirmar antes de produção.**
   Verificar se contador, ERP ou outra ferramenta já consulta DistDFe. A trava local
   não coordena essas aplicações. Alinhar cursor e consumo conforme a NT vigente;
   não presumir que começar com NSU zero será adequado nesse cenário.

4. **Credenciais e dados — necessários só para validação real.**
   Disponibilizar localmente certificado A1 válido, senha, CNPJ e UF pelas variáveis
   da spec. Não copiar senha para este documento ou chat. Não foram inspecionados
   certificados nem valores de variáveis sensíveis nesta revisão.

5. **Critério de pronto — separar 137 e 138.**
   Com 138, verificar arquivos e índice dos documentos retornados. Com 137, aceitar
   a ausência de documentos e verificar histórico, estado e bloqueio de 61 minutos.
   TLS e aceitação do schema só ficam comprovados por integração real.

## Decisões técnicas propostas

- Agendamento: `SPIKE_CRON=0 0 9 * * *` e `SPIKE_ZONE=America/Sao_Paulo`.
  Sem consulta automática na inicialização; agendamento só funciona com o processo aberto.
- HTTP local: bind em `127.0.0.1:8080`; `POST /api/consultar` retorna 202 ao aceitar
  execução assíncrona, 409 se ocupada/bloqueada. Recusas não renovam o bloqueio.
- Um coordenador atende botão e agendador. Trava em memória e lock do `dataDir`
  impedem execuções e processos concorrentes sobre o mesmo estado.
- Persistir marcador de execução antes da primeira chamada. Se houver queda, a
  reinicialização aplica espera conservadora de 61 minutos e recupera os dados locais.
  Encerramento normal ou por erro persiste 61 minutos a partir do encerramento.
- Timeout de conexão de 15 s e de requisição de 60 s, sem retry automático.
  Erros HTTP, TLS, SOAP, XML e disco interrompem a execução; histórico distingue
  erro local de `cStat`. Nunca inventar NSU/cStat ausente ou regredir cursor com erro.
- Máximo de 30 chamadas por execução, continuação apenas com 138 e cursor crescente
  menor que `maxNSU`. Cursor incoerente interrompe para diagnóstico.
- Associar o diretório ao CNPJ e ambiente; recusar reutilização com identidade diferente.
  Estado corrompido bloqueia consultas, sem reinicializar silenciosamente com NSU zero.
- XML descompactado salvo como bytes, sem serializar novamente. Parser seguro e
  namespace-aware; `schema` nunca vira caminho sem validação. Schema desconhecido
  é armazenado e indexado com os metadados disponíveis.
- Índice idempotente por NSU no diretório de uma identidade; chave é campo pesquisável,
  não identificador único de documento, pois a mesma chave pode ter vários eventos.
  Extrair campos pelo tipo de documento, sem confundir autor de evento com emitente da NF-e.
- CSV com escaping de aspas, vírgulas e quebras de linha; campos ausentes vazios.
  Histórico: uma linha por tentativa à SEFAZ; mostrar as últimas 20 consultas na tela.
- Não expor `SpikeProperties` na API ou logs: o `toString()` atual do record inclui
  `certSenha`. Redigir mensagens de erro sem senha/conteúdo PKCS12. Dados fiscais
  ficam apenas no diretório configurado; orientar sua exclusão do Git se personalizado.

## Sequência de implementação após o sinal

1. **Configuração e transporte.** Ajustar `SpikeProperties.java`; criar
   `SefazClient.java` com `HttpClient`, PKCS12 e `SSLContext`, mantendo o truststore
   padrão. Validar configuração e montar SOAP 1.2 conforme spec. Nenhuma consulta no startup.
2. **Parse e fixtures.** Criar `DistDfeParser.java` e modelos simples em
   `DistDfeModels.java`. Fixtures sintéticas de SOAP 137/138/656, resNFe, resEvento,
   procNFe, procEventoNFe e schema desconhecido em `src/test/resources/fixtures/`.
3. **Arquivos.** Criar `FileStore.java`: estado, lote pendente, XMLs, índice e histórico.
   Gravar arquivos atomicamente e recuperar lote pendente sem acessar a SEFAZ.
4. **Coordenação.** Criar `ConsultaService.java`, com relógio injetável, trava,
   limites, cooldown persistente e execução assíncrona; `ConsultaScheduler.java`
   usa exatamente o mesmo caminho do botão.
5. **API e tela.** Criar `ApiController.java`, `src/main/resources/application.properties`
   e `src/main/resources/static/index.html`. Estado inclui execução em andamento,
   cursor, horário permitido e histórico. Documentos incluem índice e totais por schema.
   JS usa `textContent` para dados fiscais; botão respeita estado informado pelo servidor.
   Visual e colunas seguem a spec.
6. **Entrega.** Criar `README.md`, executar testes offline em relação à SEFAZ e
   validar UI localmente. Validação real apenas com configuração pronta e no momento
   autorizado pelo usuário, respeitando a janela; sem chamadas reais em testes.

Os arquivos Java novos ficam em `src/main/java/br/com/notron/spike/distdfe/`.
Testes correspondentes ficam em `src/test/java/br/com/notron/spike/distdfe/`.
Dependências de teste podem usar JUnit; runtime permanece restrito à stack da spec.

## Verificação proporcional ao risco

- Parser: bytes preservados, extração por schema, campos ausentes, XXE rejeitado,
  base64/GZIP inválido e SOAP Fault.
- Armazenamento: recuperação após queda entre cursor e XMLs, reprocessamento sem
  duplicatas, CSV válido, estado corrompido e diretório de outra identidade recusados.
- Coordenador com cliente falso e relógio controlado: 138 paginado, 137, 656,
  outros códigos, timeout, máximo de 30 chamadas, cursor sem avanço, concorrência,
  bloqueio antes de 61 minutos e liberação no instante permitido, inclusive após restart.
- API: 202/409 e ausência de chamadas no cliente falso durante bloqueio; UI com tabela
  vazia e dados preenchidos. Nenhum teste depende de disponibilidade da SEFAZ.
- Comandos previstos: `./gradlew test` e `./gradlew bootJar`.

## Fontes consultadas

- [NT 2014.002 — seção 3.4, geração de NSU](https://www.nfe.fazenda.gov.br/portal/exibirArquivo.aspx?conteudo=U0LlsYVGBRU%3D).
- [Listagem oficial de NTs — conferir versão vigente](https://hom.nfe.fazenda.gov.br/PORTAL/listaConteudo.aspx?tipoConteudo=04BIflQt1aY%3D).
- [Lançamento oficial do Spring Boot 4.1.0](https://spring.io/blog/2026/06/10/spring-boot-4/).

## Estado do ambiente

Java 25 instalado; wrapper configurado para Gradle 9.6.1. Há somente o esqueleto
da aplicação, sem testes. `./gradlew classes` executado com sucesso nesta revisão;
a tentativa offline inicial falhou por dependência ausente no cache, resolvida
pela execução normal. Não foi iniciada a aplicação nem consultada a SEFAZ.
A pasta não possui repositório Git; versionamento pode
ser inicializado no início da implementação, se desejado. `SPEC.md` foi preservada.
