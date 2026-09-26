package br.com.notron.spike.distdfe;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiIntegrationTest.Fakes.class)
@DirtiesContext
class ApiIntegrationTest {
    static Path root;
    static {try {root=Files.createTempDirectory("distdfe-api-test-");Files.writeString(root.resolve("fake.pfx"),"local fixture");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spike.data-dir",()->root.resolve("data").toString());r.add("spike.cert-path",()->root.resolve("fake.pfx").toString());
        r.add("spike.cert-senha",()->"must-not-appear");r.add("spike.cnpj",()->"12345678000195");r.add("spike.cuf-autor",()->"35");
    }
    @TestConfiguration static class Fakes {
        @Bean @Primary FakeTransport fakeTransport() {return new FakeTransport();}
    }
    static class FakeTransport implements SefazTransport {
        final AtomicInteger calls=new AtomicInteger();
        public void preparar() {}
        public byte[] consultar(String nsu) throws Exception {calls.incrementAndGet();return DistDfeParserTest.fixture("resposta-138.xml");}
    }
    @Autowired Environment env;
    @Autowired FakeTransport transport;
    @Autowired ConsultaService service;
    final HttpClient http=HttpClient.newHttpClient();
    HttpResponse<String> request(String path,boolean post,String origin) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+path));
        if(post) b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if(origin!=null)b.header("Origin",origin);
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void entregaTelaValidacaoLocalConsultaE409SemVazarSegredos() throws Exception {
        assertEquals(0,transport.calls.get());
        var page=request("/",false,null);assertEquals(200,page.statusCode());assertTrue(page.body().contains("Consultar agora"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals(200,request("/app.js",false,null).statusCode());
        assertEquals(200,request("/api/certificado/validar",true,null).statusCode());assertEquals(0,transport.calls.get());
        assertEquals(403,request("/api/consultar",true,"https://example.com").statusCode());assertEquals(0,transport.calls.get());
        assertEquals(202,request("/api/consultar",true,null).statusCode()); ConsultaServiceTest.await(service);
        assertEquals(409,request("/api/consultar",true,null).statusCode());assertEquals(1,transport.calls.get());
        var state=request("/api/estado",false,null);assertEquals(200,state.statusCode());
        assertTrue(state.body().contains("000000000000005"));assertFalse(state.body().contains("must-not-appear"));
        var docs=request("/api/documentos",false,null);assertEquals(200,docs.statusCode());
        assertTrue(docs.body().contains("resNFe_v1.01.xsd"));assertTrue(docs.body().contains("\"total\":5"));
    }
}
