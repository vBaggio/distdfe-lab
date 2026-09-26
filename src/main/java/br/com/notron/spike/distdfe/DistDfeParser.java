package br.com.notron.spike.distdfe;

import static br.com.notron.spike.distdfe.DistDfeModels.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class DistDfeParser {
    public static final int MAX_RESPONSE = 16 * 1024 * 1024;
    public static final int MAX_XML = 8 * 1024 * 1024;
    private static final String NFE = "http://www.portalfiscal.inf.br/nfe";

    public Resposta parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_RESPONSE) throw new IOException("Resposta excede o limite de 16 MiB.");
        Document doc = xml(bytes);
        if (doc.getElementsByTagNameNS("http://www.w3.org/2003/05/soap-envelope", "Fault").getLength() > 0)
            throw new IOException("A SEFAZ retornou SOAP Fault.");
        Element ret = first(doc.getDocumentElement(), "retDistDFeInt");
        if (ret == null) throw new IOException("Resposta sem retDistDFeInt.");
        String stat = value(ret, "cStat"), ult = value(ret, "ultNSU"), max = value(ret, "maxNSU");
        if (!stat.matches("[0-9]{3}")) throw new IOException("Resposta sem cStat válido.");
        if ((!ult.isEmpty() && !nsuValido(ult)) || (!max.isEmpty() && !nsuValido(max)))
            throw new IOException("Resposta com NSU inválido.");
        if ((stat.equals("137") || stat.equals("138")) && (!nsuValido(ult) || !nsuValido(max)))
            throw new IOException("Resposta de sucesso sem cursores.");
        List<XmlDocumento> documentos = new ArrayList<>();
        NodeList zipped = ret.getElementsByTagNameNS(NFE, "docZip");
        if (zipped.getLength() > 50) throw new IOException("Lote excede 50 documentos.");
        Set<String> nsus = new HashSet<>();
        int total = 0;
        for (int i = 0; i < zipped.getLength(); i++) {
            Element zip = (Element) zipped.item(i);
            String nsu = zip.getAttribute("NSU"), schema = zip.getAttribute("schema");
            if (!nsuValido(nsu) || !nsus.add(nsu)) throw new IOException("NSU de documento inválido ou duplicado.");
            schemaFolder(schema);
            byte[] unpacked;
            try {
                byte[] compressed = Base64.getDecoder().decode(zip.getTextContent().replaceAll("\\s", ""));
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                    unpacked = gzip.readNBytes(MAX_XML + 1);
                    if (unpacked.length > MAX_XML) throw new IOException("XML excede 8 MiB.");
                }
            } catch (IllegalArgumentException e) { throw new IOException("docZip contém base64 inválido."); }
            total += unpacked.length;
            if (total > 64 * 1024 * 1024) throw new IOException("Lote descompactado excede 64 MiB.");
            documentos.add(new XmlDocumento(index(nsu, schema, unpacked), unpacked));
        }
        if (!stat.equals("138") && !documentos.isEmpty()) throw new IOException("Documentos em resposta sem cStat 138.");
        if (stat.equals("138") && documentos.isEmpty()) throw new IOException("Resposta 138 sem documentos.");
        return new Resposta(stat, value(ret,"xMotivo"), value(ret,"dhResp"), ult, max, List.copyOf(documentos));
    }

    public Documento index(String nsu, String schema, byte[] bytes) throws IOException {
        Element root = xml(bytes).getDocumentElement();
        String type = root.getLocalName();
        String chave = value(root, "chNFe"), emitDoc = "", emitNome = "", date = "", valor = "", sit = "";
        if ("resNFe".equals(type)) {
            emitDoc = either(root, "CNPJ", "CPF"); emitNome = value(root,"xNome");
            date = value(root,"dhEmi"); valor = value(root,"vNF"); sit = value(root,"cSitNFe");
        } else if ("nfeProc".equals(type) || "procNFe".equals(type) || "NFe".equals(type)) {
            Element inf = first(root,"infNFe"), emit = first(root,"emit"), ide = first(root,"ide"), total = first(root,"ICMSTot");
            if (chave.isEmpty() && inf != null && inf.getAttribute("Id").matches("NFe[0-9]{44}"))
                chave = inf.getAttribute("Id").substring(3);
            emitDoc = either(emit,"CNPJ","CPF"); emitNome = value(emit,"xNome");
            date = value(ide,"dhEmi"); valor = value(total,"vNF");
        }
        return new Documento(nsu,schema,chave,emitDoc,emitNome,date,valor,sit,
                value(root,"tpEvento"),value(root,"xEvento"),bytes.length);
    }
    public static String schemaFolder(String schema) throws IOException {
        if (schema == null || !schema.matches("[A-Za-z][A-Za-z0-9]*_v[0-9]+(?:\\.[0-9]+)*\\.xsd"))
            throw new IOException("Nome de schema inválido.");
        return schema.substring(0,schema.lastIndexOf("_v"));
    }
    private static Document xml(byte[] bytes) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) { throw new IOException("XML inválido ou inseguro."); }
    }
    private static Element first(Element e, String tag) {
        if (e == null) return null;
        if (NFE.equals(e.getNamespaceURI()) && tag.equals(e.getLocalName())) return e;
        return (Element) e.getElementsByTagNameNS(NFE,tag).item(0);
    }
    private static String value(Element e,String tag) { Element found=first(e,tag); return found==null ? "" : found.getTextContent().trim(); }
    private static String either(Element e,String a,String b) { String s=value(e,a); return s.isEmpty()?value(e,b):s; }
}
