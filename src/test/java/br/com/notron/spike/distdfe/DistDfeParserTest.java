package br.com.notron.spike.distdfe;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class DistDfeParserTest {
    final DistDfeParser parser=new DistDfeParser();
    static byte[] fixture(String name) throws IOException {
        try(var in=DistDfeParserTest.class.getResourceAsStream("/fixtures/"+name)) {
            if(in==null) throw new IOException("Fixture ausente"); return in.readAllBytes();
        }
    }
    @Test void extraiTiposEPreservaBytes() throws Exception {
        var r=parser.parse(fixture("resposta-138.xml"));
        assertEquals("138",r.cStat()); assertEquals(5,r.documentos().size());
        var resumo=r.documentos().get(0);
        assertArrayEquals(fixture("resNFe_v1.01.xml"),resumo.xml());
        assertEquals("Empresa, \"Teste\"",resumo.indice().emitenteNome());
        assertEquals("123.45",resumo.indice().vNF());
        assertEquals(resumo.xml().length,resumo.indice().tamanhoBytes());
        assertEquals("",r.documentos().get(1).indice().emitenteDocumento());
        assertEquals("110111",r.documentos().get(1).indice().tpEvento());
        assertEquals("12345678909",r.documentos().get(2).indice().emitenteDocumento());
        assertEquals("1".repeat(44),r.documentos().get(2).indice().chave());
        assertEquals("210210",r.documentos().get(3).indice().tpEvento());
        assertEquals("",r.documentos().get(4).indice().vNF());
    }
    @Test void aceitaRespostasSemDocumentos() throws Exception {
        for(String stat:new String[]{"137","656","999"}) {
            var r=parser.parse(fixture("resposta-"+stat+".xml"));
            assertEquals(stat,r.cStat()); assertTrue(r.documentos().isEmpty());
        }
    }
    @Test void rejeitaXxeSoapFaultTraversalEBase64Invalido() throws Exception {
        assertThrows(IOException.class,()->parser.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".getBytes()));
        assertThrows(IOException.class,()->parser.parse("<s:Envelope xmlns:s='http://www.w3.org/2003/05/soap-envelope'><s:Body><s:Fault/></s:Body></s:Envelope>".getBytes()));
        assertThrows(IOException.class,()->DistDfeParser.schemaFolder("../../arquivo.xsd"));
        String xml=new String(fixture("resposta-138.xml"),StandardCharsets.UTF_8);
        assertThrows(IOException.class,()->parser.parse(xml.replaceFirst(">H4s[^<]+<",">%%%<").getBytes()));
        assertThrows(IOException.class,()->parser.parse(xml.replaceFirst(">H4s[^<]+<",">"+Base64.getEncoder().encodeToString("not gzip".getBytes())+"<").getBytes()));
    }
    @Test void exigeCursorETemLimiteDeResposta() throws Exception {
        String s=new String(fixture("resposta-137.xml"));
        assertThrows(IOException.class,()->parser.parse(s.replace("<ultNSU>000000000000005</ultNSU>","").getBytes()));
        assertThrows(IOException.class,()->parser.parse(new byte[DistDfeParser.MAX_RESPONSE+1]));
    }
}
