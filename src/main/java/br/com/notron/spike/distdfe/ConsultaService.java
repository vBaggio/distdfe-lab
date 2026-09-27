package br.com.notron.spike.distdfe;

import static br.com.notron.spike.distdfe.DistDfeModels.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import java.net.http.HttpTimeoutException;

public final class ConsultaService implements AutoCloseable {
    private final Supplier<SpikeProperties> configuracao;
    private final FileStore store;
    private final SefazTransport transport;
    private final Clock clock;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("distdfe-consulta").factory());
    private volatile boolean running;
    private volatile String message="Pronto. Nenhuma consulta é feita automaticamente.";
    private volatile String fatal="";
    private volatile boolean closed;
    public ConsultaService(SpikeProperties config,FileStore store,SefazTransport transport,Clock clock) {
        this(() -> config,store,transport,clock);
    }
    public ConsultaService(Supplier<SpikeProperties> configuracao,FileStore store,SefazTransport transport,Clock clock) {
        this.configuracao=configuracao; this.store=store; this.transport=transport; this.clock=clock;
    }
    private SpikeProperties config() {return configuracao.get();}
    public record Status(String ultNSU,String maxNSU,Instant ultimaConsulta,Instant proximaConsultaPermitida,
        Instant agora,boolean emAndamento,boolean podeConsultar,boolean configurado,int ambiente,
        String pendencia,String mensagem,List<Consulta> ultimasExecucoes) {}
    public static class Conflict extends RuntimeException {
        Conflict(String message) {super(message);}
    }
    public static class ConfigurationError extends RuntimeException {
        ConfigurationError(String message) {super(message);}
    }
    private String pendencia() {
        if(!fatal.isEmpty()) return fatal;
        String issue=config().pendencia();
        if(!issue.isEmpty()) return issue;
        String identity=store.estado().identidade();
        if(!identity.isEmpty()&&!identity.equals(config().cnpj()+":"+config().ambiente()))
            return "Este diretório de dados pertence a outro CNPJ/ambiente.";
        return "";
    }
    public synchronized Status status() {
        Estado e=store.estado(); Instant now=clock.instant(); String issue=pendencia();
        boolean allowed=e.limite()==null || now.isAfter(e.limite());
        return new Status(e.ultNSU(),e.maxNSU(),e.ultimaConsulta(),e.limite(),now,running,
            !closed&&!running&&allowed&&issue.isEmpty(),issue.isEmpty(),config().ambiente(),issue,message,store.ultimasConsultas());
    }
    public synchronized void validarCertificado() {
        if(running||closed) throw new Conflict("Há uma execução em andamento ou a aplicação está encerrando.");
        String issue=pendencia(); if(!issue.isEmpty()) throw new ConfigurationError(issue);
        try {transport.preparar();}
        catch(Exception e) {
            message="PFX inválido ou indisponível. Confira senha, validade e chave privada.";
            throw new ConfigurationError(message);
        }
        message="PFX validado localmente. Nenhuma chamada à SEFAZ foi realizada nesta validação.";
    }
    public synchronized void iniciar() {
        if(running||closed) throw new Conflict("Há uma execução em andamento ou a aplicação está encerrando.");
        Estado state=store.estado();
        if(state.limite()!=null&&!clock.instant().isAfter(state.limite()))
            throw new Conflict("Aguarde mais de 60 minutos desde a última chamada à SEFAZ.");
        validarCertificado();
        try {store.bind(config().cnpj()+":"+config().ambiente());}
        catch(Exception e) {fatal="Falha ao vincular o diretório de dados. Verifique permissões e identidade."; throw new ConfigurationError(fatal);}
        running=true; message="Consulta em andamento.";
        worker.execute(this::execute);
    }
    private void execute() {
        try {
            for(int i=0;i<30;i++) {
                String sent=store.estado().ultNSU(),id=UUID.randomUUID().toString();
                Instant ultimaAnterior=store.estado().ultimaConsulta();
                try {
                    store.begin(clock.instant());
                } catch(Exception e) {fatal="Falha ao persistir o início da chamada. Consulta não enviada.";message=fatal;return;}
                Resposta response;
                try {
                    byte[] raw=transport.consultar(sent);
                    response=store.receive(id,sent,clock.instant(),raw);
                } catch(Exception e) {
                    String error=safeError(e);
                    if(naoConectou(e)) {
                        try {store.naoEnviada(id,sent,clock.instant(),error,ultimaAnterior);}
                        catch(Exception disk) {fatal="Falha ao gravar o resultado. Reinicie somente após verificar o armazenamento.";}
                        message=error; return;
                    }
                    try {store.failure(id,sent,clock.instant(),error);}
                    catch(Exception disk) {fatal="Falha ao gravar o resultado. Reinicie somente após verificar o armazenamento.";}
                    if(store.pending()) fatal="Há um lote pendente de recuperação. Consultas bloqueadas para preservar os documentos.";
                    message=error; if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                    return;
                }
                message="SEFAZ retornou "+response.cStat()+". "+response.documentos().size()+" documento(s) neste lote.";
                if(!response.cStat().equals("138")||response.ultNSU().equals(response.maxNSU())) return;
                if(i==29) message="Limite de 30 chamadas atingido. Retome manualmente após a janela de 60 minutos.";
            }
        } finally {running=false;}
    }
    /** A conexão nem abriu: nenhum byte chegou à SEFAZ, então a janela de consumo não é gasta. */
    static boolean naoConectou(Throwable e) {
        for(Throwable t=e;t!=null;t=t.getCause())
            if(t instanceof java.net.ConnectException||t instanceof java.net.NoRouteToHostException
                ||t instanceof java.net.UnknownHostException||t instanceof java.net.http.HttpConnectTimeoutException) return true;
        return false;
    }
    private static String safeError(Exception e) {
        if(naoConectou(e)) return "Não foi possível conectar à SEFAZ (servidor fora do ar ou sem rota). Nada foi enviado e a janela de 60 minutos não foi consumida. Tente de novo mais tarde.";
        if(e instanceof java.io.IOException && e.getMessage()!=null && e.getMessage().startsWith("SEFAZ retornou HTTP")) return e.getMessage();
        if(e instanceof HttpTimeoutException) return "Tempo de resposta da SEFAZ excedido. Aguarde a janela para tentar novamente.";
        if(e instanceof SSLException) return "Falha TLS. Confira validade, cadeia do certificado e acesso à SEFAZ.";
        if(e instanceof InterruptedException) return "Chamada interrompida. Aguarde a janela antes de retomar.";
        return "Falha na comunicação ou processamento. Consulte o histórico e verifique a configuração/armazenamento.";
    }
    public List<Documento> documentos() {return store.documentos();}
    @Override public void close() {
        synchronized(this) {closed=true;worker.shutdown();}
        try {if(!worker.awaitTermination(65,TimeUnit.SECONDS)) {worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}}
        catch(InterruptedException e) {worker.shutdownNow();Thread.currentThread().interrupt();}
    }
}
