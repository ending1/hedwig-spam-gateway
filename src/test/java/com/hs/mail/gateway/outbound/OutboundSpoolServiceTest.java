package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundSpoolServiceTest {

    @TempDir
    Path tempDir;

    private OutboundSpoolService spoolService;

    @BeforeEach
    void setUp() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getOutbound().setSpoolDir(tempDir.toString());
        spoolService = new OutboundSpoolService(properties);
    }

    private String enqueueSample() throws Exception {
        return spoolService.enqueue("sender@example.com", Collections.singletonList("rcpt@target.example"),
                new ByteArrayInputStream("Subject: test\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 접수한_메일은_즉시_큐에서_조회된다() throws Exception {
        enqueueSample();
        List<OutboundMailItem> due = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis());
        assertEquals(1, due.size());
        assertEquals("sender@example.com", due.get(0).getMailFrom());
        assertEquals(1, spoolService.count(OutboundSpoolService.QUEUE_DIR));
    }

    @Test
    void 성공_처리하면_스풀에서_제거된다() throws Exception {
        enqueueSample();
        OutboundMailItem item = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis()).get(0);
        spoolService.markSuccess(item, OutboundSpoolService.QUEUE_DIR);
        assertEquals(0, spoolService.count(OutboundSpoolService.QUEUE_DIR));
    }

    @Test
    void 재시도_예약시_다음_시도_전까지는_조회되지_않는다() throws Exception {
        enqueueSample();
        OutboundMailItem item = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis()).get(0);
        long future = System.currentTimeMillis() + 60_000;
        spoolService.reschedule(item, OutboundSpoolService.QUEUE_DIR, 1, future, "temp fail", 5);

        assertTrue(spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis()).isEmpty());
        assertTrue(spoolService.listDue(OutboundSpoolService.QUEUE_DIR, future + 1).stream()
                .anyMatch(i -> i.getId().equals(item.getId())));
    }

    @Test
    void delay_임계치_초과시_delay_디렉터리로_이동한다() throws Exception {
        enqueueSample();
        OutboundMailItem item = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis()).get(0);
        spoolService.reschedule(item, OutboundSpoolService.QUEUE_DIR, 3, System.currentTimeMillis(), "temp fail", 2);

        assertEquals(0, spoolService.count(OutboundSpoolService.QUEUE_DIR));
        assertEquals(1, spoolService.count(OutboundSpoolService.DELAY_DIR));
    }

    @Test
    void deadletter로_이동하면_로그가_남고_원본_디렉터리에서_사라진다() throws Exception {
        enqueueSample();
        OutboundMailItem item = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis()).get(0);
        spoolService.moveToDeadLetter(item, OutboundSpoolService.QUEUE_DIR, "permanent failure 550");

        assertEquals(0, spoolService.count(OutboundSpoolService.QUEUE_DIR));
        assertEquals(1, spoolService.count(OutboundSpoolService.DEADLETTER_DIR));
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("outbound-deadletter.log")));
    }
}
