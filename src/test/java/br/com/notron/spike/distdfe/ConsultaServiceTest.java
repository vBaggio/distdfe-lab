package br.com.notron.spike.distdfe;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsultaServiceTest {
    @TempDir Path dir;
    final MutableClock clock=new MutableClock();
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-09-26T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return now;}
    }
    SpikeProperties config() throws Exception {
        Path cert=dir.resolve("fake.pfx"); Files.writeString(cert,"fixture; transport is fake");
        return new SpikeProperties(cert,"local-secret","12345678000195","35",1,dir.resolve("data"));
    }
    static class Fake implements SefazTransport {
        final AtomicInteger calls=new AtomicInteger();
        String stat="137";
        boolean timeout,pages;
        CountDownLatch entered,release;
        public void preparar() {}
        public byte[] consultar(String nsu) throws Exception {
            int n=calls.incrementAndGet();
            if(entered!=null) {entered.countDown();release.await();}
            if(timeout) throw new java.net.http.HttpTimeoutException("secret must never be logged");
            if(pages) {
                String s=new String(DistDfeParserTest.fixture("resposta-138.xml"));
                for(int i=5;i>=1;i--) s=s.replace("%015d".formatted(i),"%015d".formatted((n-1)*5+i));
                return s.replace("<maxNSU>"+"%015d".formatted(n*5)+"</maxNSU>","<maxNSU>000000000000999</maxNSU>").getBytes();
            }
            return DistDfeParserTest.fixture("resposta-"+stat+".xml");
        }
    }
    static void await(ConsultaService service) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(service.status().emAndamento()&&System.nanoTime()<until) Thread.sleep(10);
        assertFalse(service.status().emAndamento(),"Execução não terminou");
    }
    @Test void limiteExatoDe60MinutosPersisteAposRestart() throws Exception {
        var cfg=config(); var fake=new Fake();
        try(var store=new FileStore(cfg.dataDir(),new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
            service.iniciar(); await(service); assertEquals(1,fake.calls.get());
            var last=service.status().ultimaConsulta();
            assertThrows(ConsultaService.Conflict.class,service::iniciar);
            assertEquals(last,service.status().ultimaConsulta());
            clock.now=clock.now.plusSeconds(3600);
            assertFalse(service.status().podeConsultar()); assertThrows(ConsultaService.Conflict.class,service::iniciar);
        }
        try(var store=new FileStore(cfg.dataDir(),new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
            assertThrows(ConsultaService.Conflict.class,service::iniciar);
            clock.now=clock.now.plusNanos(1); assertTrue(service.status().podeConsultar());
            service.iniciar(); await(service); assertEquals(2,fake.calls.get());
        }
    }
    @Test void recusaConcorrenciaEContaDesdeFimDaUltimaChamada() throws Exception {
        var cfg=config();var fake=new Fake();fake.entered=new CountDownLatch(1);fake.release=new CountDownLatch(1);
        try(var store=new FileStore(cfg.dataDir(),new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
            service.iniciar();assertTrue(fake.entered.await(2,TimeUnit.SECONDS));
            assertThrows(ConsultaService.Conflict.class,service::iniciar);
            clock.now=clock.now.plusSeconds(30);fake.release.countDown();await(service);
            assertEquals(clock.now,service.status().ultimaConsulta());assertEquals(1,fake.calls.get());
        } finally {fake.release.countDown();}
    }
    @Test void paraEm137656OutrosETempoEsgotado() throws Exception {
        var cfg=config();
        for(String stat:new String[]{"137","656","999","timeout"}) {
            var fake=new Fake();fake.stat=stat;fake.timeout=stat.equals("timeout");
            Path data=dir.resolve(stat);
            try(var store=new FileStore(data,new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
                service.iniciar();await(service);assertEquals(1,fake.calls.get()); assertFalse(service.status().podeConsultar());
                assertEquals(1,service.status().ultimasExecucoes().size());
                assertFalse(service.status().toString().contains("secret must never"));
            }
        }
    }
    @Test void paginaAte30ConsultasSemEsperarEntreLotes() throws Exception {
        var cfg=config();var fake=new Fake();fake.pages=true;
        try(var store=new FileStore(cfg.dataDir(),new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
            service.iniciar();await(service); assertEquals(30,fake.calls.get());
            assertEquals(150,service.documentos().size()); assertEquals(20,service.status().ultimasExecucoes().size());
            assertEquals("000000000000150",service.status().ultNSU());assertFalse(service.status().podeConsultar());
        }
    }
    @Test void semCertificadoNaoFazChamadaNemIniciaCooldown() throws Exception {
        var cfg=new SpikeProperties(null,null,null,null,1,dir.resolve("data"));var fake=new Fake();
        try(var store=new FileStore(cfg.dataDir(),new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
            assertFalse(service.status().configurado());assertThrows(ConsultaService.ConfigurationError.class,service::iniciar);
            assertEquals(0,fake.calls.get());assertNull(service.status().ultimaConsulta());
        }
    }
    @Test void cursorSemAvancoInterrompeEPreservaRespostaParaDiagnostico() throws Exception {
        var cfg=config();var fake=new Fake();
        try(var store=new FileStore(cfg.dataDir(),new DistDfeParser(),clock);var service=new ConsultaService(cfg,store,fake,clock)) {
            service.iniciar();await(service); // 137 establishes cursor 5.
            clock.now=clock.now.plusSeconds(3601);fake.stat="138";
            service.iniciar();await(service);
            assertEquals(2,fake.calls.get());assertEquals("000000000000005",store.estado().ultNSU());
            assertTrue(store.pending());assertTrue(service.status().pendencia().contains("lote pendente"));
            clock.now=clock.now.plusSeconds(3601);assertFalse(service.status().podeConsultar());
        }
    }
}
