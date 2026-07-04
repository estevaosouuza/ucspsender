<div align="center">

# UCSP — Ultra-low-latency Camera Streaming Protocol

**Transforme qualquer celular Android numa câmera de transmissão profissional para o OBS Studio.**

Sem fios, sem NDI, sem travamentos. Um protocolo UDP próprio, desenhado do zero para
redes Wi-Fi difíceis (igrejas, eventos, salas com dezenas de dispositivos conectados),
onde a prioridade nunca é "imagem perfeita" — é **imagem que nunca trava**.

[![License: GPL v2](https://img.shields.io/badge/license-GPL--2.0-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/app-Android-3DDC84?logo=android&logoColor=white)](android-app/)
[![Platform: OBS Studio](https://img.shields.io/badge/plugin-OBS%20Studio-302E31?logo=obsstudio&logoColor=white)](obs-plugin/)
[![Status: Fase 1](https://img.shields.io/badge/status-Fase%201%20est%C3%A1vel-success.svg)](docs/roadmap.md)

[**⬇ Downloads**](#-downloads) · [Como funciona](#-como-funciona) · [Instalação](#-instalação-rápida) · [Protocolo](docs/protocol/ucsp-spec.md) · [Roadmap](docs/roadmap.md)

</div>

---

## O problema que o UCSP resolve

Ferramentas como NDI foram feitas para redes cabeadas e bem dimensionadas. Num ambiente
real — uma igreja com 40 celulares na mesma Wi-Fi, um evento com roteador saturado — elas
tentam manter *qualidade* a qualquer custo, e o resultado é a imagem congelando por
segundos inteiros.

O UCSP inverte essa prioridade:

> **Perder qualidade antes de perder fluidez.** A imagem pode ficar temporariamente pior
> (resolução menor, um quadro corrompido descartado) — mas o vídeo **nunca para**.

Isso é implementado de propósito no protocolo, não é um efeito colateral: pacotes UDP
puros (sem handshake, sem retransmissão), correção de erro (FEC) embutida, remontagem de
quadros que descarta rápido em vez de esperar, e um canal de retorno que pede um novo
quadro-chave automaticamente quando algo se perde — tudo para bater uma latência
alvo de **80–300 ms**, mesmo em rede ruim.

## ⬇ Downloads

| Componente | O que é | Download |
|---|---|---|
| **Plugin para OBS Studio** | Instalador Windows (.exe) — adiciona a fonte "UCSP Camera Source" ao OBS | [Última versão](../../releases/latest) |
| **App Android (sender)** | APK — instale no celular que vai servir de câmera | [Última versão](../../releases/latest) |

> Precisa dos dois lados: o plugin no PC onde roda o OBS, e o app no celular que vai
> filmar. Veja [Instalação rápida](#-instalação-rápida) abaixo.

## ✨ Como funciona

| 📱 App Android (sender) | 🖥 Plugin OBS (`ucsp_source`) |
|---|---|
| Câmera | UDP Receiver |
| ↓ | ↓ |
| MediaCodec — H.264 Baseline, sem B-frame, GOP curto | Reassembly por FrameId — descarta rápido, nunca trava esperando |
| ↓ | ↓ |
| Packetizer UCSP — fragmenta em pacotes de ~1,4 KB + paridade XOR (FEC) | Decode H.264 (FFmpeg) |
| ↓ | ↓ |
| UDP Sender | Fonte de vídeo no OBS |

**→ vídeo:** UDP puro + FEC, do app pro plugin.
**← backchannel:** stats de perda/jitter + pedido de keyframe sob demanda, do plugin pro app (~50ms).

- **App Android**: captura a câmera, codifica em H.264 (perfil Baseline, sem B-frames,
  GOP de ~1s para recuperação rápida), fragmenta em pacotes UDP pequenos com paridade XOR
  (correção de 1 pacote perdido por grupo, sem precisar retransmitir) e envia direto pro PC.
- **Plugin OBS**: escuta na porta UDP, remonta os quadros por ID (descartando o que não
  chegar completo em vez de travar esperando), decodifica via FFmpeg e entrega o vídeo
  como uma fonte normal dentro de qualquer cena do OBS.
- **Canal de volta (backchannel)**: o plugin informa periodicamente ao celular a perda de
  pacotes, jitter e tempo de processamento — e pede um quadro-chave novo automaticamente
  sempre que um quadro fica impossível de decodificar (não precisa reiniciar o OBS).

Detalhes completos do formato de pacote em [`docs/protocol/ucsp-spec.md`](docs/protocol/ucsp-spec.md)
e da arquitetura em [`docs/architecture.md`](docs/architecture.md).

## 🚀 Instalação rápida

### 1. No PC (plugin do OBS)

1. Baixe o instalador em [Downloads](#-downloads) e execute (não precisa ser
   Administrador — instala em `%ProgramData%\obs-studio\plugins`, o mesmo local usado
   por outros plugins de terceiros do OBS).
2. Abra (ou reabra) o OBS Studio.
3. Numa cena, clique em **+** e adicione a fonte **"UCSP Camera Source"**.
4. Anote a porta configurada nela (padrão **5600**) — se precisar trocar, mude também no
   app do celular.

### 2. No celular (app Android)

1. Baixe o APK em [Downloads](#-downloads) e instale (permita "fontes desconhecidas" se
   o Android pedir).
2. Conecte o celular na **mesma rede Wi-Fi** do PC.
3. Abra o app, informe o **IP do PC** (`ipconfig` no Windows mostra o IP da rede local) e
   a porta (5600 por padrão).
4. Escolha resolução e FPS — quanto menor, menor a latência em rede ruim.
5. Toque em **Iniciar transmissão**.

O vídeo deve aparecer na fonte do OBS em poucos segundos. Se não aparecer, veja a seção
de diagnóstico em [`docs/roadmap.md`](docs/roadmap.md).

### Se a conexão travar

O plugin tem um botão **"Reset Connection"** (ou **"Reiniciar Conexão"**, em português)
nas propriedades da fonte — reconecta sem precisar reiniciar o OBS. Só tenha certeza de
que não existe mais de uma fonte UCSP na mesma cena tentando usar a mesma porta.

## 🧱 Estrutura do repositório

```
.
├── android-app/            # App Android (Kotlin) — sender
│   └── app/src/main/java/com/ucsp/sender/
│       ├── capture/        # CameraX + preview em tela cheia
│       ├── encode/         # MediaCodec H.264 Baseline
│       ├── network/        # Protocolo UCSP, FEC, socket UDP
│       ├── thermal/        # Monitoramento térmico (Fase 2)
│       └── adaptive/       # Motor adaptativo (Fase 2)
├── obs-plugin/              # Plugin nativo do OBS Studio (C++)
│   ├── src/ucsp/            # Protocolo, reassembly, FEC, decode, backchannel
│   └── installer/           # Script Inno Setup do instalador Windows
├── docs/
│   ├── architecture.md      # Visão geral do sistema
│   ├── roadmap.md           # Fases de desenvolvimento e status atual
│   └── protocol/ucsp-spec.md # Especificação normativa do protocolo (bytes na rede)
└── tools/                   # Utilitários standalone (sniffer UDP para debug)
```

## 🛠 Compilando a partir do código-fonte

Pré-requisitos:
- **Plugin OBS**: Visual Studio Build Tools (workload C++), CMake, vcpkg (FFmpeg).
- **App Android**: Android Studio / SDK (o NDK **não** é necessário).
- **Instalador Windows**: [Inno Setup 6](https://jrsoftware.org/isinfo.php).

Passo a passo completo (incluindo os comandos exatos usados neste projeto) em
[`docs/roadmap.md`](docs/roadmap.md) — Fase 0.

```bash
# Plugin OBS
cmake --preset windows-x64 -S obs-plugin
cmake --build obs-plugin/build_x64 --config RelWithDebInfo

# App Android
cd android-app && ./gradlew assembleDebug

# Instalador do plugin (requer o build acima)
ISCC.exe obs-plugin/installer/ucsp-plugin-setup.iss
```

## 📍 Status do projeto

O UCSP está na **Fase 1**: pipeline ponta a ponta funcional e testado em rede real
(câmera → codificação → UDP → OBS), com recuperação automática de quadro-chave e reset
manual de conexão. Fases seguintes (motor adaptativo de bitrate por condição de rede,
decodificação por hardware, dashboard de latência em tempo real) estão detalhadas em
[`docs/roadmap.md`](docs/roadmap.md).

## 📄 Licença

Este projeto é distribuído sob a [GNU GPL v2](LICENSE) — a mesma licença do OBS Studio,
já que o plugin usa a libobs diretamente.
