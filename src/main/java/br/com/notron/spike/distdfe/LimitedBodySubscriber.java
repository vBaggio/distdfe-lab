package br.com.notron.spike.distdfe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int limit) { this.limit=limit; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(1); }
    @Override public void onNext(List<ByteBuffer> items) {
        for (var item:items) {
            if (item.remaining()>limit-bytes.size()) {
                subscription.cancel(); result.completeExceptionally(new IOException("Resposta excede limite.")); return;
            }
            byte[] chunk=new byte[item.remaining()]; item.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable t) { result.completeExceptionally(t); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
}
