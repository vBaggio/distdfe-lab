package br.com.notron.spike.distdfe;

import java.time.Instant;
import java.util.List;

public final class DistDfeModels {
    private DistDfeModels() {}
    public static final String ZERO = "000000000000000";
    public static boolean nsuValido(String nsu) { return nsu != null && nsu.matches("[0-9]{15}"); }
    public record Documento(String nsu, String schema, String chave, String emitenteDocumento,
            String emitenteNome, String dhEmi, String vNF, String cSitNFe,
            String tpEvento, String xEvento, int tamanhoBytes) {}
    public record XmlDocumento(Documento indice, byte[] xml) {}
    public record Resposta(String cStat, String xMotivo, String dhResp, String ultNSU,
                           String maxNSU, List<XmlDocumento> documentos) {}
    public record Consulta(String id, Instant horario, String nsuEnviado, String cStat,
            String xMotivo, String ultNSU, String maxNSU, int quantidadeDocs, String erro) {}
    public record Pendente(String id, Instant horario, String nsuEnviado, byte[] resposta) {}
    public record Estado(String ultNSU, String maxNSU, Instant ultimaConsulta, boolean chamadaEmAndamento,
                         String identidade) {
        public static Estado inicial() { return new Estado(ZERO, ZERO, null, false, ""); }
        public Instant limite() { return ultimaConsulta == null ? null : ultimaConsulta.plusSeconds(3600); }
    }
}
