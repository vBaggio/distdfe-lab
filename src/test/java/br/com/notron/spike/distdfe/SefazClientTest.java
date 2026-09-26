package br.com.notron.spike.distdfe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.net.*;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SefazClientTest {
    @TempDir Path dir;
    SpikeProperties config(Path path,String senha) {return new SpikeProperties(path,senha,"12345678000195","35",1,dir);}
    @Test void enviaSoap12SemSegredosETrataHttp() throws Exception {
        var body=new AtomicReference<String>(); var type=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            body.set(new String(e.getRequestBody().readAllBytes())); type.set(e.getRequestHeaders().getFirst("Content-Type"));
            byte[] out=DistDfeParserTest.fixture("resposta-137.xml");
            e.sendResponseHeaders(200,out.length); e.getResponseBody().write(out); e.close();
        });
        server.createContext("/error",e->{e.sendResponseHeaders(503,-1);e.close();}); server.start();
        try {
            var cfg=config(dir.resolve("not-read.pfx"),"super-secret");
            var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var client=new SefazClient(cfg,HttpClient.newHttpClient(),uri);
            assertEquals("137",new DistDfeParser().parse(client.consultar(DistDfeModels.ZERO)).cStat());
            assertTrue(type.get().contains("application/soap+xml"));
            assertTrue(type.get().contains("nfeDistDFeInteresse"));
            assertTrue(body.get().contains("<ultNSU>000000000000000</ultNSU>"));
            assertFalse(body.get().contains("super-secret")); assertFalse(cfg.toString().contains("super-secret"));
            assertThrows(Exception.class,()->new SefazClient(cfg,HttpClient.newHttpClient(),uri.resolve("/error")).consultar(DistDfeModels.ZERO));
        } finally {server.stop(0);}
    }
    @Test void validaPfxLocalmenteSemRedeERejeitaSenhaErrada() throws Exception {
        Path pfx=dir.resolve("local.pfx");
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
            "-genkeypair","-alias","teste","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
            "-keystore",pfx.toString(),"-storepass","test-password","-dname","CN=Fixture local","-validity","2").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes(); assertEquals(0,p.waitFor());
        assertDoesNotThrow(()->new SefazClient(config(pfx,"test-password")).preparar());
        var ex=assertThrows(Exception.class,()->new SefazClient(config(pfx,"wrong-secret")).preparar());
        assertFalse(ex.toString().contains("wrong-secret")); assertNull(ex.getCause());
    }
}
