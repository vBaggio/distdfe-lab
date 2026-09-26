# DistDFe Lab

Protótipo local para consultar o **NFeDistribuicaoDFe** do Ambiente Nacional da
NF-e, preservar documentos recebidos e acompanhar a coleta por NSU em uma tela.

**Implementado e testado com respostas simuladas. Pronto para o primeiro teste
com certificado real; a integração em produção ainda não foi validada.**

## O que faz

- Consulta **somente pelo botão**; sem agendamento ou chamada na inicialização.
- Usa certificado A1 PFX/PKCS12 para TLS mútuo, diretamente no Java/Linux.
- Busca lotes consecutivos por NSU, até alcançar o fim ou 30 chamadas por execução.
- Salva XMLs sem modificar seus bytes, índice CSV e histórico de consultas.
- Bloqueia novas execuções por **60 minutos ou menos** desde o término da última
  chamada; libera somente quando o tempo decorrido for **maior que 60 minutos**.
- Mantém cursor, horário e bloqueio após reinício. Cliques recusados não renovam a espera.
- Oferece validação local do PFX, sem consultar a SEFAZ.

O foco são resumos de NF-e destinadas à empresa e eventos; XMLs completos são
preservados se vierem no retorno. Manifestação e consulta por chave estão fora
desta versão. Não há garantia de acervo retroativo para um novo consumidor.

## Stack

Java 25, Spring Boot 4.1, Gradle Wrapper, HttpClient do JDK, SOAP 1.2 e arquivos
locais. Interface em HTML/CSS/JavaScript puro, sem build frontend. Ambiente de
referência: Fedora. Sem banco, Docker ou serviço externo para armazenar dados.

JUnit testa o backend; Node.js e jsdom são usados **somente nos testes da tela**.

## Rodar sem certificado

Instale JDK 25 e execute:

```bash
./gradlew bootRun
```

Abra **http://localhost:8080**. A tela abre com as consultas desabilitadas e informa
qual configuração falta. O primeiro build precisa de internet para dependências.
A aplicação escuta apenas em `127.0.0.1`; não é um serviço multiusuário.

## Preparar o teste real no Fedora

Guarde o PFX fora do projeto. No Bash, a sequência abaixo pede os valores sem
registrar a senha no histórico nem mostrá-la no terminal:

```bash
read -r -p 'Caminho absoluto do PFX: ' SPIKE_CERT_PATH
read -r -s -p 'Senha do PFX: ' SPIKE_CERT_SENHA
printf '\n'
read -r -p 'CNPJ (14 dígitos): ' SPIKE_CNPJ
read -r -p 'Código IBGE da UF (ex.: 35): ' SPIKE_CUF_AUTOR
export SPIKE_CERT_PATH SPIKE_CERT_SENHA SPIKE_CNPJ SPIKE_CUF_AUTOR
export SPIKE_AMBIENTE=1
export SPIKE_DATA_DIR="$PWD/dados"
./gradlew --no-daemon bootRun
```

Não é necessário converter o PFX ou importá-lo no Fedora. O certificado precisa
estar válido, ter uma única chave privada e senha não vazia. A validação local
confere leitura, validade e chave; autorização do CNPJ, cadeia aceita pela SEFAZ
e handshake real só ficam comprovados na consulta.

1. Confirme se contador/ERP já consulta o mesmo CNPJ. Coletores independentes
   precisam coordenar cursor e consumo; apenas alternar horários não basta.
2. Clique **Validar PFX localmente**. Essa ação não acessa a SEFAZ nem inicia o intervalo.
3. Confira o ambiente exibido e clique **Consultar agora**. Uma execução pode fazer
   várias chamadas para esgotar os lotes; não há repetição automática após erros.
4. Com `138`, confira os documentos na tela e em `dados/xml/`. Com `137`, ausência
   de documentos é legítima: confira histórico, cursor e horário registrado.
5. Uma nova execução imediata deve estar bloqueada. A API retorna `409` sem chamar
   a SEFAZ. O botão será liberado depois de mais de 60 minutos.

Ao terminar, encerre com `Ctrl+C` e execute `unset SPIKE_CERT_SENHA` no mesmo shell.
A aplicação não carrega `.env` automaticamente. Não envie PFX, senha ou dados reais
em issues, commits ou screenshots públicas.

| Variável | Uso / padrão |
| --- | --- |
| `SPIKE_CERT_PATH` | Caminho do PFX/P12 local |
| `SPIKE_CERT_SENHA` | Senha, nunca devolvida na API |
| `SPIKE_CNPJ` | CNPJ numérico de 14 dígitos do interessado nesta v1 |
| `SPIKE_CUF_AUTOR` | Código IBGE da UF da empresa |
| `SPIKE_AMBIENTE` | `1` produção (padrão), `2` homologação |
| `SPIKE_DATA_DIR` | `./dados` por padrão; diretório exclusivo do CNPJ/ambiente |

## Armazenamento e recuperação

| Arquivo | Conteúdo |
| --- | --- |
| `estado.properties` | Identidade, cursores, última chamada e marcador de interrupção |
| `execucoes.jsonl` | Uma entrada por chamada; recuperação de interrupção gera aviso adicional |
| `xml/<schema-sem-versão>/<NSU>.xml` | Bytes originais do documento descompactado |
| `indice.csv` | Campos de cada documento, incluindo tamanho em bytes |
| `indice.json` | Índice interno usado pela API; não editar separadamente do CSV |
| `pendentes/lote.json` | Resposta recebida aguardando materialização, removida após conclusão |

O lote é gravado antes de avançar o cursor. Escritas usam substituição atômica e
sincronização em disco. Após falha, a aplicação reaplica o lote pendente sem chamar
a SEFAZ. Se a interrupção ocorreu durante uma chamada, aplica espera conservadora
de 60 minutos a partir da reabertura, pois o horário de conclusão é desconhecido.

Estado corrompido, lote impossível de processar ou conflito de identidade bloqueiam
a coleta; não são corrigidos apagando o cursor. Preserve o diretório e diagnostique
o problema. Faça backup do diretório completo com a aplicação parada. Não exclua
`dados/` para contornar o intervalo. A trava de arquivos só coordena processos
usando o mesmo diretório, não outros softwares ou computadores.

A pasta de dados recebe permissão `700` no Fedora. `.gitignore` exclui `dados/`,
certificados e arquivos de ambiente; se escolher outro diretório dentro do projeto,
adicione-o ao `.gitignore`. XMLs e índices contêm informações fiscais.

## API local

| Método e rota | Resultado |
| --- | --- |
| `GET /api/estado` | Cursores, horário, disponibilidade e últimas 20 chamadas |
| `GET /api/documentos` | Índice e totais por schema |
| `POST /api/certificado/validar` | `200` se o PFX passar na verificação local |
| `POST /api/consultar` | `202` ao aceitar; `409` se bloqueada/ocupada; `422` se configuração inválida |

Os POSTs exigem `Content-Type: application/json` (corpo `{}`). Origens externas
são recusadas. `proximaConsultaPermitida` é o limite **exclusivo**: só é permitido
consultar quando `agora > proximaConsultaPermitida`. Datas são retornadas em UTC;
a tela usa o fuso do navegador. Uma resposta `202` significa execução aceita,
não sucesso na SEFAZ; acompanhe o resultado em `/api/estado`.

## Validar e empacotar

```bash
./gradlew test bootJar
npm ci --ignore-scripts
npm test
```

Os testes não consultam a SEFAZ: usam XMLs sintéticos, relógio controlado, servidor
HTTP local e PFX sintético temporário. Para executar o JAR sem Gradle:

```bash
java -jar build/libs/distdfe-lab-0.0.1.jar
```

## Documentação

- [Especificação atualizada](SPEC.md)
- [Etapas de implementação](PLANO.md)
- [Registro das validações e limites](docs/VALIDACAO.md)

Este é um experimento de coleta, não um sistema fiscal de produção. O teste real
confirmará TLS, autorização do interessado e compatibilidade da resposta do serviço.
