package br.com.notron.spike.distdfe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Configuração feita uma vez pela tela: o PFX é copiado para o diretório de dados, o CNPJ é lido do
 * próprio certificado e senha + UF ficam num arquivo local com permissão só do dono. Variáveis de
 * ambiente completas, se existirem, têm precedência.
 */
public final class ConfiguracaoLocal {
    public record Resumo(boolean configurado, String cnpj, String titular, String uf, String origem) {}

    private static final Pattern CNPJ_NO_CN = Pattern.compile(":(\\d{14})\\b");
    private static final Pattern QUATORZE_DIGITOS = Pattern.compile("(\\d{14})");
    // DER do OID 2.16.76.1.3.3 (CNPJ da pessoa jurídica no e-CNPJ ICP-Brasil).
    private static final byte[] OID_CNPJ = {0x06, 0x05, 0x60, 0x4C, 0x01, 0x03, 0x03};

    private final SpikeProperties ambiente;
    private final Path arquivo;
    private final Path pfx;
    private volatile SpikeProperties atual;
    private volatile String titular = "";

    public ConfiguracaoLocal(SpikeProperties ambiente) {
        this.ambiente = ambiente;
        this.arquivo = ambiente.dataDir().resolve("configuracao.properties");
        this.pfx = ambiente.dataDir().resolve("certificado.pfx");
        this.atual = carregar();
    }

    public SpikeProperties atual() { return atual; }

    public Resumo resumo() {
        var c = atual;
        boolean ok = c.pendencia().isEmpty();
        String origem = !ok ? "nenhuma" : c == ambiente ? "variáveis de ambiente" : "tela";
        return new Resumo(ok, ok ? c.cnpj() : "", titular, ok ? c.cufAutor() : "", origem);
    }

    public synchronized Resumo salvar(byte[] bytes, String senha, String uf) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Selecione o arquivo do certificado (.pfx ou .p12).");
        if (senha == null || senha.isEmpty()) throw new IllegalArgumentException("Informe a senha do certificado.");
        if (!SpikeProperties.UFS.contains(uf == null ? "" : uf)) throw new IllegalArgumentException("Selecione uma UF válida.");
        X509Certificate cert = certificado(bytes, senha);
        String cnpj = extrairCnpj(cert);

        Files.createDirectories(ambiente.dataDir());
        gravarPrivado(pfx, bytes);
        var props = new Properties();
        props.setProperty("senha", senha);
        props.setProperty("cnpj", cnpj);
        props.setProperty("uf", uf);
        props.setProperty("titular", titular(cert));
        var out = new ByteArrayOutputStream();
        props.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "DistDFe Lab - configuracao local. NAO versionar.");
        gravarPrivado(arquivo, out.toByteArray());

        titular = titular(cert);
        atual = new SpikeProperties(pfx, senha, cnpj, uf, ambiente.ambiente(), ambiente.dataDir());
        return resumo();
    }

    private SpikeProperties carregar() {
        if (ambiente.pendencia().isEmpty()) return ambiente;
        if (!Files.isRegularFile(arquivo)) return ambiente;
        try (var in = new InputStreamReader(Files.newInputStream(arquivo), StandardCharsets.UTF_8)) {
            var p = new Properties();
            p.load(in);
            titular = p.getProperty("titular", "");
            return new SpikeProperties(pfx, p.getProperty("senha"), p.getProperty("cnpj"), p.getProperty("uf"),
                    ambiente.ambiente(), ambiente.dataDir());
        } catch (IOException e) {
            return ambiente;
        }
    }

    static X509Certificate certificado(byte[] bytes, String senha) {
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(bytes), senha.toCharArray());
            for (var aliases = store.aliases(); aliases.hasMoreElements(); ) {
                String alias = aliases.nextElement();
                if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate c) {
                    c.checkValidity();
                    return c;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Não foi possível abrir o certificado: confira o arquivo, a senha e a validade.");
        }
        throw new IllegalArgumentException("O arquivo não contém um certificado com chave privada.");
    }

    static String extrairCnpj(X509Certificate cert) {
        try {
            var sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (Integer.valueOf(0).equals(san.get(0)) && san.get(1) instanceof byte[] der) {
                        int i = indexOf(der, OID_CNPJ);
                        if (i >= 0) {
                            var m = QUATORZE_DIGITOS.matcher(new String(der, i + OID_CNPJ.length,
                                    der.length - i - OID_CNPJ.length, StandardCharsets.ISO_8859_1));
                            if (m.find()) return m.group(1);
                        }
                    }
                }
            }
        } catch (Exception ignored) { /* cai para o CN */ }
        var m = CNPJ_NO_CN.matcher(cert.getSubjectX500Principal().getName());
        if (m.find()) return m.group(1);
        throw new IllegalArgumentException("Não encontrei o CNPJ no certificado. É um e-CNPJ?");
    }

    private static String titular(X509Certificate cert) {
        var m = Pattern.compile("CN=([^:,]+)").matcher(cert.getSubjectX500Principal().getName());
        return m.find() ? m.group(1).trim() : "";
    }

    private static int indexOf(byte[] data, byte[] target) {
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (data[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static void gravarPrivado(Path destino, byte[] conteudo) throws IOException {
        Path tmp = Files.createTempFile(destino.getParent(), ".tmp-", "");
        try {
            try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* sistema sem POSIX */ }
            Files.write(tmp, conteudo);
            Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
