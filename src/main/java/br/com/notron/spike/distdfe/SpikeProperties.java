package br.com.notron.spike.distdfe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("spike")
public record SpikeProperties(Path certPath, String certSenha, String cnpj, String cufAutor,
                              int ambiente, Path dataDir) {
    static final Set<String> UFS = Set.of("11","12","13","14","15","16","17","21","22",
            "23","24","25","26","27","28","29","31","32","33","35","41","42","43","50","51","52","53");
    public SpikeProperties {
        if (ambiente == 0) ambiente = 1;
        if (ambiente != 1 && ambiente != 2) throw new IllegalArgumentException("SPIKE_AMBIENTE deve ser 1 ou 2.");
        if (dataDir == null) dataDir = Path.of("dados");
    }
    public String pendencia() {
        if (certPath == null || !Files.isRegularFile(certPath) || !Files.isReadable(certPath))
            return "Configure SPIKE_CERT_PATH com um arquivo PFX/PKCS12 legível.";
        if (certSenha == null || certSenha.isEmpty()) return "Configure SPIKE_CERT_SENHA.";
        if (cnpj == null || !cnpj.matches("[0-9]{14}")) return "Configure SPIKE_CNPJ com 14 dígitos.";
        if (!UFS.contains(cufAutor == null ? "" : cufAutor)) return "Configure SPIKE_CUF_AUTOR com uma UF válida.";
        return "";
    }
    public String urlServico() {
        return "https://" + (ambiente == 1 ? "www1" : "hom1")
                + ".nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx";
    }
    @Override public String toString() { return "SpikeProperties[configuração protegida]"; }
}
