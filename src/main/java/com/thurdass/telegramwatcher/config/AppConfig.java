package com.thurdass.telegramwatcher.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class AppConfig {
    private static final long MB = 1_000_000L;
    private static final long OFFICIAL_UPLOAD_LIMIT_MB = 50L;
    private static final long LOCAL_UPLOAD_LIMIT_MB = 2_000L;

    private final Path watchFolder;
    private final Duration folderStableDuration;
    private final Duration folderScanInterval;
    private final String telegramBotToken;
    private final String telegramChatId;
    private final URI telegramApiBaseUri;
    private final long telegramPartMaxBytes;
    private final int telegramConcurrentUploads;
    private final int telegramRetryMax;
    private final List<Duration> telegramRetryDelays;
    private final Duration telegramConnectTimeout;
    private final Duration telegramRequestTimeout;
    private final Path tempFolder;
    private final Path stateFolder;
    private final boolean checksumEnabled;
    private final int archiveBufferBytes;
    private final Duration shutdownAwaitDuration;

    private AppConfig(
            Path watchFolder,
            Duration folderStableDuration,
            Duration folderScanInterval,
            String telegramBotToken,
            String telegramChatId,
            URI telegramApiBaseUri,
            long telegramPartMaxBytes,
            int telegramConcurrentUploads,
            int telegramRetryMax,
            List<Duration> telegramRetryDelays,
            Duration telegramConnectTimeout,
            Duration telegramRequestTimeout,
            Path tempFolder,
            Path stateFolder,
            boolean checksumEnabled,
            int archiveBufferBytes,
            Duration shutdownAwaitDuration) {
        this.watchFolder = watchFolder;
        this.folderStableDuration = folderStableDuration;
        this.folderScanInterval = folderScanInterval;
        this.telegramBotToken = telegramBotToken;
        this.telegramChatId = telegramChatId;
        this.telegramApiBaseUri = telegramApiBaseUri;
        this.telegramPartMaxBytes = telegramPartMaxBytes;
        this.telegramConcurrentUploads = telegramConcurrentUploads;
        this.telegramRetryMax = telegramRetryMax;
        this.telegramRetryDelays = List.copyOf(telegramRetryDelays);
        this.telegramConnectTimeout = telegramConnectTimeout;
        this.telegramRequestTimeout = telegramRequestTimeout;
        this.tempFolder = tempFolder;
        this.stateFolder = stateFolder;
        this.checksumEnabled = checksumEnabled;
        this.archiveBufferBytes = archiveBufferBytes;
        this.shutdownAwaitDuration = shutdownAwaitDuration;
    }

    public static AppConfig load(Path configPath) throws IOException {
        Path absoluteConfig = configPath.toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(absoluteConfig)) {
            properties.load(input);
        }

        Path baseDirectory = absoluteConfig.getParent();
        Path watchFolder = resolvePath(required(properties, "watch.folder"), baseDirectory);
        if (!Files.isDirectory(watchFolder)) {
            throw new IOException("watch.folder não é uma pasta existente: " + watchFolder);
        }

        Duration stableDuration = positiveDuration(properties, "folder.stable.seconds", 60);
        Duration scanInterval = positiveDuration(properties, "folder.scan.interval.seconds", 10);

        String token = credential(properties, "telegram.bot.token", "TELEDROP_BOT_TOKEN");
        if (token.equals("COLOQUE_O_TOKEN_AQUI")) {
            throw new IllegalArgumentException("Configure telegram.bot.token antes de executar");
        }
        String chatId = credential(properties, "telegram.chat.id", "TELEDROP_CHAT_ID");
        if (chatId.equals("COLOQUE_O_CHAT_ID_AQUI")) {
            throw new IllegalArgumentException("Configure telegram.chat.id antes de executar");
        }

        URI apiBaseUri = parseApiBaseUri(properties.getProperty("telegram.api.base.url", "https://api.telegram.org"));
        long partMaxMb = positiveLong(properties, "telegram.part.max.mb", 45);
        if (partMaxMb > LOCAL_UPLOAD_LIMIT_MB) {
            throw new IllegalArgumentException("telegram.part.max.mb não pode exceder o limite Local Bot API atual de "
                    + LOCAL_UPLOAD_LIMIT_MB + " MB");
        }
        if (partMaxMb >= OFFICIAL_UPLOAD_LIMIT_MB && isOfficialApi(apiBaseUri)) {
            throw new IllegalArgumentException("No Bot API oficial, use menos de " + OFFICIAL_UPLOAD_LIMIT_MB
                    + " MB; o padrão seguro é 45 MB");
        }

        int concurrentUploads = positiveInt(properties, "telegram.concurrent.uploads", 1);
        int retryMax = positiveInt(properties, "telegram.retry.max", 5);
        List<Duration> retryDelays = retryDelays(properties.getProperty("telegram.retry.delays.seconds",
                "5,15,30,60,120"));
        Duration connectTimeout = positiveDuration(properties, "telegram.connect.timeout.seconds", 30);
        Duration requestTimeout = positiveDuration(properties, "telegram.request.timeout.seconds", 86_400);

        Path tempFolder = resolvePath(properties.getProperty("temp.folder",
                watchFolder.resolve(".telegram-upload").toString()), baseDirectory);
        Path dataFolder = resolvePath(properties.getProperty("data.folder", "./data"), baseDirectory);
        validateStoragePaths(watchFolder, tempFolder, dataFolder);
        Path stateFolder = dataFolder.resolve("upload-state").normalize();

        boolean checksumEnabled = booleanValue(properties, "checksum.enabled", true);
        int archiveBufferKb = positiveInt(properties, "archive.buffer.kb", 64);
        if (archiveBufferKb > 1024) {
            throw new IllegalArgumentException("archive.buffer.kb não pode exceder 1024");
        }
        Duration shutdownAwait = positiveDuration(properties, "shutdown.await.seconds", 30);

        return new AppConfig(
                watchFolder,
                stableDuration,
                scanInterval,
                token,
                chatId,
                apiBaseUri,
                Math.multiplyExact(partMaxMb, MB),
                concurrentUploads,
                retryMax,
                retryDelays,
                connectTimeout,
                requestTimeout,
                tempFolder,
                stateFolder,
                checksumEnabled,
                Math.multiplyExact(archiveBufferKb, 1024),
                shutdownAwait);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Propriedade obrigatória ausente: " + key);
        }
        return value.trim();
    }

    private static String credential(Properties properties, String key, String environmentVariable) {
        String environmentValue = System.getenv(environmentVariable);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        return required(properties, key);
    }

    private static Path resolvePath(String value, Path baseDirectory) {
        String expanded = value.trim();
        if (expanded.equals("~")) {
            expanded = System.getProperty("user.home");
        } else if (expanded.startsWith("~/")) {
            expanded = Path.of(System.getProperty("user.home"), expanded.substring(2)).toString();
        }
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            path = baseDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static URI parseApiBaseUri(String value) {
        URI uri = URI.create(value.trim().replaceAll("/+$", ""));
        if (uri.getScheme() == null || (!uri.getScheme().equalsIgnoreCase("http")
                && !uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("telegram.api.base.url deve usar http ou https");
        }
        return uri;
    }

    private static void validateStoragePaths(Path watchFolder, Path tempFolder, Path dataFolder) {
        if (watchFolder.startsWith(tempFolder)) {
            throw new IllegalArgumentException("temp.folder não pode ser a pasta monitorada nem um diretório pai dela: "
                    + tempFolder);
        }
        if (dataFolder.startsWith(watchFolder)) {
            throw new IllegalArgumentException("data.folder não pode ficar dentro da pasta monitorada: " + dataFolder);
        }
        if (tempFolder.startsWith(dataFolder) || dataFolder.startsWith(tempFolder)) {
            throw new IllegalArgumentException("temp.folder e data.folder não podem ficar um dentro do outro");
        }
    }

    private static boolean isOfficialApi(URI uri) {
        return uri.getHost() != null && uri.getHost().equalsIgnoreCase("api.telegram.org");
    }

    private static Duration positiveDuration(Properties properties, String key, long defaultSeconds) {
        long seconds = positiveLong(properties, key, defaultSeconds);
        return Duration.ofSeconds(seconds);
    }

    private static long positiveLong(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key, Long.toString(defaultValue)).trim();
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(key + " deve ser maior que zero");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " deve ser um número inteiro positivo: " + value, exception);
        }
    }

    private static int positiveInt(Properties properties, String key, int defaultValue) {
        long value = positiveLong(properties, key, defaultValue);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " é grande demais");
        }
        return (int) value;
    }

    private static List<Duration> retryDelays(String value) {
        List<Duration> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                long seconds;
                try {
                    seconds = Long.parseLong(item.trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("telegram.retry.delays.seconds inválido: " + value, exception);
                }
                if (seconds <= 0) {
                    throw new IllegalArgumentException("telegram.retry.delays.seconds deve conter valores positivos");
                }
                result.add(Duration.ofSeconds(seconds));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("telegram.retry.delays.seconds não pode ser vazio");
        }
        return result;
    }

    private static boolean booleanValue(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " deve ser true ou false");
        }
        return Boolean.parseBoolean(value);
    }

    public Path watchFolder() {
        return watchFolder;
    }

    public Duration folderStableDuration() {
        return folderStableDuration;
    }

    public Duration folderScanInterval() {
        return folderScanInterval;
    }

    public String telegramBotToken() {
        return telegramBotToken;
    }

    public String telegramChatId() {
        return telegramChatId;
    }

    public URI telegramApiBaseUri() {
        return telegramApiBaseUri;
    }

    public long telegramPartMaxBytes() {
        return telegramPartMaxBytes;
    }

    public int telegramConcurrentUploads() {
        return telegramConcurrentUploads;
    }

    public int telegramRetryMax() {
        return telegramRetryMax;
    }

    public List<Duration> telegramRetryDelays() {
        return telegramRetryDelays;
    }

    public Duration telegramConnectTimeout() {
        return telegramConnectTimeout;
    }

    public Duration telegramRequestTimeout() {
        return telegramRequestTimeout;
    }

    public Path tempFolder() {
        return tempFolder;
    }

    public Path stateFolder() {
        return stateFolder;
    }

    public boolean checksumEnabled() {
        return checksumEnabled;
    }

    public int archiveBufferBytes() {
        return archiveBufferBytes;
    }

    public Duration shutdownAwaitDuration() {
        return shutdownAwaitDuration;
    }
}
