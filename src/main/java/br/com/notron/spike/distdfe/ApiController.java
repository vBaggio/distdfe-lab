package br.com.notron.spike.distdfe;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ConsultaService service;
    private final ConfiguracaoLocal configuracao;
    public ApiController(ConsultaService service,ConfiguracaoLocal configuracao) {this.service=service;this.configuracao=configuracao;}
    @GetMapping("/configuracao") public ConfiguracaoLocal.Resumo configuracao() {return configuracao.resumo();}
    @PostMapping(value="/configuracao",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ConfiguracaoLocal.Resumo configurar(@RequestParam("certificado") MultipartFile certificado,
            @RequestParam("senha") String senha,@RequestParam("uf") String uf) throws java.io.IOException {
        if(service.status().emAndamento()) throw new ConsultaService.Conflict("Há uma consulta em andamento.");
        return configuracao.salvar(certificado.getBytes(),senha,uf);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalido(IllegalArgumentException e) {
        return ResponseEntity.status(422).body(Map.of("mensagem",e.getMessage()));
    }
    @GetMapping("/estado") public ConsultaService.Status estado() {return service.status();}
    @GetMapping("/documentos") public Map<String,Object> documentos() {
        var docs=service.documentos();
        var totals=docs.stream().collect(Collectors.groupingBy(DistDfeModels.Documento::schema,TreeMap::new,Collectors.counting()));
        return Map.of("documentos",docs,"total",docs.size(),"totaisPorSchema",totals);
    }
    @PostMapping(value="/consultar",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,String>> consultar() {
        service.iniciar(); return ResponseEntity.accepted().body(Map.of("mensagem","Execução iniciada."));
    }
    @PostMapping(value="/certificado/validar",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,String> validar() {
        service.validarCertificado(); return Map.of("mensagem","PFX validado localmente, sem consulta à SEFAZ.");
    }
    @ExceptionHandler(ConsultaService.Conflict.class)
    public ResponseEntity<Map<String,String>> conflict(ConsultaService.Conflict e) {
        return ResponseEntity.status(409).body(Map.of("mensagem",e.getMessage()));
    }
    @ExceptionHandler(ConsultaService.ConfigurationError.class)
    public ResponseEntity<Map<String,String>> configuration(ConsultaService.ConfigurationError e) {
        return ResponseEntity.status(422).body(Map.of("mensagem",e.getMessage()));
    }
}
