# TeleDrop

<p align="center">
  <strong>Large Folder → Telegram</strong>
</p>

<p align="center">
  A Java 21 automation tool for monitoring large folders and progressively uploading their contents to Telegram.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-F59E0B?style=for-the-badge&logo=openjdk&logoColor=white&labelColor=EA580C">
  <img src="https://img.shields.io/badge/Java_NIO-WatchService-9333EA?style=for-the-badge&logo=openjdk&logoColor=white&labelColor=6D28D9">
  <img src="https://img.shields.io/badge/HTTP-HttpClient-14B8A6?style=for-the-badge&labelColor=0F766E">
  <img src="https://img.shields.io/badge/Telegram-Bot_API-38BDF8?style=for-the-badge&logo=telegram&logoColor=white&labelColor=0284C7">
  <img src="https://img.shields.io/badge/Maven-Build-E11D48?style=for-the-badge&logo=apachemaven&logoColor=white&labelColor=9F1239">
  <img src="https://img.shields.io/badge/Ubuntu-Linux-F97316?style=for-the-badge&logo=ubuntu&logoColor=white&labelColor=C2410C">
</p>

---

## About

TeleDrop monitors a directory on Ubuntu/Linux and automatically detects new folders.

Instead of assuming that a folder is complete as soon as it appears, TeleDrop keeps monitoring its contents until the entire directory remains stable for a configurable amount of time.

Once the folder is considered complete, it enters an upload pipeline that:

1. analyzes its contents;
2. plans Telegram-compatible volumes;
3. creates one volume at a time;
4. uploads the volume;
5. waits for Telegram confirmation;
6. stores the upload state;
7. removes the temporary volume;
8. continues with the next one.

TeleDrop was designed specifically for **large directories**, including folders with:

```text
20 GB
50 GB
80 GB
100 GB
or more
```

The original folder is always treated as read-only data.

TeleDrop never automatically:

- deletes it;
- moves it;
- renames it;
- modifies its contents.

---

## Features

- Automatic folder detection
- Recursive directory monitoring
- Download completion detection
- Support for 20–100+ GB folders
- Progressive ZIP volume creation
- Low-memory streaming I/O
- Handling of individual files larger than one volume
- Multiple folders monitored simultaneously
- Sequential upload queue
- Configurable concurrent uploads
- Telegram Bot API integration
- Local Telegram Bot API Server support
- Upload retry with backoff
- Telegram `retry_after` handling
- Retry only for transient Telegram failures
- Persistent upload state
- Resume after restart or interruption
- SHA-256 integrity verification
- Manifest generation
- Predictable temporary disk usage
- Temporary disk-space preflight checks
- Single-instance process lock
- Recovery after `WatchService` event overflow
- Graceful shutdown
- Safe temporary file handling
- Original folder protection
- Configurable upload limits
- Automated tests for core processing flows
- No heavy runtime frameworks

---

## Technologies

### Core

- **Java 21**
- **Java NIO**
- **WatchService**
- **java.net.http.HttpClient**
- **ExecutorService**
- **BlockingQueue**
- **java.util.zip**
- **MessageDigest**
- **SHA-256**
- **Telegram Bot API**
- **Maven**
- **Ubuntu / Linux**

### Main Java APIs

```text
java.nio.file
java.nio.channels
java.net.http
java.util.concurrent
java.util.zip
java.security
java.io
```

TeleDrop uses only the **Java standard library at runtime**.

There is no Spring Boot and no external Telegram client library.

---

## How It Works

```text
New folder appears
        ↓
TeleDrop detects it
        ↓
Folder is monitored recursively
        ↓
Are files still changing?
        │
        ├── YES → keep monitoring
        │
        └── NO
             ↓
Folder remains stable
             ↓
Folder considered complete
             ↓
Added to upload queue
             ↓
Plan upload volumes
             ↓
Create part001
             ↓
Upload part001
             ↓
Telegram confirms
             ↓
Save state
             ↓
Delete temporary part001
             ↓
Create part002
             ↓
...
             ↓
All parts uploaded
             ↓
Send manifest
             ↓
Task completed
```

Example:

```text
~/Downloads/
├── Project Files/
├── Media Archive/
├── Backup/
└── Large Dataset/
```

Each directory is handled independently.

---

# Architecture

## Folder Monitoring

TeleDrop uses Java's `WatchService` to observe the configured root directory.

Example:

```text
~/Downloads/
├── Folder A/
├── Folder B/
└── Folder C/
```

When a new subdirectory appears, TeleDrop registers it as a possible processing task.

The root watcher does not wait for that directory to finish.

Monitoring is delegated to worker threads so the main watcher remains available to detect other folders.

This allows scenarios such as:

```text
Folder A → 40 GB still changing
Folder B → 82 GB still changing
Folder C → 26 GB still changing
```

to be monitored at the same time.

---

## Download Completion Detection

A folder is **not** considered complete simply because it exists.

Large folders may continue receiving:

- new files;
- new subdirectories;
- file modifications;
- file size changes;
- temporary files.

TeleDrop periodically creates a recursive snapshot of the directory.

A snapshot contains information such as:

```text
fileCount
directoryCount
totalSize
latestModification
```

The implementation can also maintain a fingerprint based on file paths and metadata.

Example:

```text
[SCAN] Large Folder - 52.80 GB - 831 files - 42 directories

[WAITING] Folder is still changing...

[STABLE] No changes for 20s
[STABLE] No changes for 40s
[STABLE] No changes for 60s

[COMPLETE] Folder is stable
```

If relevant content changes, the stability timer starts again.

Default configuration:

```properties
folder.stable.seconds=60
folder.scan.interval.seconds=10
```

Empty directories are never considered completed tasks.

---

## Performance During Scanning

Folders can contain thousands or tens of thousands of files.

TeleDrop therefore avoids unnecessarily frequent recursive scans.

The scan interval is configurable:

```properties
folder.scan.interval.seconds=10
```

Directory traversal is performed using Java NIO APIs such as:

```java
Files.walkFileTree(...)
```

Streams and filesystem resources are always closed after use.

File sizes are represented using `long`.

---

# Large Folder Processing

TeleDrop does **not** create a massive archive containing the entire original folder before uploading.

For example, it does not do this:

```text
100 GB folder
      ↓
100 GB ZIP
      ↓
Split ZIP
      ↓
Upload
```

That approach could require nearly twice the original amount of disk space.

Instead, TeleDrop uses progressive processing:

```text
Original folder
      ↓
Create volume 001
      ↓
Upload volume 001
      ↓
Telegram confirms
      ↓
Delete volume 001
      ↓
Create volume 002
      ↓
Upload volume 002
      ↓
Telegram confirms
      ↓
Delete volume 002
      ↓
...
```

Normally, only one completed volume needs to exist temporarily.

This keeps temporary storage usage close to the configured maximum volume size instead of the total folder size.

---

## Volume Planning

`VolumePlanner` walks through the folder tree in deterministic order and decides how the content should be distributed between volumes.

The planning phase works primarily with metadata.

File contents are not loaded into memory.

Example:

```text
Large Folder - 87 GB

↓

Large Folder.part001.zip
Large Folder.part002.zip
Large Folder.part003.zip
...
Large Folder.part050.zip
```

The exact number of volumes depends on:

- configured maximum volume size;
- original file sizes;
- ZIP metadata overhead;
- files larger than one volume.

---

## Progressive Volume Creation

`VolumeBuilder` creates only the volume currently being processed.

While being generated, a volume uses a temporary extension:

```text
Large Folder.part001.zip.tmp
```

After successful construction:

```text
Large Folder.part001.zip
```

The rename is performed atomically when supported by the filesystem.

An interrupted `.tmp` file is never considered a valid completed volume.

If TeleDrop stops during volume creation, that temporary file can be discarded and reconstructed during the next run.

---

# ZIP Strategy

TeleDrop deliberately uses ZIP entries in:

```text
STORED
```

mode.

This means the data is stored without compression.

### Why?

The main priority is **predictable volume size**.

With regular compression, the final archive size is unknown until compression has finished.

That makes it more difficult to guarantee that a generated volume stays below Telegram's configured upload limit.

Using `STORED` keeps the resulting size close to:

```text
original content
+
small ZIP metadata overhead
```

The trade-off is larger archives compared with compressed ZIP files, but it provides:

- predictable size;
- lower CPU usage;
- simpler volume planning;
- safer upload limit handling.

---

## CRC32

ZIP entries stored without compression require CRC32 information.

Because of that requirement, a complete entry may require two streaming passes:

```text
Pass 1
↓
Calculate CRC32

Pass 2
↓
Write file contents into ZIP
```

This increases disk reads but avoids loading large files into memory.

---

# Memory Usage

TeleDrop is designed so that RAM usage does not scale with the total size of a folder.

It avoids approaches such as:

```java
Files.readAllBytes(file);
```

and:

```java
byte[] entireFile;
```

for large files.

Instead, file content is processed through streaming buffers.

Default buffer:

```properties
archive.buffer.kb=64
```

This means a folder containing:

```text
20 GB
50 GB
80 GB
100 GB
or more
```

does not require an equivalent amount of RAM.

Memory usage mostly grows with metadata required for planning paths and archive entries.

---

# Large Individual Files

An individual file may itself be larger than the configured upload volume.

Example:

```text
large-file.bin
5.2 GB
```

If the current limit is smaller than the file, TeleDrop splits it into logical parts:

```text
large-file.bin.part001
large-file.bin.part002
large-file.bin.part003
```

Splitting is performed through streaming.

The original file is never entirely loaded into RAM.

Information about these parts is stored in the generated manifest.

---

## Reconstructing Split Files

The manifest stores information such as:

- original path;
- part number;
- volume number;
- byte offset;
- part size.

After extracting all required parts into the same location, a file can be reconstructed on Linux with:

```bash
cat \
  "large-file.bin.part001" \
  "large-file.bin.part002" \
  "large-file.bin.part003" \
  > "large-file.bin"
```

For nested paths, use the corresponding directory structure.

The generated manifest should always be considered the definitive reference for part ordering.

---

# Manifest

TeleDrop generates a manifest describing the processed directory.

The manifest can contain:

```text
original folder name
original folder size
file count
directory count
number of volumes
volume names
volume sizes
split-file information
SHA-256 checksums
```

The manifest helps with:

- volume identification;
- file reconstruction;
- upload verification;
- integrity validation.

---

# Integrity Checking

When enabled, TeleDrop calculates SHA-256 hashes for generated volumes.

Configuration:

```properties
checksum.enabled=true
```

SHA-256 calculations are performed through streaming.

The complete volume is never loaded into memory.

---

# Telegram File Limits

The standard Telegram Bot API and the Local Bot API Server have different upload limits.

According to the Telegram documentation referenced during development:

### Standard Bot API

`sendDocument` supports uploads of up to:

```text
50 MB
```

The standard endpoint is:

```text
https://api.telegram.org
```

TeleDrop therefore uses a safer default value:

```properties
telegram.part.max.mb=45
```

This leaves margin below the maximum allowed size.

---

## Local Telegram Bot API Server

For substantially larger volumes, TeleDrop supports the official Local Telegram Bot API Server.

The local server supports uploads of up to:

```text
2000 MB
```

A configuration such as this can therefore be used:

```properties
telegram.api.base.url=http://127.0.0.1:8081
telegram.part.max.mb=1800
```

Using `1800 MB` instead of the absolute maximum provides additional safety margin.

The Local Bot API Server must be installed and configured separately.

Telegram requires credentials such as:

```text
api_id
api_hash
```

for the local server.

TeleDrop itself continues using the same upload pipeline regardless of whether the standard or local Bot API endpoint is selected.

---

## Upload Limit Validation

TeleDrop validates configured volume sizes before starting processing.

When the API host is:

```text
api.telegram.org
```

values equal to or above the standard upload limit are rejected.

Values above:

```text
2000 MB
```

are rejected for any configured endpoint.

This prevents large processing tasks from starting with obviously invalid settings.

---

# Telegram Upload

TeleDrop communicates with Telegram using:

```java
java.net.http.HttpClient
```

New files are uploaded through:

```text
multipart/form-data
```

File bodies are supplied using file-backed publishers such as:

```java
HttpRequest.BodyPublishers.ofFile(...)
```

The volume is not converted into one giant `byte[]`.

A part is considered successfully uploaded only when Telegram returns a successful Bot API response with:

```json
{
  "ok": true
}
```

After confirmation, TeleDrop:

1. persists the successful state;
2. marks the volume as uploaded;
3. removes the local temporary volume;
4. continues to the next one.

---

# Upload Queue

Monitoring several folders simultaneously does not require uploading all of them at the same time.

Stable folders enter an upload queue.

Example:

```text
Folder A
   ↓
Folder B
   ↓
Folder C
```

Default configuration:

```properties
telegram.concurrent.uploads=1
```

This means that, by default, only one large Telegram upload occurs at a time.

Other directories continue being monitored while the current upload is running.

The queue can be implemented using:

```java
BlockingQueue
```

and upload workers.

---

# Retry System

Temporary failures do not immediately fail the entire task.

TeleDrop implements retry with backoff.

Default configuration:

```properties
telegram.retry.max=5
telegram.retry.delays.seconds=5,15,30,60,120
```

Example:

```text
Attempt 1
↓
Failed

wait 5 seconds

Attempt 2
↓
Failed

wait 15 seconds

Attempt 3
↓
...
```

When Telegram returns `retry_after`, for example after HTTP `429`, TeleDrop respects the greater waiting period.

Retries are used for transient failures such as network errors, timeouts, HTTP `429`, and HTTP `5xx` responses.
Permanent client errors, such as invalid configuration or authorization (`4xx` other than `429`), are reported
without spending all configured retry attempts.

After the configured retry limit is reached, the task becomes:

```text
FAILED
```

The application itself continues running.

A volume that has not been confirmed by Telegram is never automatically deleted.

---

# Persistent State

Large uploads can take hours.

TeleDrop stores processing state locally so an interruption does not require restarting an entire folder from the beginning.

State files are stored under:

```text
data/upload-state/
```

A state can contain:

- original folder path;
- original folder snapshot;
- total size;
- file count;
- directory count;
- total number of volumes;
- next pending volume;
- generated volumes;
- uploaded volumes;
- volume sizes;
- SHA-256 hashes;
- current status;
- latest error;
- manifest status.

Example:

```text
Large Folder

Total parts: 48
Uploaded parts: 31
Next part: 32

Status: UPLOADING
```

After restarting TeleDrop, processing can resume from:

```text
part032
```

instead of starting again from:

```text
part001
```

---

## State Safety

State files are first written to temporary files and then moved atomically when supported by the filesystem.

This reduces the risk of corrupted state after:

- application crashes;
- JVM termination;
- operating system shutdown;
- power loss.

Incomplete temporary volume files are never marked as sent.

---

## Upload Idempotency Limitation

Telegram's `sendDocument` does not provide a dedicated idempotency key for this workflow.

There is therefore a small unavoidable edge case:

```text
Telegram confirms upload
        ↓
Application stops unexpectedly
        ↓
Local state has not yet been saved
```

In this situation, that volume may be uploaded again after restart.

TeleDrop prioritizes avoiding data loss over avoiding a possible duplicate upload.

---

# Resume

When TeleDrop starts, it also evaluates existing directories inside the configured watch folder.

Existing persistent states can be resumed when their source directories become stable again.

States such as:

```text
UPLOADING
FAILED
BUILDING
```

can continue from their next pending volume instead of rebuilding all previously confirmed parts.

A completed volume left after an upload failure can also be reused instead of unnecessarily rebuilding it.

---

# Temporary Storage

Generated files are stored separately from the original directories.

Default structure:

```text
.telegram-upload/
└── Large Folder/
    ├── Large Folder.part001.zip
    └── manifest.json
```

Normally, only one active volume exists at a time.

After upload confirmation:

```text
[SUCCESS] Large Folder.part001.zip uploaded
[CLEANUP] Large Folder.part001.zip removed locally
```

TeleDrop then builds the next part.

The monitoring system completely ignores:

```text
.telegram-upload/
```

so generated files are never detected as new downloads.

---

# Source Folder Safety

The original folder is treated as immutable input.

TeleDrop never automatically:

```text
DELETE
MOVE
RENAME
MODIFY
```

the source directory or its files.

Automatic deletion is restricted to files created by TeleDrop inside the configured temporary directory.

If the source folder changes after it has already been considered stable, TeleDrop can mark the task as:

```text
FAILED
```

instead of mixing data from two different snapshots.

This protects consistency between uploaded volumes.

---

# Configuration

Copy the example configuration:

```bash
cp config.example.properties config.properties
```

Restrict access to the configuration file:

```bash
chmod 600 config.properties
```

Example:

```properties
watch.folder=/home/USER/Downloads

folder.stable.seconds=60
folder.scan.interval.seconds=10

telegram.bot.token=YOUR_BOT_TOKEN
telegram.chat.id=YOUR_CHAT_ID

telegram.api.base.url=https://api.telegram.org
telegram.part.max.mb=45

telegram.concurrent.uploads=1

telegram.retry.max=5
telegram.retry.delays.seconds=5,15,30,60,120

telegram.connect.timeout.seconds=30
telegram.request.timeout.seconds=86400

temp.folder=/home/USER/Downloads/.telegram-upload
data.folder=./data

checksum.enabled=true
archive.buffer.kb=64

shutdown.await.seconds=30
```

As an alternative to storing credentials in `config.properties`, set environment variables:

```bash
export TELEDROP_BOT_TOKEN='YOUR_BOT_TOKEN'
export TELEDROP_CHAT_ID='YOUR_CHAT_ID'
```

Environment variables take precedence over the corresponding properties.

For a Local Telegram Bot API Server:

```properties
telegram.api.base.url=http://127.0.0.1:8081
telegram.part.max.mb=1800
```

---

# Telegram Bot Setup

Create a Telegram bot using:

```text
@BotFather
```

Run:

```text
/newbot
```

and follow the instructions.

Bot credentials must only be stored in:

```text
config.properties
```

Example:

```properties
telegram.bot.token=YOUR_BOT_TOKEN
telegram.chat.id=YOUR_CHAT_ID
```

Never hardcode the token inside Java source files.

---

## Telegram Destination

The configured destination can be:

- a private chat;
- a group;
- a supergroup;
- a channel.

For groups or channels, the bot needs permission to send documents.

TeleDrop uses the configured:

```properties
telegram.chat.id=
```

for all generated volumes.

---

# Project Structure

A simplified project structure can look like:

```text
TeleDrop/
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── thurdass/
│                   └── telegramwatcher/
│                       ├── Main.java
│                       ├── ApplicationLock.java
│                       │
│                       ├── config/
│                       │   └── AppConfig.java
│                       │
│                       ├── watcher/
│                       │   ├── DownloadWatcher.java
│                       │   ├── FolderCompletionChecker.java
│                       │   └── FolderSnapshot.java
│                       │
│                       ├── archive/
│                       │   ├── VolumePlanner.java
│                       │   ├── VolumeBuilder.java
│                       │   └── LargeFileSplitter.java
│                       │
│                       ├── queue/
│                       │   ├── UploadQueue.java
│                       │   └── UploadWorker.java
│                       │
│                       ├── telegram/
│                       │   ├── TelegramClient.java
│                       │   └── TelegramUploadResult.java
│                       │
│                       ├── state/
│                       │   ├── UploadState.java
│                       │   └── UploadStateStore.java
│                       │
│                       ├── manifest/
│                       │   └── ManifestWriter.java
│                       │
│                       ├── model/
│                       │   ├── DownloadTask.java
│                       │   └── VolumeInfo.java
│                       │
│                       └── util/
│                           ├── FileUtils.java
│                           └── ChecksumUtils.java
│
├── config.example.properties
├── build.sh
├── run.sh
├── pom.xml
├── .gitignore
└── README.md
```

The exact internal structure may evolve as the project develops.

---

# Build

TeleDrop requires:

```text
Java 21+
```

Build using the provided script:

```bash
./build.sh
```

Run:

```bash
./run.sh
```

A custom configuration file can also be provided:

```bash
./run.sh /path/to/config.properties
```

The project can also be built using Maven.

Run the automated tests with:

```bash
mvn test
```

---

# IntelliJ IDEA

1. Open the TeleDrop project directory.
2. Select JDK 21.
3. Allow IntelliJ IDEA to import `pom.xml`.
4. Run:

```text
com.thurdass.telegramwatcher.Main
```

If the configuration file is not in the application's working directory, pass it as a program argument.

Example:

```text
/path/to/config.properties
```

The configured data and temporary directories are created automatically when necessary.

---

# Example Logs

```text
[WATCHER] Monitoring /home/user/Downloads

[NEW] New folder detected: Large Folder

[SCAN] Large Folder - 52.80 GB - 831 files - 42 directories

[WAITING] Large Folder is still changing...

[STABLE] Large Folder - no changes for 20s

[STABLE] Large Folder - no changes for 40s

[STABLE] Large Folder - no changes for 60s

[COMPLETE] Folder completed: Large Folder

[QUEUE] Added Large Folder to upload queue

[VOLUME] Creating Large Folder.part001.zip...

[VOLUME] Large Folder.part001.zip created - 1.74 GB

[UPLOAD] Uploading Large Folder.part001.zip

[PROGRESS] Part 1/50

[SUCCESS] Large Folder.part001.zip uploaded

[CLEANUP] Large Folder.part001.zip removed locally

[VOLUME] Creating Large Folder.part002.zip...

[UPLOAD] Uploading part 2/50

...

[SUCCESS] All volumes uploaded successfully
```

---

# Shutdown

TeleDrop installs a shutdown hook.

When the application receives:

```text
Ctrl+C
```

it attempts to:

- stop accepting new tasks;
- close `WatchService`;
- stop new uploads;
- save pending states;
- wait for active workers;
- close executors;
- preserve incomplete work safely.

Configuration:

```properties
shutdown.await.seconds=30
```

Incomplete `.tmp` volumes are never considered successfully generated or uploaded.

---

# Error Handling

A failure in one folder should not terminate the entire application.

TeleDrop handles errors per task.

Examples include:

- filesystem errors;
- removed directories;
- interrupted scans;
- Telegram errors;
- HTTP failures;
- upload timeouts;
- invalid configuration;
- failed volume construction.

A failed task can be marked:

```text
FAILED
```

while the watcher and other tasks continue running.

---

# Deliberate Limitations

TeleDrop deliberately makes several design choices:

- empty folders are never considered completed tasks;
- symbolic links are ignored;
- symbolic links are not followed outside the original directory;
- non-regular filesystem entries can be ignored;
- ZIP compression is disabled;
- generated volume size is prioritized over compression ratio;
- source directories are never automatically deleted;
- only TeleDrop-generated temporary files are automatically cleaned up;
- the standard Telegram Bot API is not suitable for very large volumes;
- Local Bot API Server is recommended when larger individual upload parts are required.

---

# Security

Never commit:

```text
config.properties
data/
.telegram-upload/
```

Never expose:

```text
telegram.bot.token
```

The repository should contain only:

```text
config.example.properties
```

with placeholder credentials.

Recommended `.gitignore` entries:

```gitignore
config.properties
data/
.telegram-upload/

.idea/
*.iml
out/
target/
```

Before every Git push, verify that no Telegram token or other private credential is being tracked.

---

# Project Goals

TeleDrop is also a Java learning project focused on applying backend and systems concepts to a real automation problem.

The project explores:

- filesystem monitoring;
- Java NIO;
- streaming I/O;
- large-file processing;
- HTTP communication;
- REST API integration;
- concurrency;
- thread pools;
- producer/consumer queues;
- ZIP internals;
- CRC32;
- checksums;
- persistent state;
- retry strategies;
- fault tolerance;
- graceful shutdown;
- disk-space management;
- configuration management.

The project intentionally avoids heavy frameworks so that these mechanisms can be implemented and understood directly with Java.

---

# References

Telegram documentation used when designing the upload system:

- [Telegram Bot API — sendDocument and InputFile](https://core.telegram.org/bots/api#senddocument)
- [Telegram Bot FAQ — Upload and download limits](https://core.telegram.org/bots/faq#how-do-i-upload-a-large-file)
- [Telegram Bot API Server](https://github.com/tdlib/telegram-bot-api)
- [Telegram Bot API — Local Server](https://core.telegram.org/bots/features#local-server)

---

# License

No license has been selected for this project yet.

---

# Author

Arthur da Silva Mendes de Almeida
thurdass
