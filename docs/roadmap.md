# Roadmap de fases

Status: **Fase 0 em andamento**.

## Fase 0 — Setup de ambiente e scaffold

- [x] git init + estrutura de pastas + `.gitignore`
- [x] `docs/protocol/ucsp-spec.md`, `docs/architecture.md`
- [ ] Instalar Visual Studio Build Tools (workload C++) + CMake
- [ ] Configurar vcpkg + FFmpeg dev libs (avcodec/avformat/avutil/swscale)
- [ ] Clonar/adaptar `obsproject/obs-plugintemplate` em `obs-plugin/`, confirmar que o
      plugin de exemplo compila e carrega na instalação local do OBS Studio
- [ ] Scaffold do projeto Android (Android Studio, Kotlin, minSdk 24, CameraX), confirmar
      Hello World rodando no emulador

## Fase 1 — MVP: pipeline ponta a ponta funcional

**Objetivo**: vídeo da câmera do celular aparecendo como fonte no OBS via LAN, mesmo que
a latência ainda não bata os 80–300ms alvo (isso é trabalho da Fase 2+).

Android:
- `capture/CameraController.kt` — CameraX com `Surface` do `MediaCodec.createInputSurface()`
- `encode/H264Encoder.kt` — MediaCodec H264 Baseline, sem B-frame, GOP curto (~1s)
- `network/UcspHeader.kt` + `UcspPacketizer.kt` — implementação do header de 32 bytes
- `network/FecEncoder.kt` — paridade XOR
- `network/UcspSender.kt` — socket UDP único (envia vídeo/FEC, recebe backchannel)
- `network/BackchannelListener.kt` — parse de reports e keyframe requests
- `thermal/ThermalMonitor.kt` — stub que loga transições (liga na Fase 2)
- `service/StreamingService.kt` — foreground service + wake lock
- `ui/` + `MainActivity.kt` — IP/porta do PC, start/stop

OBS plugin:
- `plugin-main.cpp` + `ucsp-source.cpp/.h` — `obs_source_info` (`OBS_SOURCE_ASYNC_VIDEO`)
- `ucsp/udp_receiver.cpp/.h` — WinSock2, thread de recepção, parse de header
- `ucsp/frame_reassembler.cpp/.h` — reassembly por FrameId, descarte rápido
- `ucsp/fec_decoder.cpp/.h` — recuperação XOR
- `ucsp/h264_decoder.cpp/.h` — libavcodec, saída I420 → `obs_source_frame2`
- `ucsp/backchannel_sender.cpp/.h` — reports ~50ms + keyframe request sob demanda

**Critério de "pronto" da Fase 1**:
1. Vídeo ao vivo do celular aparece numa fonte do OBS ("UCSP Camera Source").
2. Perda de pacote única induzida é mascarada pelo FEC sem glitch visível.
3. Perda maior derruba um frame e dispara keyframe request, recuperando em ~1 GOP —
   nunca trava indefinidamente.
4. Reports de backchannel chegam no celular a cada ~50ms (visível no logcat).
5. Primeira medição de latência glass-to-glass documentada (método do relógio/tela,
   ver seção de testes no plano).

## Fase 2 — Inteligência adaptativa

- Motor adaptativo no Android consumindo stats do backchannel (bitrate via
  `MediaCodec.setParameters`; resolução/fps via re-init gracioso do encoder)
- `ThermalMonitor` ligado ao motor adaptativo (degrada antes do throttling)
- Handshake HELLO/HELLO_ACK real (offset de clock, sessão)

## Fase 3 — Polimento de latência

- Jitter buffer adaptativo (baseado no jitter medido, não fixo)
- Decode hardware (DXVA2/D3D11VA) no lugar do software decode
- Tuning fino de GOP/bitrate

## Fase 4 — Dashboard e UX

- Dashboard de latência/jitter/loss/fps/bitrate (overlay no app + painel no plugin)
- Configuração assistida de rede (Wi-Fi dedicado, 5GHz, canal fixo, QoS) como guia na UI

## Fase 5 — Futuro / opcional

- Multi-câmera com sync via `StreamId` + HELLO real
- Sender nativo NDK/zero-copy (só se profiling mostrar que o caminho Kotlin é o gargalo)
