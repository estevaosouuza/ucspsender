# UCSP — Ultra low latency Camera Streaming Protocol

Sistema de transmissão de câmera de celular Android para o OBS Studio via um protocolo
UDP customizado (UCSP), otimizado para latência mínima (alvo 80–300ms) em redes Wi-Fi
instáveis (ex.: ambiente de igreja com muitos dispositivos conectados).

Filosofia central: **nunca travar a imagem — degradar qualidade antes de perder fluidez.**

## Estrutura do repositório

- [`docs/`](docs/) — especificação do protocolo, arquitetura e roadmap de fases.
- [`android-app/`](android-app/) — app Android (Kotlin) que captura, codifica (H.264) e
  envia o vídeo via UCSP.
- [`obs-plugin/`](obs-plugin/) — plugin nativo do OBS Studio (C++) que recebe, decodifica
  e expõe o vídeo como uma fonte dentro do OBS.
- [`tools/`](tools/) — utilitários standalone (ex.: sniffer de pacotes UCSP para debug).

## Por onde começar

1. Leia [`docs/architecture.md`](docs/architecture.md) para a visão geral do sistema.
2. Leia [`docs/protocol/ucsp-spec.md`](docs/protocol/ucsp-spec.md) para o formato de pacote.
3. Veja [`docs/roadmap.md`](docs/roadmap.md) para o estado atual das fases de desenvolvimento.

## Setup do ambiente

Pré-requisitos para compilar o plugin OBS: Visual Studio Build Tools (workload C++),
CMake e vcpkg (para FFmpeg). Pré-requisitos para o app Android: Android Studio + SDK
(NDK não é necessário na Fase 1). Detalhes em `docs/roadmap.md` (Fase 0).
