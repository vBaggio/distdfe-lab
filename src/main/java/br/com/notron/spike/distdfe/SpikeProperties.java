package br.com.notron.spike.distdfe;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Configuração do spike. Certificado, senha, CNPJ e UF vêm de variáveis de ambiente
 * (ver README) e nunca do código.
 *
 * @param certPath   caminho do certificado A1 (.pfx/.p12)
 * @param certSenha  senha do certificado
 * @param cnpj       CNPJ do interessado, 14 dígitos
 * @param cufAutor   código IBGE da UF do interessado (ex.: 35 = SP, 41 = PR)
 * @param ambiente   1 = produção, 2 = homologação
 * @param dataDir    diretório onde ficam estado, índice e XMLs
 */
@ConfigurationProperties("spike")
public record SpikeProperties(
        Path certPath,
        String certSenha,
        String cnpj,
        String cufAutor,
        int ambiente,
        Path dataDir) {

    public SpikeProperties {
        if (ambiente == 0) ambiente = 1;
        if (dataDir == null) dataDir = Path.of("dados");
    }

    public String urlServico() {
        return ambiente == 1
                ? "https://www1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx"
                : "https://hom1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx";
    }
}
