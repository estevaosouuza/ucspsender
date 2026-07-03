# Roadmap de fases

Status: **Fase 1 implementada e compilando dos dois lados.** Falta apenas a verificação
manual final (ver "Próximo passo manual" abaixo) e o teste ponta a ponta com um celular
físico.

## Próximo passo manual (precisa de você)

1. `obs-plugin/build_x64/rundir/RelWithDebInfo/ucsp-source.dll` foi copiado, junto com as
   DLLs do FFmpeg (`avcodec-62.dll`, `avformat-62.dll`, `avutil-60.dll`, `swscale-9.dll`,
   `swresample-6.dll`), para `C:\ProgramData\obs-studio\plugins\ucsp-source\bin\64bit\` +
   `...\data\locale\en-US.ini` — essa é a mesma convenção que o plugin NDI (`distroav`)
   já usa nessa máquina, então não precisa de admin nem de mexer em `Program Files`.
2. Abri o OBS Studio para testar e ele mostrou o diálogo de **"Crash or unclean shutdown
   detected"** (por causa de um `Stop-Process -Force` que usei num teste anterior) — esse
   diálogo está esperando você clicar em **"Start Normally"** (não "Safe Mode", senão os
   plugins de terceiros são desabilitados).
3. Depois disso, em **Fontes → + → procure "UCSP Camera Source"** — se aparecer na lista,
   o plugin carregou corretamente. Confira também em Ajuda → Log Files → Show Log File se
   não há erro relacionado a `ucsp-source.dll`.
4. Porta padrão de escuta: **5600** (configurável nas propriedades da fonte).

## Fase 0 — Setup de ambiente e scaffold

- [x] git init + estrutura de pastas + `.gitignore`
- [x] `docs/protocol/ucsp-spec.md`, `docs/architecture.md`
- [x] Instalar Visual Studio Build Tools (workload C++) + CMake
      (CMake instalado via winget; MSVC 14.44 via VS Build Tools 2022 — exige elevação
      UAC interativa, que precisou ser feita manualmente)
- [x] Configurar vcpkg + FFmpeg dev libs (avcodec/avformat/avdevice/avfilter/swscale) —
      `C:\Users\Lenovo\vcpkg`, variável de ambiente `VCPKG_ROOT` (user-level). Linkado
      diretamente por caminho no `CMakeLists.txt` (não via `CMAKE_TOOLCHAIN_FILE` global,
      que conflita com o mecanismo próprio do `obs-plugintemplate` de localizar `libobs`)
- [x] Clonar/adaptar `obsproject/obs-plugintemplate` em `obs-plugin/`; `cmake --preset
      windows-x64` configura com sucesso (baixa/compila obs-studio 31.1.1 + obs-deps +
      Qt6 automaticamente). Nota: `CMakePresets.json` teve o pin de SDK do Windows
      (`10.0.22621`) removido para usar o SDK instalado localmente (`10.0.26100`)
- [x] Scaffold do projeto Android (Gradle/Kotlin via wrapper portátil, sem Android
      Studio GUI), `assembleDebug` funcionando. Teste em emulador fica para quando
      houver um device físico (câmera não é útil em emulador para este caso de uso)

## Fase 1 — MVP: pipeline ponta a ponta funcional

**Objetivo**: vídeo da câmera do celular aparecendo como fonte no OBS via LAN, mesmo que
a latência ainda não bata os 80–300ms alvo (isso é trabalho da Fase 2+).

**Status**: código completo dos dois lados (`android-app` builda com `assembleDebug`;
`obs-plugin` compila — só falta o build do FFmpeg via vcpkg terminar para linkar e testar
ponta a ponta pela primeira vez).

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

**Para testar com um celular Android físico**:
1. APK debug já buildado em
   `android-app/app/build/outputs/apk/debug/app-debug.apk` — instale com
   `adb install app-debug.apk` (com o celular em modo depuração USB) ou copie e instale
   manualmente (precisa permitir "fontes desconhecidas").
2. Celular e PC precisam estar na mesma rede Wi-Fi/LAN.
3. No app, informe o IP local do PC (`ipconfig` no PC para achar, ex. `192.168.x.x`) e a
   porta `5600`, toque em "Iniciar transmissão" (vai pedir permissão de câmera).
4. No OBS, adicione a fonte "UCSP Camera Source" com a mesma porta `5600` — o vídeo deve
   aparecer assim que o primeiro pacote chegar.

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
