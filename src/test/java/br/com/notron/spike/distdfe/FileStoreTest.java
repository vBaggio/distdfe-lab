package br.com.notron.spike.distdfe;

import static org.junit.jupiter.api.Assertions.*;
import static br.com.notron.spike.distdfe.DistDfeModels.*;
import java.nio.file.*;
import java.time.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class FileStoreTest {
    @TempDir Path dir;
    Clock clock=Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"),ZoneOffset.UTC);
    FileStore open() throws IOException {return new FileStore(dir,new DistDfeParser(),clock);}
    @Test void persisteXmlIndiceHistoricoEIdentidade() throws Exception {
        try(var store=open()) {
            store.bind("12345678000195:1"); store.begin(clock.instant());
            store.receive("lote",ZERO,clock.instant(),DistDfeParserTest.fixture("resposta-138.xml"));
            assertEquals("000000000000005",store.estado().ultNSU());
            assertEquals(5,store.documentos().size());
            assertEquals(1,store.ultimasConsultas().size());
            assertArrayEquals(DistDfeParserTest.fixture("resNFe_v1.01.xml"),Files.readAllBytes(dir.resolve("xml/resNFe/000000000000001.xml")));
            assertTrue(Files.readString(dir.resolve("indice.csv")).contains("\"Empresa, \"\"Teste\"\"\""));
            assertThrows(IOException.class,()->store.bind("99999999000191:1"));
            assertThrows(IOException.class,this::open);
        }
        try(var store=open()) {assertEquals(5,store.documentos().size()); assertEquals(clock.instant(),store.estado().ultimaConsulta());}
    }
    @Test void recuperaLoteAposAvancarCursorSemDuplicar() throws Exception {
        var pending=new Pendente("lote",clock.instant(),ZERO,DistDfeParserTest.fixture("resposta-138.xml"));
        try(var store=open()) {
            store.begin(clock.instant()); store.receive(pending.id(),pending.nsuEnviado(),pending.horario(),pending.resposta());
        }
        // Simulate a crash after successful writes but before removing the journal; replay is idempotent.
        FileStore.atomic(dir.resolve("pendentes/lote.json"),JsonMapper.builder().build().writeValueAsBytes(pending));
        Files.delete(dir.resolve("xml/resNFe/000000000000001.xml"));
        try(var store=open()) {
            assertEquals(5,store.documentos().size()); assertEquals(1,store.ultimasConsultas().size());
            assertTrue(Files.exists(dir.resolve("xml/resNFe/000000000000001.xml"))); assertFalse(store.pending());
        }
    }
    @Test void quedaDuranteChamadaReiniciaEsperaNaReabertura() throws Exception {
        try(var store=open()) {store.begin(clock.instant().minusSeconds(9000));}
        try(var store=open()) {assertEquals(clock.instant(),store.estado().ultimaConsulta()); assertFalse(store.estado().chamadaEmAndamento());}
    }
    @Test void falhaAoSalvarXmlMantemLoteParaRecuperacao() throws Exception {
        try(var store=open()) {
            store.begin(clock.instant());
            Files.createDirectories(dir.resolve("xml"));
            Files.writeString(dir.resolve("xml/resNFe"),"obstrução de escrita simulada");
            assertThrows(IOException.class,()->store.receive("recuperavel",ZERO,clock.instant(),DistDfeParserTest.fixture("resposta-138.xml")));
            assertEquals("000000000000005",store.estado().ultNSU());
            assertTrue(store.pending());
        }
        Files.delete(dir.resolve("xml/resNFe"));
        try(var store=open()) {
            assertEquals(5,store.documentos().size()); assertFalse(store.pending());
            assertArrayEquals(DistDfeParserTest.fixture("resNFe_v1.01.xml"),Files.readAllBytes(dir.resolve("xml/resNFe/000000000000001.xml")));
        }
    }
    @Test void estadoCorrompidoOuPerdidoNaoViraZero() throws Exception {
        try(var ignored=open()) {}
        Files.writeString(dir.resolve("estado.properties"),"ultNSU=oops");
        assertThrows(IOException.class,this::open);
        Files.delete(dir.resolve("estado.properties")); Files.writeString(dir.resolve("indice.csv"),"existing data");
        assertThrows(IOException.class,this::open);
    }
    @Test void erroSemCursorNaoZeraEstadoE656PodeAvancar() throws Exception {
        try(var store=open()) {
            store.receive("ok",ZERO,clock.instant(),DistDfeParserTest.fixture("resposta-138.xml"));
            String error="<retDistDFeInt xmlns='http://www.portalfiscal.inf.br/nfe'><cStat>999</cStat><xMotivo>Erro</xMotivo></retDistDFeInt>";
            store.receive("error","000000000000005",clock.instant(),error.getBytes());
            assertEquals("000000000000005",store.estado().ultNSU());
            String blocked=error.replace("999","656").replace("</retDistDFeInt>","<ultNSU>000000000000100</ultNSU></retDistDFeInt>");
            store.receive("blocked","000000000000005",clock.instant(),blocked.getBytes());
            assertEquals("000000000000100",store.estado().ultNSU());
        }
    }
}
