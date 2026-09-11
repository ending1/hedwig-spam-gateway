package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Hedwig의 spool/remote, spool/delay 파일 스풀 방식을 참고한 게이트웨이 인스턴스 로컬 아웃바운드 스풀.
 * queue/(신규+재시도) - delay/(느린 도메인 격리) - deadletter/(영구 실패/재시도 소진) 세 디렉터리로 구성된다.
 */
@Component
public class OutboundSpoolService {

    private static final Logger log = LoggerFactory.getLogger(OutboundSpoolService.class);

    public static final String QUEUE_DIR = "queue";
    public static final String DELAY_DIR = "delay";
    public static final String DEADLETTER_DIR = "deadletter";

    private static final String KEY_MAIL_FROM = "mailFrom";
    private static final String KEY_RECIPIENTS = "recipients";
    private static final String KEY_ATTEMPTS = "attempts";
    private static final String KEY_NEXT_ATTEMPT_AT = "nextAttemptAt";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_LAST_ERROR = "lastError";

    private final Path baseDir;

    public OutboundSpoolService(GatewayProperties properties) throws IOException {
        this.baseDir = Paths.get(properties.getOutbound().getSpoolDir());
        for (String dir : new String[]{QUEUE_DIR, DELAY_DIR, DEADLETTER_DIR}) {
            Files.createDirectories(baseDir.resolve(dir));
        }
    }

    /** Hedwig으로부터 접수한 메일을 큐 스풀에 저장한다. */
    public String enqueue(String mailFrom, List<String> recipients, InputStream rawMessage) throws IOException {
        String id = UUID.randomUUID().toString();
        Path dataFile = baseDir.resolve(QUEUE_DIR).resolve(id + ".eml");
        Path metaFile = baseDir.resolve(QUEUE_DIR).resolve(id + ".meta.properties");

        try (OutputStream out = Files.newOutputStream(dataFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = rawMessage.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        Properties meta = new Properties();
        meta.setProperty(KEY_MAIL_FROM, mailFrom == null ? "" : mailFrom);
        meta.setProperty(KEY_RECIPIENTS, String.join(",", recipients));
        meta.setProperty(KEY_ATTEMPTS, "0");
        meta.setProperty(KEY_NEXT_ATTEMPT_AT, "0");
        meta.setProperty(KEY_CREATED_AT, Instant.now().toString());
        writeMeta(metaFile, meta);

        log.info("아웃바운드 메일 접수: id={}, from={}, to={}", id, mailFrom, recipients);
        return id;
    }

    public List<OutboundMailItem> listDue(String dir, long nowEpochMillis) {
        Path dirPath = baseDir.resolve(dir);
        try (Stream<Path> files = Files.list(dirPath)) {
            List<OutboundMailItem> result = new ArrayList<>();
            List<Path> metaFiles = files.filter(p -> p.getFileName().toString().endsWith(".meta.properties"))
                    .collect(Collectors.toList());
            for (Path metaFile : metaFiles) {
                try {
                    OutboundMailItem item = readItem(metaFile);
                    if (item.isDue(nowEpochMillis)) {
                        result.add(item);
                    }
                } catch (IOException e) {
                    log.warn("스풀 메타 파일 읽기 실패, 건너뜀: {}", metaFile, e);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("스풀 디렉터리 목록 조회 실패: {}", dirPath, e);
            return new ArrayList<>();
        }
    }

    public long count(String dir) {
        try (Stream<Path> files = Files.list(baseDir.resolve(dir))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".meta.properties")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public void markSuccess(OutboundMailItem item, String dir) {
        deleteQuietly(metaFile(dir, item.getId()));
        deleteQuietly(item.getDataFile());
    }

    /** 재시도 예약. delayAfterRetries 이상이면 delay 디렉터리로 옮긴다. */
    public void reschedule(OutboundMailItem item, String currentDir, int newAttempts, long nextAttemptAtEpochMillis,
                            String lastError, int delayAfterRetries) throws IOException {
        String targetDir = newAttempts >= delayAfterRetries ? DELAY_DIR : currentDir;
        Path newDataFile = baseDir.resolve(targetDir).resolve(item.getId() + ".eml");
        Path newMetaFile = baseDir.resolve(targetDir).resolve(item.getId() + ".meta.properties");

        if (!targetDir.equals(currentDir)) {
            Files.move(item.getDataFile(), newDataFile, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(metaFile(currentDir, item.getId()));
            log.info("느린/실패 반복 도메인, delay 큐로 이동: id={}, attempts={}", item.getId(), newAttempts);
        }

        Properties meta = new Properties();
        meta.setProperty(KEY_MAIL_FROM, item.getMailFrom() == null ? "" : item.getMailFrom());
        meta.setProperty(KEY_RECIPIENTS, String.join(",", item.getRecipients()));
        meta.setProperty(KEY_ATTEMPTS, String.valueOf(newAttempts));
        meta.setProperty(KEY_NEXT_ATTEMPT_AT, String.valueOf(nextAttemptAtEpochMillis));
        meta.setProperty(KEY_CREATED_AT, Instant.now().toString());
        if (lastError != null) {
            meta.setProperty(KEY_LAST_ERROR, lastError);
        }
        writeMeta(newMetaFile, meta);
    }

    public void moveToDeadLetter(OutboundMailItem item, String currentDir, String reason) {
        try {
            Path deadDataFile = baseDir.resolve(DEADLETTER_DIR).resolve(item.getId() + ".eml");
            Path deadMetaFile = baseDir.resolve(DEADLETTER_DIR).resolve(item.getId() + ".meta.properties");
            Files.move(item.getDataFile(), deadDataFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(metaFile(currentDir, item.getId()), deadMetaFile, StandardCopyOption.REPLACE_EXISTING);

            Path logFile = baseDir.resolve("outbound-deadletter.log");
            String line = String.format("%s\tid=%s\tfrom=%s\tto=%s\treason=%s%n",
                    Instant.now(), item.getId(), item.getMailFrom(),
                    String.join(",", item.getRecipients()), reason);
            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            log.warn("아웃바운드 메일 최종 실패, dead-letter로 이동: id={}, reason={}", item.getId(), reason);
        } catch (IOException e) {
            log.error("dead-letter 이동 실패: id={}", item.getId(), e);
        }
    }

    private OutboundMailItem readItem(Path metaFile) throws IOException {
        Properties meta = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            meta.load(in);
        }
        String id = metaFile.getFileName().toString().replace(".meta.properties", "");
        Path dataFile = metaFile.getParent().resolve(id + ".eml");
        String mailFrom = meta.getProperty(KEY_MAIL_FROM, "");
        List<String> recipients = new ArrayList<>();
        String rcptCsv = meta.getProperty(KEY_RECIPIENTS, "");
        if (!rcptCsv.isEmpty()) {
            recipients.addAll(java.util.Arrays.asList(rcptCsv.split(",")));
        }
        int attempts = Integer.parseInt(meta.getProperty(KEY_ATTEMPTS, "0"));
        long nextAttemptAt = Long.parseLong(meta.getProperty(KEY_NEXT_ATTEMPT_AT, "0"));
        return new OutboundMailItem(id, mailFrom, recipients, dataFile, attempts, nextAttemptAt);
    }

    private Path metaFile(String dir, String id) {
        return baseDir.resolve(dir).resolve(id + ".meta.properties");
    }

    private void writeMeta(Path metaFile, Properties meta) throws IOException {
        try (OutputStream out = Files.newOutputStream(metaFile)) {
            meta.store(out, "hedwig-spam-gateway outbound spool item");
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("스풀 파일 삭제 실패: {}", path, e);
        }
    }
}
