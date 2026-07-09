# Khossa Agent

Agente Android estilo Sova AI / Siri: controla o telefone via API de
Acessibilidade e ferramentas nativas, ativado por voz ("Olá, Khossa"), com
memória local e a IA que você escolher (BYOK — sua própria chave, com
rotação automática entre várias).

Provedores suportados: **Groq**, OpenAI, Anthropic Claude, Google Gemini, DeepSeek.

## O que já está implementado

- `AssistantActivity.kt` + `ui/compose/` — ecrã principal em **Jetpack
  Compose**: botão de falar com animação pulsante enquanto ouve, histórico de
  conversa, atalhos de permissões e para as configurações avançadas.
- `MainActivity.kt` — configurações avançadas: provedor/modelo, chaves de
  API (com rotação automática), modo supervisão, **modo autónomo**, modo
  Live, skills e log técnico em tempo real.
- `AgentAccessibilityService.kt` — lê a árvore de UI da tela e executa cliques,
  toques longos, digitação, scroll, back/home e gestos por coordenadas.
- `UiTreeParser.kt` — converte a árvore de acessibilidade em JSON compacto.
- `LlmClient.kt` — chama a API do provedor escolhido, extrai a próxima ação e
  roda a chave automaticamente tanto em limite de uso (429) como em chave
  inválida/sem permissão (401/403/400).
- `TaskPlanner.kt` — loop: captura tela → junta memória relevante → pergunta
  à IA → executa ação → repete (máx. 25 passos por tarefa, por segurança),
  registando cada tarefa concluída na memória local.
- `ActionExecutor.kt` — traduz a decisão da IA em ação real; decide quando
  pedir confirmação (modo supervisão / ações sensíveis / modo autónomo).
- `memory/` — **memória local em Room/SQLite**: contactos importantes,
  preferências, histórico de tarefas e deteção simples de hábitos repetidos.
- `voice/VoiceInteractionManager.kt` — TTS + STT contínuo (Modo Live).
- `voice/WakeWordDetector.kt` + `voice/KhossaVoiceService.kt` — **ativação por
  palavra-chave "Olá, Khossa"** e execução de tarefas **em segundo plano**
  (foreground service com notificação persistente), com confirmações faladas
  quando não há ecrã aberto.
- `KhossaNotificationListenerService.kt` — leitura das últimas notificações e
  controlo de música (play/pause/próxima) via sessão de media ativa.
- `tools/AndroidControlTools.kt` — abrir apps por nome, discador, mensagem
  (SMS/WhatsApp pré-preenchida), alarme, notificações, seletor de ficheiros.
- `tools/ReminderTools.kt` — lembretes com hora exata ("me lembra às 15h"),
  via `AlarmManager` + notificação, sem precisar abrir nenhum app.
- `skills/` — procedimentos reutilizáveis (diferente da memória: aqui é
  "como fazer X", não "factos sobre o utilizador").

## Como compilar

Você precisa do **Android Studio** (recomendado) ou do Gradle + Android SDK
via linha de comando.

### Opção A — Android Studio (mais fácil)
1. Abra o Android Studio → "Open" → selecione a pasta `KhossaAgent`.
2. Deixe o Android Studio sincronizar o Gradle (ele baixa o `gradle-wrapper.jar`
   automaticamente, que não veio incluso neste ZIP por não ter acesso à
   internet no momento em que gerei os arquivos).
3. Build → Build Bundle(s)/APK(s) → Build APK(s).
4. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção B — Linha de comando (Termux/Linux com Gradle instalado)
```bash
cd KhossaAgent
gradle wrapper --gradle-version 8.7   # gera o wrapper que faltava
./gradlew assembleDebug
```
O APK gerado fica em `app/build/outputs/apk/debug/app-debug.apk`.

> Nota: compilar apps Android nativamente pelo Termux é possível mas
> trabalhoso (requer Android SDK command-line tools). Se travar em algum
> passo, o caminho mais rápido é abrir o projeto num PC/notebook com Android
> Studio, ou usar um serviço de build na nuvem.

## Como usar no celular

1. Instale o APK. O ícone abre agora o **ecrã do Assistente** (Compose).
2. Toque no ícone de permissões (🔔) no topo, ou vá a **"⚙️ Configurações
   avançadas" → "Ativar serviço de acessibilidade"** → nas configurações do
   Android, procure "Khossa Agent" e ative.
   - **Android 13+**: se o Android disser que a configuração está restrita,
     vá em Configurações → Apps → Khossa Agent → toque nos 3 pontinhos →
     "Permitir configurações restritas", depois tente ativar de novo.
3. Em "Configurações avançadas": escolha o provedor de IA (ex.: Groq ou
   Gemini) e o modelo, cole sua(s) chave(s) de API.
4. Volte ao ecrã principal e toque no microfone para falar um comando, ou
   ligue "Escuta em segundo plano" para poder dizer "Olá, Khossa" a
   qualquer momento sem abrir o app.
5. (Opcional) Para lembretes com hora exata: toque em "⏰ Alarmes exatos" e
   permita. Para controlar música e ler notificações: toque em
   "🔔 Notificações" e ative o acesso a notificações do Khossa Agent.

## Rotação automática de chaves

Você pode colar **várias chaves de API** no mesmo campo, uma por linha (ou
separadas por vírgula) — por exemplo, várias chaves do Gemini. Funcionamento:

- `KeyRotationManager` guarda a lista e usa round-robin entre elas.
- Se uma chave receber erro **429** (limite atingido) de qualquer provedor
  (Groq, OpenAI, Claude, Gemini ou DeepSeek), ela entra em "cooldown" — pelo
  tempo indicado no header `Retry-After` da resposta, ou 60s por padrão.
- Se uma chave receber erro **401/403, ou 400 do tipo "API key inválida"**,
  também entra em cooldown (mais longo, 1h) e a rotação passa pra próxima —
  não só limite de uso, também chave errada/expirada.
- Isso acontece de forma transparente durante a execução da tarefa; o log
  mostra quando uma chave é colocada em cooldown e a rotação acontece.
- Se todas as chaves estiverem em cooldown ao mesmo tempo, o agente para e
  avisa no log.
- As chaves ficam salvas localmente por provedor (trocar de provedor no
  spinner carrega automaticamente a lista salva daquele provedor).

### Chaves "de fábrica" (uso pessoal)

Para conveniência, o campo de chaves do Gemini vem pré-preenchido (só na
primeira vez, editável/removível depois) com as chaves guardadas em
`secrets.properties`, **na raiz do projeto — fora do controlo de versão**
(está no `.gitignore`). Isto é injetado em `BuildConfig` pelo
`app/build.gradle` e lido por `config/SecretKeys.kt`; nenhuma chave fica
hardcoded em código versionado.

> ⚠️ **Aviso importante**: as 5 chaves fornecidas não seguem o formato
> oficial das chaves de API do Gemini/Google AI Studio, que começam sempre
> por `AIzaSy...` (geradas em https://aistudio.google.com/apikey). O formato
> `AQ.Ab8...` sugere que vêm de outra origem (por exemplo, uma sessão da
> conta Google, não uma chave de API de projeto Cloud) e é bem provável que
> a chamada REST pública usada pelo `LlmClient` (`generativelanguage.googleapis.com`)
> as rejeite com 400/401/403 — o que a nova lógica de rotação já trata sem
> travar o agente, mas convém confirmar: teste uma delas com um `curl`
> simples antes de confiar na automação. Se forem mesmo chaves de sessão,
> considere gerar chaves reais no AI Studio para o Gemini funcionar de
> verdade — e, já que foram coladas neste chat, é boa prática invalidá-las/
> trocá-las se possível, tal como faria com qualquer segredo exposto numa
> conversa.

## Memória local (Room/SQLite)

O agente guarda, só no telefone:

- **Contactos importantes** (`remember_contact`): quem é "a mãe", "o chefe",
  etc., para não perguntar de novo.
- **Preferências** (`remember_preference` / `recall_preference`): pares
  chave/valor livres (ex.: `hora_dormir=22:30`).
- **Histórico de tarefas**: toda tarefa concluída/cancelada/falhada fica
  registada.
- **Hábitos**: quando o mesmo tipo de objetivo se repete, a contagem sobe —
  base para futuramente sugerir "quer que eu faça isto todo dia às 15h?".

Nada disto sai do telefone; só o texto relevante entra no prompt mandado à
IA escolhida, exatamente como o histórico de ações já fazia.

## Ativação por voz e segundo plano

- **Modo Live** (na tela avançada): o agente fala o que está a fazer e ouve
  continuamente enquanto uma tarefa corre em primeiro plano.
- **Escuta em segundo plano** (botão no ecrã principal): liga um foreground
  service (`KhossaVoiceService`) que fica à espera da frase "Olá, Khossa" —
  ao ouvir, pergunta o comando (ou já usa o que vier a seguir na mesma
  frase) e executa a tarefa sozinho, com uma notificação persistente a
  mostrar o que está a fazer. Confirmações de ações sensíveis, sem ecrã
  aberto, são feitas por voz ("posso continuar? diz sim ou não").
- A deteção da wake word usa o reconhecedor de fala do próprio Android (não
  é um motor offline dedicado tipo Porcupine/Vosk) — ver comentário em
  `WakeWordDetector.kt` para o porquê e o caminho de evolução.

## Modo autónomo

Além do "modo supervisão" (confirma tudo), agora há um **modo autónomo**:
quando ligado, ações sensíveis "leves" (ligar, criar alarme) não pedem
confirmação — mas **mandar mensagem** e ações em apps financeiros/bancários
continuam a pedir **sempre**, mesmo em modo autónomo (regra fixa em
`ActionExecutor.HARD_CONFIRM_ACTIONS`, não é possível desligar pela UI).

## Segurança

- As chaves de API ficam salvas apenas localmente no `SharedPreferences` do
  telefone (não são enviadas a nenhum servidor além da própria API do
  provedor escolhido); as chaves de fábrica ficam em `secrets.properties`,
  fora do controlo de versão.
- Ações em apps que parecem ser de banco/carteira/pagamento, e mandar
  mensagem (`send_message`), **sempre** pedem confirmação manual — mesmo com
  modo supervisão e modo autónomo desligados/ligados como for.
- O agente nunca liga, nunca envia SMS/WhatsApp e nunca apaga ficheiros
  sozinho: essas ações sempre abrem o app do sistema já preenchido/pronto
  para a pessoa confirmar com o próprio dedo.
- Há um limite de 25 passos por tarefa para evitar loops infinitos.
- Recomendo manter o modo supervisão ligado até confiar bem no comportamento
  do agente com o modelo escolhido, e só ligar o modo autónomo/a escuta em
  segundo plano depois disso.

## Próximos passos sugeridos

- Persistir o log técnico também num arquivo (hoje só existe na tela).
- Motor de wake-word 100% offline (Vosk/Porcupine) para a escuta em segundo
  plano não depender do reconhecedor online do Android.
- Botão de emergência para revogar o serviço de acessibilidade a qualquer
  momento (atalho: Configurações → Acessibilidade → Khossa Agent → Desativar).
- UI para editar/apagar contactos e preferências guardados na memória
  (hoje só existe programaticamente via `MemoryRepository`).
- Sugerir automações a partir dos "hábitos detetados" (`detectedHabits()`),
  em vez de só contar repetições.
- Publicar como APK direto (fora da Google Play), já que apps de automação
  universal via Acessibilidade costumam ser rejeitados na Play Store.
