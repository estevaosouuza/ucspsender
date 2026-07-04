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
- `capture/CameraController.kt` — CameraX `Preview` (visível, `PreviewView`) +
  `ImageAnalysis` (YUV_420_888 → encoder), combinação garantida em qualquer device
- `encode/H264Encoder.kt` — MediaCodec H264 Baseline em modo buffer síncrono (não
  Surface), sem B-frame, GOP curto (~1s); dropa frame se o encoder não tiver buffer
  livre em vez de bloquear
- `network/UcspHeader.kt` + `UcspPacketizer.kt` — implementação do header de 32 bytes
- `network/FecEncoder.kt` — paridade XOR
- `network/UcspSender.kt` — socket UDP único (envia vídeo/FEC, recebe backchannel)
- `network/BackchannelListener.kt` — parse de reports e keyframe requests
- `network/WifiSignalMonitor.kt` — força do sinal Wi-Fi (RSSI, 0–4 barras) exibida no
  canto superior esquerdo
- `thermal/ThermalMonitor.kt` — stub que loga transições (liga na Fase 2)
- `service/StreamingService.kt` — só notificação de foreground service (o pipeline
  inteiro roda na `MainActivity`, já que o preview da câmera precisa de uma Activity
  visível)
- `MainActivity.kt` — preview da câmera em tela cheia, controles de resolução (480p
  /720p/1080p) e FPS (15/24/30) ordenados do menor para o maior (menor = menor
  latência), layout `layout/` (retrato: controles embaixo) e `layout-land/` (paisagem:
  controles na lateral)

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

### Diagnóstico: "OBS não está recebendo vídeo"

Quando **zero pacotes chegam** (log do OBS não mostra nenhuma linha `first packet
received from ...`), verifique nesta ordem:

1. **IP/porta corretos no app** — o IP do PC muda se ele trocar de rede; confira com
   `ipconfig` no PC e compare com o que está digitado no app.
2. **Firewall do Windows** — precisa de uma regra de entrada (Inbound, Allow) para
   `obs64.exe` no perfil de rede ativo (Domain/Private/Public — `Get-NetConnectionProfile`
   mostra qual). O instalador do plugin não mexe no firewall automaticamente.
3. **Isolamento de cliente (AP/client isolation) no roteador ou repetidor** — comum em
   redes de convidados e em alguns repetidores/mesh: bloqueia tráfego *entre dispositivos*
   da mesma rede mesmo com internet funcionando para cada um. Precisa ser desligado nas
   configurações do roteador/repetidor; não tem como contornar por software.
4. **Roteamento errado no celular** — se a rede Wi-Fi não tem acesso à internet (comum
   nesse tipo de instalação local), o Android pode preferir a rede móvel para tráfego novo
   mesmo com o Wi-Fi conectado. `UcspSender` já força o socket a sair pela rede Wi-Fi
   ativa (`bindToWifiNetworkIfAvailable()`) especificamente por causa disso — se ainda
   assim não funcionar, confirme que o celular está mesmo com Wi-Fi ativo (não só
   "conectado, mas sem uso de dados") e que não há um app de VPN/proxy no celular
   competindo pela rota padrão.
5. **Porta duplicada** — duas fontes "UCSP Camera Source" na mesma porta: a segunda falha
   o `bind()` (`WSAEADDRINUSE`, log explica isso). Use portas diferentes por telefone ou
   (Fase 1+) a mesma porta com o dropdown de dispositivo (ver abaixo).

### Multi-câmera: várias fontes, uma ou várias portas

Duas formas de usar mais de um celular ao mesmo tempo, cada um em sua própria cena:

- **Uma porta por celular** (mais simples): cada app aponta para uma porta diferente
  (5600, 5601, ...) e cada fonte do OBS usa a porta correspondente. Não precisa de nada
  além do que já existe.
- **Uma porta só, várias fontes** (mais prático em evento — não precisa coordenar portas
  entre telefones): todos os celulares apontam para a mesma porta; nas propriedades de
  cada fonte do OBS, o dropdown **"Dispositivo"** lista quem está enviando pacotes
  naquela porta agora (`IP:porta`, atualizado com o botão "Atualizar Lista de
  Dispositivos") — escolha ali qual telefone essa fonte/cena deve mostrar. Com "Automático"
  selecionado, a fonte só exibe vídeo enquanto houver exatamente um remetente ativo na
  porta (evita misturar dois streams por engano).

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

- [x] Multi-câmera básico: seleção de dispositivo por IP no plugin (`SenderRegistry`,
      dropdown "Dispositivo" nas propriedades da fonte) — várias fontes podem compartilhar
      uma porta, cada uma travada num telefone diferente
- [ ] Multi-câmera com sync via `StreamId` + HELLO real (identificação estável por
      dispositivo em vez de IP, que muda se o DHCP renovar o endereço)
- Sender nativo NDK/zero-copy (só se profiling mostrar que o caminho Kotlin é o gargalo)
