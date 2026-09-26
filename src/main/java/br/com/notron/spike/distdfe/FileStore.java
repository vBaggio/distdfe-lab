package br.com.notron.spike.distdfe;

import static br.com.notron.spike.distdfe.DistDfeModels.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class FileStore implements AutoCloseable {
    private final Path dir;
    private final DistDfeParser parser;
    private final JsonMapper json=JsonMapper.builder().build();
    private FileChannel lockChannel;
    private FileLock lock;
    private Estado estado;
    private final LinkedHashMap<String,Documento> indice=new LinkedHashMap<>();
    private final LinkedHashMap<String,Consulta> historico=new LinkedHashMap<>();
    public FileStore(Path dir, DistDfeParser parser, Clock clock) throws IOException {
        this.dir=dir.toAbsolutePath().normalize(); this.parser=parser;
        try {
            Files.createDirectories(this.dir);
            try { Files.setPosixFilePermissions(this.dir,PosixFilePermissions.fromString("rwx------")); }
            catch(UnsupportedOperationException ignored) {}
            lockChannel=FileChannel.open(this.dir.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            lock=lockChannel.tryLock();
            if(lock==null) throw new IOException("Diretório de dados já está em uso.");
            load();
            boolean interrupted=estado.chamadaEmAndamento();
            Path pending=this.dir.resolve("pendentes/lote.json");
            if(Files.exists(pending)) apply(json.readValue(Files.readAllBytes(pending),Pendente.class));
            if(interrupted) {
                estado=new Estado(estado.ultNSU(),estado.maxNSU(),clock.instant(),false,estado.identidade());
                saveState();
                if(!Files.exists(pending)) {
                    record(new Consulta(UUID.randomUUID().toString(),clock.instant(),estado.ultNSU(),"","",estado.ultNSU(),estado.maxNSU(),0,
                        "Execução interrompida recuperada; espera de 60 minutos iniciada na reabertura."));
                }
            }
        } catch(Exception e) {
            close();
            throw new IOException("Não foi possível abrir/recuperar o diretório de dados. Verifique o lock, estado e lotes pendentes; não apague o cursor.");
        }
    }
    private void load() throws IOException {
        Path path=dir.resolve("estado.properties");
        if(!Files.exists(path)) {
            try(var files=Files.list(dir)) {
                if(files.anyMatch(p->!p.getFileName().toString().equals(".lock")))
                    throw new IOException("Dados existentes sem estado.");
            }
            estado=Estado.inicial(); saveState();
        } else {
            Properties p=new Properties();
            try(var in=Files.newInputStream(path)) {p.load(in);}
            String ult=p.getProperty("ultNSU"), max=p.getProperty("maxNSU"), last=p.getProperty("ultimaConsulta",""),
                inflight=p.getProperty("chamadaEmAndamento"), identity=p.getProperty("identidade");
            if(!nsuValido(ult)||!nsuValido(max)||!("true".equals(inflight)||"false".equals(inflight))||identity==null)
                throw new IOException("Estado inválido.");
            estado=new Estado(ult,max,last.isEmpty()?null:Instant.parse(last),Boolean.parseBoolean(inflight),identity);
            if(estado.chamadaEmAndamento()&&estado.ultimaConsulta()==null) throw new IOException("Estado incompleto.");
        }
        if(Files.exists(dir.resolve("indice.json"))) {
            for(var doc:json.readValue(Files.readAllBytes(dir.resolve("indice.json")),Documento[].class)) {
                if(!nsuValido(doc.nsu())) throw new IOException("Índice inválido.");
                DistDfeParser.schemaFolder(doc.schema()); indice.put(doc.nsu(),doc);
            }
        } else if(Files.exists(dir.resolve("xml")) && !Files.exists(dir.resolve("pendentes/lote.json"))) {
            throw new IOException("Índice ausente em acervo existente.");
        }
        if(Files.exists(dir.resolve("execucoes.jsonl"))) {
            for(String line:Files.readAllLines(dir.resolve("execucoes.jsonl"))) {
                if(!line.isBlank()) {var c=json.readValue(line,Consulta.class); historico.put(c.id(),c);}
            }
        }
    }
    public synchronized Estado estado() {return estado;}
    public synchronized List<Documento> documentos() {return List.copyOf(indice.values());}
    public synchronized List<Consulta> ultimasConsultas() {
        var all=new ArrayList<>(historico.values()); Collections.reverse(all);
        return List.copyOf(all.subList(0,Math.min(20,all.size())));
    }
    public synchronized void bind(String identity) throws IOException {
        if(!estado.identidade().isEmpty()&&!estado.identidade().equals(identity)) throw new IOException("Diretório pertence a outro CNPJ/ambiente.");
        estado=new Estado(estado.ultNSU(),estado.maxNSU(),estado.ultimaConsulta(),estado.chamadaEmAndamento(),identity); saveState();
    }
    public synchronized void begin(Instant at) throws IOException {
        estado=new Estado(estado.ultNSU(),estado.maxNSU(),at,true,estado.identidade()); saveState();
    }
    public synchronized Resposta receive(String id,String sent,Instant at,byte[] bytes) throws IOException {
        var pending=new Pendente(id,at,sent,bytes);
        atomic(dir.resolve("pendentes/lote.json"),json.writeValueAsBytes(pending));
        return apply(pending);
    }
    private Resposta apply(Pendente pending) throws IOException {
        Resposta r=parser.parse(pending.resposta());
        validate(r,pending.nsuEnviado());
        String ult=estado.ultNSU(),max=estado.maxNSU();
        // Error cursors may be hints; only an ascending, valid cursor from 137/138/656 is adopted.
        if(Set.of("137","138","656").contains(r.cStat()) && nsuValido(r.ultNSU()) && r.ultNSU().compareTo(ult)>=0) {
            ult=r.ultNSU();
            if(nsuValido(r.maxNSU())&&r.maxNSU().compareTo(ult)>=0) max=r.maxNSU();
            else if(max.compareTo(ult)<0) max=ult;
        }
        estado=new Estado(ult,max,pending.horario(),true,estado.identidade());
        saveState(); // The durable pending batch makes a crash after advancing the cursor recoverable.
        for(var doc:r.documentos()) {
            String folder=DistDfeParser.schemaFolder(doc.indice().schema());
            Path target=dir.resolve("xml").resolve(folder).resolve(doc.indice().nsu()+".xml");
            if(Files.exists(target) && !Arrays.equals(Files.readAllBytes(target),doc.xml()))
                throw new IOException("Documento já gravado com conteúdo diferente.");
            Documento previous=indice.get(doc.indice().nsu());
            if(previous!=null && !previous.equals(doc.indice())) throw new IOException("NSU já indexado com metadados diferentes.");
            atomic(target,doc.xml()); indice.put(doc.indice().nsu(),doc.indice());
        }
        atomic(dir.resolve("indice.json"),json.writeValueAsBytes(indice.values()));
        writeCsv();
        record(new Consulta(pending.id(),pending.horario(),pending.nsuEnviado(),r.cStat(),r.xMotivo(),r.ultNSU(),r.maxNSU(),r.documentos().size(),""));
        finish(pending.horario());
        Files.deleteIfExists(dir.resolve("pendentes/lote.json"));
        syncDirectory(dir.resolve("pendentes"));
        return r;
    }
    static void validate(Resposta r,String sent) throws IOException {
        if(r.cStat().equals("137")||r.cStat().equals("138")) {
            if(r.ultNSU().compareTo(sent)<0||r.ultNSU().compareTo(r.maxNSU())>0)
                throw new IOException("Cursores incoerentes na resposta.");
            if(r.cStat().equals("138")&&r.ultNSU().compareTo(sent)<=0) throw new IOException("Cursor sem avanço.");
            for(var d:r.documentos()) {
                if(d.indice().nsu().compareTo(sent)<=0||d.indice().nsu().compareTo(r.ultNSU())>0)
                    throw new IOException("Documento fora da faixa do lote.");
            }
        }
    }
    public synchronized void failure(String id,String sent,Instant at,String message) throws IOException {
        record(new Consulta(id,at,sent,"","",estado.ultNSU(),estado.maxNSU(),0,message)); finish(at);
    }
    public synchronized void finish(Instant at) throws IOException {
        estado=new Estado(estado.ultNSU(),estado.maxNSU(),at,false,estado.identidade()); saveState();
    }
    public synchronized boolean pending() {return Files.exists(dir.resolve("pendentes/lote.json"));}
    private void record(Consulta consulta) throws IOException {
        historico.put(consulta.id(),consulta);
        var lines=new StringBuilder(); for(var c:historico.values()) lines.append(json.writeValueAsString(c)).append('\n');
        atomic(dir.resolve("execucoes.jsonl"),lines.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void saveState() throws IOException {
        Properties p=new Properties();
        p.setProperty("ultNSU",estado.ultNSU()); p.setProperty("maxNSU",estado.maxNSU());
        p.setProperty("ultimaConsulta",estado.ultimaConsulta()==null?"":estado.ultimaConsulta().toString());
        p.setProperty("proximaConsultaPermitida",estado.limite()==null?"":estado.limite().toString());
        p.setProperty("chamadaEmAndamento",Boolean.toString(estado.chamadaEmAndamento()));
        p.setProperty("identidade",estado.identidade());
        var out=new ByteArrayOutputStream(); p.store(out,"DistDFe Lab - permitir apenas AGORA > proximaConsultaPermitida");
        atomic(dir.resolve("estado.properties"),out.toByteArray());
    }
    private void writeCsv() throws IOException {
        var csv=new StringBuilder("NSU,schema,chNFe,emitenteDocumento,emitenteNome,dhEmi,vNF,cSitNFe,tpEvento,xEvento,tamanhoBytes\n");
        for(var d:indice.values()) {
            var fields=List.of(d.nsu(),d.schema(),d.chave(),d.emitenteDocumento(),d.emitenteNome(),d.dhEmi(),d.vNF(),d.cSitNFe(),d.tpEvento(),d.xEvento(),Integer.toString(d.tamanhoBytes()));
            csv.append(String.join(",",fields.stream().map(s->"\""+s.replace("\"","\"\"")+"\"").toList())).append('\n');
        }
        atomic(dir.resolve("indice.csv"),csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void atomic(Path target,byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".distdfe-",".tmp");
        try {
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(target.getParent());
        } finally {Files.deleteIfExists(temp);}
    }
    private static void syncDirectory(Path path) throws IOException {
        try(var c=FileChannel.open(path,StandardOpenOption.READ)) {c.force(true);}
    }
    @Override public synchronized void close() throws IOException {
        try {if(lock!=null&&lock.isValid()) lock.release();}
        finally {if(lockChannel!=null&&lockChannel.isOpen()) lockChannel.close();}
    }
}
