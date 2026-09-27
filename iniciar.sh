#!/usr/bin/env bash
# Sobe o DistDFe Lab e abre o navegador. Na primeira vez, configure o certificado pela tela.
cd "$(dirname "$0")"
(until curl -fs -o /dev/null http://127.0.0.1:8080/api/estado; do sleep 1; done
 xdg-open http://localhost:8080 >/dev/null 2>&1) &
exec ./gradlew bootRun -q --console=plain
