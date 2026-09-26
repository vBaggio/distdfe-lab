package br.com.notron.spike.distdfe;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ConsultaService service;
    public ApiController(ConsultaService service) {this.service=service;}
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
