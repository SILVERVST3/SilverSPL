# SilverSPL — Analisador de espectro e SPL (Android)

App para o Samsung Galaxy A55 (funciona em qualquer Android 8+) que usa o microfone do celular como medidor:

- **Medidor SPL** com ponderações A, C e Z, tempos Fast (125 ms) e Slow (1 s), Leq, Máx, Mín e Pico Z, indicação de saturação.
- **Analisador de espectro**: bandas de 1/1, 1/3 e 1/6 de oitava ou FFT de banda estreita (4096/8192/16384 pontos), com média (sem/rápida/lenta/infinita), pico retido, congelar e cursor (toque no gráfico).
- **Correção do microfone direto no app**: curvas pré-definidas, curva informada pelo próprio Android, importação de arquivo de calibração ou **medição da curva com um decibelímetro** (assistente).
- **Calibrar (ajuste externo)**: compara com um decibelímetro, ou com um calibrador acústico de 94/114 dB, mais ajuste fino ±0,1/±1 dB.
- **Gerador de sinais**: seno (1/3 de oitava ou frequência livre), ruído rosa e branco.

## Como compilar (GitHub Actions)

1. Crie um repositório novo no GitHub e envie todo o conteúdo desta pasta (incluindo `.github/` e `app/silverspl.jks`).
2. O workflow **Build APK** roda sozinho a cada push (ou em *Actions → Build APK → Run workflow*).
3. Ao terminar, baixe o artefato **SilverSPL-apk**, descompacte e instale o `SilverSPL.apk` no celular.

O APK é assinado com a chave fixa `app/silverspl.jks`, então as versões novas instalam por cima das antigas sem precisar desinstalar.
Android compila no runner Linux (`ubuntu-latest`), que já vem com o Android SDK.

## Precisão: de onde vêm os números

- **Fonte UNPROCESSED** (padrão): o Android desliga ganho automático, supressão de ruído e equalização de voz. O documento de compatibilidade do Android (CDD) exige, para essa fonte, que **94 dB SPL a 1 kHz resulte em −36 dBFS**, resposta dentro de ±10 dB entre 100 Hz e 7 kHz e ±30 dB entre 7 e 22 kHz. Por isso o offset nominal é **130 dB**.
- Para **VOICE_RECOGNITION** o CDD recomenda 90 dB SPL = −22,35 dBFS (offset 112,35 dB) e ±3 dB de 100 Hz a 4 kHz.
- Na prática cada aparelho erra alguns dB. **Calibre** com um decibelímetro ou calibrador sempre que puder.
- O medidor SPL filtra no domínio do tempo (FIR de fase linear com a correção da curva + filtros IIR A/C, normalizados em 1 kHz). Em teste: erro das ponderações A/C ≤ 0,05 dB de 20 Hz a 16 kHz; respostas Fast/Slow conforme a IEC 61672.
- Limites físicos do celular: ruído próprio de ~30–35 dB(A) e saturação em torno de 110–120 dB SPL.

## Curvas do microfone

Convenção (igual aos arquivos de calibração do REW/miniDSP/Dayton): valor **positivo = o mic lê alto** nessa frequência; o app **subtrai** a curva.

| Curva | O que é |
|---|---|
| Plana | Sem correção. |
| Smartphone MEMS típico (estimada) | Modelo genérico de MEMS embutido: graves −3 dB em ~50 Hz, ressonância do canal acústico em ~12,5 kHz (+4 dB), leve reforço de difração do corpo acima de 4 kHz. |
| MEMS c/ ressonância forte (estimada) | Graves −3 dB em ~80 Hz, ressonância em ~9 kHz (+8 dB). |
| Do aparelho | Se o Android do celular informar a resposta do microfone (API `MicrophoneInfo`), ela aparece aqui automaticamente. Muitos aparelhos deixam vazio. |
| Arquivo importado | Texto com `frequência dB` por linha (aceita `.txt`/`.cal` do REW, miniDSP UMIK, Dayton). Linhas com `*`, `#` ou `"` são comentários. |
| Medida com decibelímetro | Gerada pelo assistente do app (abaixo). **É a mais precisa para o seu A55.** |

A Samsung não publica a curva do microfone do A55, e não existe base pública confiável com essa curva — por isso as curvas "estimadas" são só modelos físicos típicos, e o app traz o assistente para medir a curva real.

## Calibrar (ajuste externo)

1. **Com decibelímetro**: celular e decibelímetro lado a lado (o microfone do celular fica na borda de baixo), mesma ponderação (A ou C) e o decibelímetro em Slow/Leq. Use um som estável (ruído rosa do Gerador numa caixa de som). Toque **Medir**, digite a leitura do decibelímetro e **Aplicar**.
2. **Com calibrador acústico**: encoste o calibrador no furo do microfone, toque **94 dB** ou **114 dB**.
3. **Ajuste fino**: botões −1, −0,1, +0,1, +1 dB; **Restaurar nominal** volta ao padrão.

O ajuste é salvo separadamente para cada fonte de áudio e cada entrada (interna ou microfone USB externo).

## Medir a curva com decibelímetro (assistente)

Na tela **Curva → Medir curva com decibelímetro…**:

1. Escolha a frequência (◀ ▶, 1/3 de oitava) e toque o tom (botão *Tocar tom*, de preferência numa caixa de som externa).
2. Escolha a ponderação que o decibelímetro está usando (A, C ou Z): o app converte a leitura para Z naquela frequência.
3. **Medir 5 s** → digite a leitura do decibelímetro → **Adicionar ponto**. O app avança para a próxima frequência.
4. Repita em várias frequências (inclua 1 kHz) e toque **Salvar curva**. O app normaliza a curva em 1 kHz e ajusta o offset automaticamente.

Dicas: volume bem acima do ruído ambiente (75–90 dB), evite reflexões perto de paredes e não segure o celular pela borda do microfone. Abaixo de ~50 Hz a correção fica limitada pelo limite de correção (padrão ±20 dB) e pela resolução da FFT.

## Estrutura do código

```
app/src/main/java/br/silver/spl/
  MainActivity.kt      tela principal (layout em código, sem XML)
  Dialogs.kt           Curva, Calibrar, Assistente de curva, Gerador
  Store.kt             preferências, offsets e curvas salvas
  audio/AudioEngine.kt captura AudioRecord float 48 kHz, desliga AGC/NS/AEC
  audio/ToneGenerator.kt gerador seno/rosa/branco
  dsp/Fft.kt           FFT radix-2 e espectro de potência (Hann)
  dsp/SplMeter.kt      medidor SPL no tempo (FIR overlap-save + IIR A/C + Fast/Slow/Leq)
  dsp/Analyzer.kt      orquestra medidor, espectro, bandas e capturas de calibração
  dsp/Acoustics.kt     ponderações IEC 61672 e bandas IEC 61260
  dsp/MicCurve.kt      curvas, interpolação log-f, leitura/escrita de arquivos
```
