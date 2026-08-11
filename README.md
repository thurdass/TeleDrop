# TeleDrop

TeleDrop monitora uma pasta de downloads no Ubuntu/Linux, espera cada pasta ficar estável, cria volumes ZIP progressivos e envia um volume por vez para um chat do Telegram. A pasta original é somente leitura para o programa: ele nunca a move, renomeia, altera ou apaga.

O projeto usa Java 21 e somente a biblioteca padrão. Não há dependência de framework nem biblioteca de cliente Telegram.

## Limites atuais do Telegram

De acordo com a documentação oficial consultada em agosto de 2026:

- o Bot API oficial (`https://api.telegram.org`) permite que `sendDocument` envie arquivos de até **50 MB**;
- um novo arquivo é enviado como `multipart/form-data`; `file_id` e URL são alternativas para arquivos já existentes ou acessíveis por URL;
- `getFile` no endpoint oficial permite download de arquivos de até **20 MB**;
- o Local Bot API Server permite upload de até **2000 MB**, download sem limite de tamanho e URI `file://` local; o servidor local recebe requisições HTTP.

Fontes:

- [Telegram Bot API — sendDocument e InputFile](https://core.telegram.org/bots/api#senddocument)
- [Telegram Bot FAQ — limites de upload/download](https://core.telegram.org/bots/faq#how-do-i-upload-a-large-file)
- [Telegram Bot API Server — modo local](https://github.com/tdlib/telegram-bot-api#usage)
- [Tabela oficial de limites](https://core.telegram.org/bots/features#local-server)

O valor padrão de `telegram.part.max.mb` é **45 MB**. Ele fica abaixo dos 50 MB para deixar margem para o multipart e pequenas diferenças de medição. Para usar volumes grandes, configure o Local Bot API Server e use, por exemplo, `telegram.part.max.mb=1800`. Não use 2000 MB cegamente.

## Arquitetura para pastas grandes

O programa não cria um ZIP de 100 GB. Ele faz o seguinte:

1. `WatchService` observa somente a pasta raiz e sinaliza novas subpastas.
2. `FolderCompletionChecker` faz snapshots recursivos em intervalos configuráveis. Ele acompanha arquivos, diretórios, tamanho total, última modificação e um fingerprint de caminhos/metadados.
3. A pasta só entra na fila depois de permanecer estável pelo período configurado e possuir pelo menos um arquivo.
4. `VolumePlanner` percorre a árvore em ordem determinística e calcula volumes apenas com metadados. O conteúdo não é carregado na memória.
5. `VolumeBuilder` cria um volume `.tmp` em streaming, fecha-o e renomeia-o atomicamente.
6. O volume é enviado com `HttpClient` e `BodyPublishers.ofFile`, dentro de um multipart cujo tamanho é conhecido.
7. Somente depois da resposta `{"ok":true}` o estado é salvo e o volume local é apagado.
8. O processo continua no próximo volume.

Os ZIPs usam entradas `STORED` (sem compressão). Essa escolha evita uma estimativa incerta de compressão e permite garantir que o arquivo final fique abaixo do limite. O custo é que o tamanho tende a ser o tamanho original mais o pequeno overhead do ZIP.

Há duas passagens streaming por cada entrada: uma para CRC32, exigida pelo formato ZIP armazenado, e outra para copiar os bytes. O buffer padrão é 64 KiB e os tamanhos são tratados com `long`. O consumo de RAM não cresce com 20, 50 ou 100 GB; ele cresce apenas com metadados de nomes/entradas necessários para o plano.

## Arquivos individuais maiores que um volume

Um arquivo que não cabe em um volume é dividido diretamente dentro dos ZIPs:

```text
video.mkv.part001
video.mkv.part002
video.mkv.part003
```

O `manifest.json` registra o caminho original, número da parte, volume, offset e tamanho de cada pedaço. Depois de extrair todos os volumes em uma única pasta, a reconstrução pode ser feita, por exemplo:

```bash
cat "video.mkv.part001" "video.mkv.part002" "video.mkv.part003" > "video.mkv"
```

Para subpastas, use o caminho correspondente. O manifest deve ser a referência definitiva para a ordem. Os volumes têm SHA-256 quando `checksum.enabled=true`.

## Configuração

Copie o exemplo e preencha os valores:

```bash
cp config.example.properties config.properties
chmod 600 config.properties
```

Propriedades principais:

```properties
watch.folder=/home/USUARIO/Downloads
folder.stable.seconds=60
folder.scan.interval.seconds=10

telegram.bot.token=TOKEN_AQUI
telegram.chat.id=CHAT_ID_AQUI
telegram.api.base.url=https://api.telegram.org
telegram.part.max.mb=45
telegram.concurrent.uploads=1
telegram.retry.max=5
telegram.retry.delays.seconds=5,15,30,60,120
telegram.connect.timeout.seconds=30
telegram.request.timeout.seconds=86400

temp.folder=/home/USUARIO/Downloads/.telegram-upload
data.folder=./data
checksum.enabled=true
archive.buffer.kb=64
shutdown.await.seconds=30
```

`telegram.part.max.mb` é interpretado em MB decimais. O programa rejeita valores iguais ou maiores que 50 MB quando o host é `api.telegram.org` e rejeita valores acima de 2000 MB em qualquer endpoint.

Para Local Bot API Server:

```properties
telegram.api.base.url=http://127.0.0.1:8081
telegram.part.max.mb=1800
```

O servidor local oficial precisa ser instalado/configurado separadamente com `api_id` e `api_hash`. A implementação continua usando multipart streaming para funcionar da mesma forma nos dois endpoints; não depende do recurso opcional de URI `file://`.

## Criando o bot e obtendo `chat_id`

1. Abra `@BotFather` no Telegram, use `/newbot` e guarde o token somente em `config.properties`.
2. Envie uma mensagem para o bot no chat desejado.
3. Consulte, temporariamente, `getUpdates` usando o token e procure `message.chat.id` na resposta:

   ```text
   https://api.telegram.org/botSEU_TOKEN/getUpdates
   ```

   Para um grupo, adicione o bot e envie uma mensagem nele. IDs de grupos normalmente são negativos. Para um canal, o bot precisa das permissões necessárias e também pode ser usado um username de canal aceito pelo Bot API.
4. Coloque o valor em `telegram.chat.id` e remova o token de qualquer histórico ou arquivo não ignorado pelo Git.

Se o bot tiver webhook configurado, `getUpdates` pode não retornar mensagens até o webhook ser removido. Faça essa manutenção conscientemente antes de consultar updates.

## Execução no Ubuntu

É necessário ter um JDK 21 disponível:

```bash
./build.sh
./run.sh
```

Também é possível passar outro arquivo de configuração:

```bash
./run.sh /caminho/para/minha-config.properties
```

O build usa diretamente `javac --release 21`. O `pom.xml` existe para abrir o projeto no IntelliJ/Maven, mas não adiciona dependências de runtime.

## Execução pelo IntelliJ IDEA

1. Abra a pasta do projeto e aguarde o IntelliJ reconhecer o `pom.xml`.
2. Selecione um JDK 21 para o projeto.
3. Use `com.thurdass.telegramwatcher.Main` como classe principal.
4. Defina `config.properties` como argumento de programa, caso ele não esteja no diretório de trabalho.
5. Execute. O diretório `data` e a pasta temporária são criados automaticamente.

## Retomada e estados

Cada pasta possui um arquivo em `data/upload-state/`, com:

- caminho e snapshot original;
- tamanho, quantidade de arquivos e diretórios;
- quantidade total de volumes;
- próxima parte;
- volumes construídos/enviados, tamanho e SHA-256;
- status e erro mais recente;
- confirmação do manifest.

O arquivo é salvo em formato `.properties` usando arquivo temporário e movimentação atômica quando suportada. Um `.tmp` de volume interrompido nunca é considerado enviado; ele é descartado e reconstruído no próximo processamento. Um volume final preservado após falha de upload é reutilizado.

Após reiniciar, as pastas existentes são monitoradas novamente. Quando estiverem estáveis, estados `UPLOADING`, `FAILED` e `BUILDING` retomam a próxima parte pendente. O máximo padrão de uploads simultâneos é um.

Como o Bot API não oferece uma chave de idempotência para `sendDocument`, existe uma pequena janela inevitável: se o processo cair depois de o Telegram confirmar e antes de o estado local ser salvo, a parte pode ser enviada novamente no reinício. O arquivo local é mantido até a confirmação e o desenho privilegia não perder dados.

## Espaço em disco

O caminho padrão é:

```text
.telegram-upload/
└── Nome da Pasta/
    ├── Nome da Pasta.part001.zip
    └── manifest.json
```

Normalmente há somente um volume em construção ou aguardando confirmação. Volumes confirmados são removidos imediatamente. O WatchService ignora completamente `.telegram-upload`; a pasta original nunca é usada como destino temporário.

Se a pasta original mudar depois de ser considerada estável, o estado é marcado como `FAILED` para não misturar uma parte antiga com uma parte nova. Deixe o download completamente parado antes de tentar novamente.

## Logs esperados

Os logs usam marcadores simples:

```text
[WATCHER] Monitorando /home/user/Downloads
[NEW] Curso detectado
[SCAN] Curso - 52.80 GB - 831 arquivos - 42 diretórios
[STABLE] Curso - sem alterações por 60s
[COMPLETE] Download concluído
[QUEUE] Adicionado à fila de upload
[VOLUME] Criando Curso.part001.zip...
[UPLOAD] Enviando Curso.part001.zip (parte 1/50)
[SUCCESS] Curso.part001.zip enviado
[CLEANUP] Curso.part001.zip removido localmente
```

Erros de upload usam retry com backoff e, quando o Telegram envia `retry_after` (por exemplo, HTTP 429), o maior dos dois tempos é respeitado. Depois do limite, o estado vira `FAILED`, o arquivo permanece local e outras pastas continuam sendo processadas.

## Encerramento

`Ctrl+C` fecha o `WatchService`, para aceitar novos trabalhos, aguarda os workers pelo tempo configurado e interrompe apenas o que não terminou nesse prazo. Estados de construção/upload são salvos antes da operação longa; arquivos `.tmp` incompletos são retomados com segurança depois.

## Limitações deliberadas

- pastas vazias nunca são consideradas downloads concluídos;
- links simbólicos e entradas não regulares são ignorados, sem seguir links para fora da pasta;
- não há compressão ZIP, pois a prioridade é limite de tamanho previsível e baixo uso de CPU/RAM;
- a exclusão automática se limita a arquivos criados em `temp.folder` pelo próprio pipeline;
- o Bot API oficial não é adequado para volumes de centenas de MB; use Local Bot API Server ou mantenha o limite padrão de 45 MB.

Nunca versione `config.properties`, o token, `data/` ou `.telegram-upload/`. O `.gitignore` já cobre esses caminhos; mantenha também permissões restritivas no arquivo de configuração.
