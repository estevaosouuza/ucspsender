# Arquitetura

## Visão geral

```
[Android phone]                                   [PC com OBS Studio]
 CameraX (Surface)                                 obs-plugin (C++)
   -> MediaCodec H264                                -> UdpReceiver (WinSock2)
      (Baseline, sem B-frame,                         -> FrameReassembler (por FrameId)
       GOP curto)                                     -> FecDecoder (XOR recovery)
   -> UcspPacketizer                                   -> H264Decoder (FFmpeg/libavcodec)
      (chunks ~1368B)                                  -> obs_source_output_video2()
   -> FecEncoder (paridade XOR)                              |
   -> UcspSender (DatagramSocket)                            v
        --------- UDP (UCSP) --------->                 Fonte "UCSP Camera Source"
        <-------- backchannel ---------                     dentro de uma Scene do OBS
   -> BackchannelListener                            -> BackchannelSender (~50ms)
      (stats, keyframe request)                       -> KeyframeRequest sob demanda
   -> ThermalMonitor (Fase 2)
   -> AdaptiveController (Fase 2)
```

## Princípio de design

O caminho crítico (Camera → encode → rede → decode → render) é **unidirecional e sem
espera**: nunca há retransmissão TCP-like nem espera por acks para entregar um frame. A
única via de volta (backchannel) é informativa — usada para adaptar o *próximo* frame
(bitrate, resolução, pedido de keyframe), nunca para seguar frames já enviados.

Quando um frame não pode ser totalmente reconstruído (perda de pacote não coberta pelo
FEC), ele é **descartado imediatamente** — o pipeline nunca espera indefinidamente por um
pacote atrasado. Isso é o que implementa a filosofia "zero freeze, degradar qualidade
antes de perder fluidez".

## Por que UDP puro (não RTP/SRT/NDI)

- RTP/SRT/NDI otimizam para *integridade* (retransmissão, buffers maiores) — o que
  aumenta latência em redes ruins exatamente quando você mais precisa de fluidez.
  UCSP é um protocolo mínimo, desenhado apenas para este caso de uso, que aceita perda
  de qualidade like a troco de nunca travar.
- Ver [`docs/protocol/ucsp-spec.md`](protocol/ucsp-spec.md) para o formato de pacote.

## Componentes

- **android-app/**: app sender (Kotlin, CameraX + MediaCodec). Ver `docs/roadmap.md`
  Fase 1 para a lista de módulos.
- **obs-plugin/**: plugin receiver nativo do OBS (C++, OBS SDK + FFmpeg/libavcodec).
- **tools/udp-sniff.py**: script standalone para inspecionar pacotes UCSP crus na rede,
  útil para depurar o packetizer/reassembler sem precisar do OBS rodando.
