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
    @Bean SefazTransport transport(SpikeProperties config) {return new SefazClient(config);}
    @Bean(destroyMethod="close") ConsultaService service(SpikeProperties config,FileStore store,SefazTransport transport,Clock clock) {
        return new ConsultaService(config,store,transport,clock);
    }
}
