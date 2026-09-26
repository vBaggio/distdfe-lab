# DistDFe Lab — especificação

Atualizada em 26/09/2026: consulta exclusivamente manual; nova execução permitida
somente após **mais de 60 minutos** desde a última chamada.

Protótipo **descartável** para coletar, em produção, os resumos de NF-e emitidas contra o CNPJ de
uma empresa. O dono do certificado autorizou o uso. Objetivo: montar um acervo real de chaves de
acesso, que depois servirá de base para baixar os XMLs completos e medir tamanho e compressão.
Não é código do Notron: vale simplicidade, não arquitetura.

## Contexto e motivação

O **Notron** é um SaaS B2B para escritórios de contabilidade: centraliza os documentos fiscais
eletrônicos (XML de NF-e e NFC-e) das empresas-clientes do escritório. Ele ainda está em fase de
arquitetura, e a decisão em aberto é **onde e como guardar os XMLs** (no PostgreSQL ou num object
storage). Um parecer técnico recomendou o Postgres no MVP, mas todos os números dele (tamanho médio
do XML, taxa de compressão) vieram de **XML sintético**. Falta dado real.

Este spike resolve isso em duas etapas:

1. **v1 (esta spec):** consultar manualmente o serviço oficial da SEFAZ (`NFeDistribuicaoDFe`, do
   Ambiente Nacional) com o certificado A1 da empresa, e coletar os **resumos** das NF-e emitidas
   contra o CNPJ dela, junto com as chaves de acesso. O resumo não traz o XML completo, e isso é
   esperado.
2. **v2 (futura):** para cada chave, registrar a Ciência da Operação e baixar o XML completo.

De quebra, o spike serve de aprendizado para o canal de captura automática que o Notron terá.

**Quem vai usar:** o próprio fundador, localmente, durante uma a duas semanas. Ele quer
**enxergar** o processo numa tela: o que foi consultado, o que voltou, o que foi gravado.

## Fatos do domínio que o agente precisa saber

- **O emitente não recebe as próprias notas pelo DistDFe.** Só chegam notas em que o CNPJ é
  destinatário (compras da empresa), mais eventos. Poucos documentos, ou nenhum, é um resultado
  possível e legítimo.
- **Sem manifestação do destinatário, o serviço entrega só o resumo** (`resNFe`): chave, emitente,
  data, valor, situação. É o esperado na v1.
- **Na primeira consulta (NSU zero),** não há garantia de retroativo. Para novos usuários,
  a geração de NSU começa no primeiro acesso; o retorno 137 é esperado. Há janela de
  disponibilidade de até ~90 dias para documentos existentes, não um arquivo permanente.
- **A SEFAZ pune consumo indevido** com o `cStat=656`, que bloqueia o CNPJ por 1 hora. As regras de
  consumo abaixo não são opcionais.
- **Tudo é produção real, com o CNPJ de uma empresa real.** Nada de testes que disparem consultas em
  loop contra a SEFAZ. Teste o parse com um XML de resposta fixo (fixture), não com chamadas reais.

## Escopo

**Dentro:** consultar o `NFeDistribuicaoDFe` por NSU, guardar tudo o que vier (resumos de NF-e,
resumos de eventos e o que mais aparecer), manter um índice das chaves e uma tela simples para
acompanhar.

**Fora (v1):** manifestação do destinatário (Ciência da Operação), consulta por chave, banco de
dados, autenticação, testes elaborados, Docker.

## Stack

Java 25 e Spring Boot 4.1 (só `spring-boot-starter-web`), Gradle com wrapper. O esqueleto já existe
nesta pasta (`build.gradle`, `settings.gradle`, wrapper, `SpikeApplication`, `SpikeProperties`):
use ou substitua. Sem banco: o estado fica em arquivos no `dataDir`.

## Configuração (variáveis de ambiente)

| Variável | Uso |
|---|---|
| `SPIKE_CERT_PATH` | caminho do certificado A1 (.pfx/.p12) |
| `SPIKE_CERT_SENHA` | senha do certificado |
| `SPIKE_CNPJ` | CNPJ do interessado (14 dígitos) |
| `SPIKE_CUF_AUTOR` | código IBGE da UF da empresa (ex.: 35 = SP, 41 = PR, 42 = SC, 43 = RS) |
| `SPIKE_AMBIENTE` | `1` produção (padrão), `2` homologação |
| `SPIKE_DATA_DIR` | diretório de dados (padrão `./dados`) |

**O certificado e a senha nunca entram no repositório nem nos logs.** O `.gitignore` já exclui
`*.pfx`, `*.p12`, `.env` e `dados/`.

## O serviço SEFAZ

- **Produção:** `https://www1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx`
- **Homologação:** `https://hom1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx`
- **TLS mútuo:** o certificado A1 (PKCS12) é o certificado de cliente, via `SSLContext` com
  `KeyManagerFactory`. O certificado do servidor fecha na GlobalSign Root R46, que já está no
  truststore padrão do JDK 25 (verificado), então não precisa de truststore customizado.
- **SOAP 1.2**, `POST` com
  `Content-Type: application/soap+xml; charset=utf-8; action="http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe/nfeDistDFeInteresse"`.
- **Corpo:**

```xml
<soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
  <soap12:Body>
    <nfeDistDFeInteresse xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe">
      <nfeDadosMsg>
        <distDFeInt xmlns="http://www.portalfiscal.inf.br/nfe" versao="1.01">
          <tpAmb>1</tpAmb>
          <cUFAutor>35</cUFAutor>
          <CNPJ>00000000000000</CNPJ>
          <distNSU><ultNSU>000000000000000</ultNSU></distNSU>
        </distDFeInt>
      </nfeDadosMsg>
    </nfeDistDFeInteresse>
  </soap12:Body>
</soap12:Envelope>
```

- **Resposta:** um `retDistDFeInt` com `cStat`, `xMotivo`, `dhResp`, `ultNSU`, `maxNSU` e
  `loteDistDFeInt/docZip`. Cada `docZip` tem os atributos `NSU` e `schema` (ex.: `resNFe_v1.01.xsd`,
  `resEvento_v1.01.xsd`, `procNFe_v4.00.xsd`, `procEventoNFe_v1.00.xsd`), e o conteúdo é
  **base64 de GZIP** do XML.
- Faça o parse do XML com uma fábrica segura (`disallow-doctype-decl` ligado, sem entidades
  externas).

## Regras de consumo — obrigatórias

Violar estas regras gera `cStat=656` (consumo indevido) e **bloqueia o CNPJ por 1 hora**.

1. **O `ultNSU` persiste entre execuções.** Começa em `000000000000000`. Primeiro preserve
   a resposta em um lote pendente durável, depois grave o cursor válido retornado e materialize
   documentos e índice. Isso permite recuperação após queda sem nova consulta à SEFAZ.
   Respostas de erro sem cursor não apagam o cursor anterior; 656 pode trazer um cursor
   de recuperação, adotado somente se válido e não regressivo.
2. **Laço de uma execução:** consulta com o `ultNSU` atual.
   - `cStat=138` (documentos localizados): salva os docs, atualiza o `ultNSU` e, se
     `ultNSU < maxNSU`, consulta de novo em seguida. Se `ultNSU == maxNSU`, para.
   - `cStat=137` (nenhum documento): para.
   - `cStat=656`: para imediatamente.
   - Qualquer outro `cStat`: para e registra.
   - Trava de segurança: no máximo 30 consultas por execução.
3. **Em cada chamada**, persista o início antes do envio e, ao concluir (inclusive com erro),
   registre `ultimaConsulta` e `proximaConsultaPermitida = ultimaConsulta + 60 minutos`.
   Novas execuções são recusadas enquanto `agora <= proximaConsultaPermitida`. Lotes
   consecutivos dentro da mesma execução não esperam esse intervalo. Cliques recusados
   não renovam o prazo. Após queda com chamada em andamento, aguarde mais de 60 minutos
   desde a reabertura, pois o término da chamada interrompida não é conhecido.
4. **Uma execução por vez.** Um disparo concorrente é recusado.
5. **Somente manual:** sem agendamento e sem consulta na inicialização. O usuário dispara
   pelo botão. Após mais de 60 dias sem uso do serviço, a geração de NSUs é interrompida
   e retomada na consulta seguinte, sem geração retroativa do período de interrupção.

## Armazenamento (em `dataDir`)

- `estado.properties`: `ultNSU`, `maxNSU`, `ultimaConsulta`, `proximaConsultaPermitida`, identidade
  CNPJ/ambiente e marcador de chamada em andamento.
- `pendentes/lote.json`: resposta bruta e metadados para recuperação local; removido após conclusão.
- `indice.json`: índice interno para leitura pela API, consistente com `indice.csv`.
- `execucoes.jsonl`: uma linha por consulta à SEFAZ (horário, NSU enviado, `cStat`, `xMotivo`,
  `ultNSU`, `maxNSU`, quantidade de docs).
- `xml/<schema-sem-versão>/<NSU>.xml`: cada documento **descompactado, exatamente como veio**, sem
  normalizar. Ex.: `xml/resNFe/000000000001234.xml`.
- `indice.csv`: uma linha por documento, com NSU, schema, chave (`chNFe`), CNPJ/CPF do emitente,
  nome do emitente, `dhEmi`, `vNF`, `cSitNFe`, e, para eventos, `tpEvento` e `xEvento`, além do
  tamanho em bytes do XML. Campo ausente fica vazio.

## Tela (uma página HTML estática em `static/index.html`, com JS simples chamando a API)

- **Estado:** `ultNSU`, `maxNSU`, próxima consulta permitida, se há execução em andamento.
- **Botão "Consultar agora"**, desabilitado enquanto a janela estiver bloqueada ou faltar configuração.
- **Botão "Validar PFX localmente"**, sem acesso à SEFAZ. A aplicação abre sem certificado.
- **Últimas 20 execuções** (tabela).
- **Chaves coletadas** (tabela do índice: chave, emitente, data, valor, situação), com o total de
  documentos por schema.
- **Visual:** sóbrio e monocromático (grafite `#16181B` sobre `#FAFAF9`), tabela densa e números
  com algarismos tabulares. Nada elaborado.

## API

- `GET /api/estado`: estado atual e últimas execuções
- `GET /api/documentos`: conteúdo do índice
- `POST /api/consultar`: dispara uma execução assíncrona; `202` ao aceitar, `409` se estiver bloqueada
  ou em andamento, `422` se faltar configuração ou o PFX não passar na validação local.
- `POST /api/certificado/validar`: valida o PFX localmente; `200` ou `422`, sem iniciar cooldown.
- Os POSTs exigem JSON; aplicação restrita a loopback e requisições de mesma origem.

## Critério de pronto

1. `./gradlew bootRun` com as variáveis de ambiente sobe a aplicação em `http://localhost:8080`.
2. A primeira consulta em produção retorna `138` ou `137` (e não erro de TLS ou de schema).
   Com `138`, documentos aparecem em `dados/xml/` e na tela. Com `137`, ausência de documentos
   é legítima e estado/histórico são atualizados. Este item depende do teste real com PFX.
3. Uma segunda tentativa logo em seguida é **recusada pela aplicação** (janela de 1 h), sem chegar a
   ir à SEFAZ.
4. O certificado e a senha não aparecem em nenhum log nem arquivo gerado.
5. Um `README.md` curto explica como rodar.

## Próximo passo (fora da v1, só para não fechar a porta)

Uma v2 vai registrar a Ciência da Operação (evento 210210, no serviço `RecepcaoEvento`) para as
chaves de `resNFe`, e então baixar os XMLs completos (`procNFe`), dentro da janela de ~90 dias.
Mantenha o índice por chave para facilitar isso.
