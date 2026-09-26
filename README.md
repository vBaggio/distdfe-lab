# DistDFe Lab

Laboratório de integração com o serviço **NFeDistribuicaoDFe** do Ambiente Nacional
da NF-e, usando Java e Spring Boot.

O objetivo é estudar a coleta incremental por NSU e reunir uma amostra real de
documentos fiscais para avaliar armazenamento e compressão. O projeto também
documenta decisões técnicas e limitações encontradas durante a integração.

**Status:** especificação e planejamento disponíveis; esqueleto Java compilável.
O coletor, a API e a interface ainda não foram implementados. Não há validação
real com a SEFAZ nem suíte de testes neste estágio.

## Escopo da primeira versão

- Consultar documentos por NSU com certificado A1 e TLS mútuo.
- Preservar XMLs recebidos e manter índice CSV e histórico JSONL.
- Persistir o cursor e controlar concorrência e intervalos entre consultas.
- Exibir estado, histórico e documentos em uma interface local.
- Validar comportamento com respostas simuladas, sem consultar a SEFAZ nos testes.

Manifestação do destinatário e download por chave ficam fora da primeira versão.
Um retorno sem documentos é um resultado legítimo; não há garantia de acervo
retroativo para um novo consumidor do serviço.

## Stack

| Componente | Tecnologia |
| --- | --- |
| Runtime | Java 25 |
| Aplicação web | Spring Boot 4.1 |
| Build | Gradle Wrapper |
| Integração planejada | HttpClient do JDK, SOAP 1.2, TLS mútuo com PKCS12 |
| Interface planejada | HTML, CSS e JavaScript puro |
| Persistência planejada | Arquivos locais: XML, CSV, JSONL e properties |

Execução local em Linux, com ambiente de desenvolvimento Fedora. Sem banco de
dados ou Docker.

## Executar o esqueleto

Com JDK 25 instalado:

```bash
./gradlew classes
./gradlew bootRun
```

O primeiro build requer acesso à internet para baixar dependências. O comando
`bootRun` inicia apenas o esqueleto Spring Boot na porta 8080; a tela e os endpoints
de coleta ainda não existem. Nenhum certificado é necessário para essa etapa.

## Certificado e dados locais

A integração real usará um certificado A1 `.pfx` ou `.p12`, lido diretamente pelo
Java no Linux, e as variáveis abaixo:

| Variável | Finalidade |
| --- | --- |
| `SPIKE_CERT_PATH` | Caminho local do certificado |
| `SPIKE_CERT_SENHA` | Senha do certificado |
| `SPIKE_CNPJ` | CNPJ interessado |
| `SPIKE_CUF_AUTOR` | Código IBGE da UF |
| `SPIKE_AMBIENTE` | `1` produção, padrão; `2` homologação |
| `SPIKE_DATA_DIR` | Diretório de dados, padrão `./dados` |

Certificados, senhas e documentos fiscais reais não devem ser publicados.
Mantenha o certificado fora do repositório. O `.gitignore` exclui certificados,
arquivos de ambiente e `dados/`; se usar outro diretório de coleta dentro do
projeto, inclua-o no `.gitignore` antes de versionar arquivos.

## Documentação

- [Especificação do experimento](SPEC.md)
- [Plano de implementação e pontos em aberto](PLANO.md)

O plano registra ajustes propostos à especificação, incluindo recuperação de
lotes após falhas e ressalvas sobre a primeira consulta. Este é um protótipo
experimental, não um componente pronto para uso em produção.
