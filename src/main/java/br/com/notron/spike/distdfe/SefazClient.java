package br.com.notron.spike.distdfe;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.*;

public final class SefazClient implements SefazTransport, AutoCloseable {
    private final Supplier<SpikeProperties> config;
    private HttpClient client;
    private final URI endpoint;
    public SefazClient(SpikeProperties config) { this(() -> config); }
    public SefazClient(Supplier<SpikeProperties> config) { this.config=config; this.endpoint=URI.create(config.get().urlServico()); }
    SefazClient(SpikeProperties config, HttpClient client, URI endpoint) {
        this.config=() -> config; this.client=client; this.endpoint=endpoint;
    }
    @Override public synchronized void preparar() throws Exception {
        SpikeProperties config = this.config.get();
        if (!config.pendencia().isEmpty()) throw new IOException(config.pendencia());
        char[] password = config.certSenha().toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(config.certPath())) { store.load(in,password); }
            int keys=0;
            var aliases=store.aliases();
            while (aliases.hasMoreElements()) {
                String alias=aliases.nextElement();
                if (store.isKeyEntry(alias)) {
                    keys++;
                    if (!(store.getCertificate(alias) instanceof X509Certificate cert)) throw new IOException();
                    cert.checkValidity();
                }
            }
            if (keys != 1) throw new IOException();
            var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store,password);
            var ssl=SSLContext.getInstance("TLS");
            ssl.init(managers.getKeyManagers(),null,null);
            if (client != null) client.close();
            client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        } catch (Exception e) {
            throw new IOException("Não foi possível validar o PFX: confira senha, validade e uma única chave privada.");
        } finally { Arrays.fill(password,'\0'); }
    }
    @Override public byte[] consultar(String ultNSU) throws Exception {
        if (client == null) throw new IOException("Certificado ainda não validado.");
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe/nfeDistDFeInteresse\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope(ultNSU))).build();
        // Bound the entire exchange, including a stalled response body, and cancel on timeout.
        var future=client.sendAsync(request, info -> new LimitedBodySubscriber(DistDfeParser.MAX_RESPONSE));
        HttpResponse<byte[]> response;
        try { response=future.get(60,TimeUnit.SECONDS); }
        catch (TimeoutException e) {future.cancel(true);throw new HttpTimeoutException("Tempo de resposta excedido.");}
        catch (InterruptedException e) {future.cancel(true);throw e;}
        catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IOException("Falha na comunicação com a SEFAZ.");
        }
        if (response.statusCode()!=200) throw new IOException("SEFAZ retornou HTTP " + response.statusCode() + ".");
        return response.body();
    }
    @Override public synchronized void close() {if (client != null) client.close();}
    String envelope(String nsu) {
        if (!DistDfeModels.nsuValido(nsu)) throw new IllegalArgumentException("NSU inválido.");
        SpikeProperties config = this.config.get();
        if (config.cnpj()==null || !config.cnpj().matches("[0-9]{14}") || config.cufAutor()==null || !config.cufAutor().matches("[0-9]{2}"))
            throw new IllegalArgumentException("CNPJ ou UF inválido.");
        return """
            <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope"><soap12:Body>
            <nfeDistDFeInteresse xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe"><nfeDadosMsg>
            <distDFeInt xmlns="http://www.portalfiscal.inf.br/nfe" versao="1.01"><tpAmb>%d</tpAmb><cUFAutor>%s</cUFAutor><CNPJ>%s</CNPJ><distNSU><ultNSU>%s</ultNSU></distNSU></distDFeInt>
            </nfeDadosMsg></nfeDistDFeInteresse></soap12:Body></soap12:Envelope>
            """.formatted(config.ambiente(),config.cufAutor(),config.cnpj(),nsu);
    }
}
