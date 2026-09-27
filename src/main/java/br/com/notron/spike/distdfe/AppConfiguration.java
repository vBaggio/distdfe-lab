package br.com.notron.spike.distdfe;

import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class AppConfiguration {
    @Bean Clock clock() {return Clock.systemUTC();}
    @Bean DistDfeParser parser() {return new DistDfeParser();}
    @Bean(destroyMethod="close") FileStore fileStore(SpikeProperties config,DistDfeParser parser,Clock clock) throws IOException {
        return new FileStore(config.dataDir(),parser,clock);
    }
    @Bean ConfiguracaoLocal configuracaoLocal(SpikeProperties config) {return new ConfiguracaoLocal(config);}
    @Bean SefazTransport transport(ConfiguracaoLocal local) {return new SefazClient(local::atual);}
    @Bean(destroyMethod="close") ConsultaService service(ConfiguracaoLocal local,FileStore store,SefazTransport transport,Clock clock) {
        return new ConsultaService(local::atual,store,transport,clock);
    }
}
