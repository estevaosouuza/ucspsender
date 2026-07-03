# UCSP Wire Protocol Specification (v1)

Este documento é a referência normativa do protocolo. O sender Android
(`android-app/.../network/UcspHeader.kt`) e o receiver do plugin OBS
(`obs-plugin/src/ucsp/ucsp_protocol.h`) são implementações **independentes** deste
documento — não compartilham código, apenas precisam concordar nos bytes na rede.

Todos os campos multi-byte são **little-endian**. Não assuma que `struct` casts em C++
respeitam este layout — parseie campo a campo (padding/alignment do compilador não é
garantido bater com a spec).

## 1. Header comum (32 bytes, presente em todo pacote UCSP)

| Offset | Tamanho | Campo | Descrição |
|---|---|---|---|
| 0 | 1 | `Version` | `= 1` |
| 1 | 1 | `PacketType` | `0`=VIDEO_DATA, `1`=FEC_PARITY, `2`=BACKCHANNEL_REPORT, `3`=KEYFRAME_REQUEST, `4`=HELLO, `5`=HELLO_ACK |
| 2 | 1 | `StreamId` | índice da câmera; `0` na Fase 1 (permite multi-câmera no futuro) |
| 3 | 1 | `Flags` | bit0=IsKeyframe, bit1=IsFecPacket, bits2-7 reservados |
| 4 | 4 | `FrameId` (uint32) | monotonicamente crescente, dá wrap em overflow |
| 8 | 2 | `PacketIndex` (uint16) | posição do pacote dentro do frame (0-based) |
| 10 | 2 | `TotalPackets` (uint16) | total de pacotes deste frame |
| 12 | 1 | `FecGroupSize` (uint8) | N do grupo FEC ao qual este pacote pertence |
| 13 | 1 | `Codec` | `0`=H264 (reservado p/ H265/AV1 futuramente) |
| 14 | 2 | `PayloadLength` (uint16) | bytes reais de payload que seguem o header |
| 16 | 8 | `PresentationTimestampUs` (uint64) | microssegundos de clock monotônico; igual para todos os pacotes de um mesmo frame; base para sync de clock (NTP/PTP-like) via HELLO/HELLO_ACK no futuro |
| 24 | 4 | `SequenceNumber` (uint32) | sequência global do stream inteiro (não reinicia por frame) — simplifica cálculo de loss/jitter no backchannel |
| 28 | 4 | `Reserved` | reservado para flags de nível de stream / epoch de sync de clock |

**Tamanho de datagrama alvo**: payload de até 1368 bytes → datagrama de até 1400 bytes
(32 header + 1368 payload), com folga sob a MTU típica de Wi-Fi (1500 bytes, considerando
overhead de IP/UDP).

## 2. VIDEO_DATA (`PacketType = 0`)

A unidade de acesso H.264 (Annex-B, saída do `MediaCodec`) é fragmentada em blocos de até
1368 bytes. O último bloco de cada frame só é zero-padded para o cálculo de FEC — o
`PayloadLength` sempre carrega o tamanho real.

**Regra crítica de SPS/PPS**: o buffer único `BUFFER_FLAG_CODEC_CONFIG` do MediaCodec
(SPS/PPS) deve ser cacheado pelo app Android e **reinjetado como NAL unit própria
imediatamente antes de todo NAL de keyframe (IDR)**. Isso garante que qualquer keyframe
enviado via UCSP seja decodificável de forma independente — essencial porque o plugin OBS
pode começar a escutar depois que o stream já começou, ou porque um keyframe pode ser
pedido no meio do stream via `KEYFRAME_REQUEST`.

## 3. FEC — XOR parity (`PacketType = 1`, estilo Pro-MPEG COP3, corrige 1 erro por grupo)

- Agrupado **por frame**, nunca cruzando fronteira de frame (mantém simples o descarte
  rápido de frame incompleto). Tamanho de grupo `N` configurável, default `5` (20% de
  overhead). Frames com menos de 2 pacotes de dados não geram FEC.
- Pacote FEC: `PacketType=1`, `Flags.bit1=1`, `FecGroupSize=N`, `PacketIndex` = índice do
  primeiro pacote do grupo.
- Payload = `[N × uint16 tabela de tamanhos reais]` seguido do **XOR dos N pacotes do
  grupo, cada um zero-padded a 1368 bytes**.
- **Recuperação**: se exatamente 1 pacote do grupo estiver faltando e o pacote de paridade
  estiver presente, faz XOR da paridade contra todos os chunks padded presentes para
  recuperar o chunk padded faltante, depois corta usando a tabela de tamanhos embutida.
  **2+ perdas no mesmo grupo não são recuperáveis** — o frame inteiro é descartado.
- Qualquer frame não decodificável dispara `KEYFRAME_REQUEST` imediatamente pelo
  backchannel (não espera o próximo ciclo de 50ms).

## 4. BACKCHANNEL_REPORT (`PacketType = 2`, OBS → celular, ~a cada 50ms)

Reusa o header de 32 bytes (campos Frame/Packet/FEC não usados) + payload:

| Campo | Tamanho | Descrição |
|---|---|---|
| `LastFrameIdReceived` | uint32 | |
| `PacketsExpectedWindow` | uint16 | desde o último report |
| `PacketsReceivedWindow` | uint16 | desde o último report |
| `EstimatedJitterMs` | uint16 | ponto-fixo, ms×10 |
| `EstimatedPacketLossPercent` | uint8 | 0–100 |
| `AvgFrameProcessingTimeMs` | uint16 | 1º pacote recebido → frame decodificado |
| `Flags` | uint8 | reservado |

Esses dados alimentam, na Fase 2, o motor adaptativo do Android (bitrate/resolução/FPS).

## 5. KEYFRAME_REQUEST (`PacketType = 3`, OBS → celular, sob demanda)

Apenas header, sem payload. Ao receber, o Android chama
`MediaCodec.setParameters(Bundle.of(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0))`.

## 6. HELLO / HELLO_ACK (`PacketType = 4/5`)

Formato reservado desde já (para o wire format nunca precisar mudar), mas **a Fase 1 não
exige handshake**: o plugin OBS aprende o `sockaddr` do celular a partir do primeiro
pacote `VIDEO_DATA` recebido, e envia o backchannel de volta para esse endereço.
HELLO/HELLO_ACK passam a ser usados de fato na Fase 2, para troca de offset de clock e
controle de sessão multi-câmera.

## 7. Reassembly (lado OBS)

Buffers de reassembly indexados por `FrameId`, descartando frames
incompletos/corrompidos **rapidamente** (nunca travando à espera) quando um `FrameId`
materialmente mais novo chega. Usa a flag `IsKeyframe` + o backchannel para pedir um
keyframe novo quando um frame keyframe é irrecuperável.
