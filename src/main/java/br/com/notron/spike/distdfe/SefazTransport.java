package br.com.notron.spike.distdfe;

public interface SefazTransport {
    void preparar() throws Exception;
    byte[] consultar(String ultNSU) throws Exception;
}
