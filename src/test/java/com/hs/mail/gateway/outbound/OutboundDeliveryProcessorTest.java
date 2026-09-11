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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboundDeliveryProcessorTest {

    @TempDir
    Path tempDir;

    private OutboundSpoolService spoolService;
    private MailSender mailSender;
    private GatewayProperties properties;
    private OutboundDeliveryProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        properties = new GatewayProperties();
        properties.getOutbound().setSpoolDir(tempDir.toString());
        properties.getOutbound().setMaxRetries(3);
        properties.getOutbound().setDelayAfterRetries(2);
        properties.getOutbound().setRetryDelaySeconds(1);

        spoolService = new OutboundSpoolService(properties);
        mailSender = mock(MailSender.class);
        processor = new OutboundDeliveryProcessor(mailSender, spoolService, properties);
    }

    private OutboundMailItem enqueueAndFetch() throws Exception {
        spoolService.enqueue("sender@hedwig.local", Collections.singletonList("rcpt@target.example"),
                new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8)));
        List<OutboundMailItem> due = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis());
        return due.get(0);
    }

    @Test
    void 발송_성공하면_스풀에서_제거된다() throws Exception {
        when(mailSender.deliver(anyString(), anyString(), any(), any())).thenReturn(DeliveryResult.success());
        OutboundMailItem item = enqueueAndFetch();

        processor.process(item, OutboundSpoolService.QUEUE_DIR);

        assertEquals(0, spoolService.count(OutboundSpoolService.QUEUE_DIR));
        assertEquals(1, processor.getSuccessCount());
    }

    @Test
    void 영구실패면_즉시_deadletter로_이동한다() throws Exception {
        when(mailSender.deliver(anyString(), anyString(), any(), any()))
                .thenReturn(DeliveryResult.permanentFailure("550 no such user"));
        OutboundMailItem item = enqueueAndFetch();

        processor.process(item, OutboundSpoolService.QUEUE_DIR);

        assertEquals(0, spoolService.count(OutboundSpoolService.QUEUE_DIR));
        assertEquals(1, spoolService.count(OutboundSpoolService.DEADLETTER_DIR));
        assertEquals(1, processor.getFailureCount());
    }

    @Test
    void 일시실패_반복시_delay_큐로_격리되고_결국_deadletter로_소진된다() throws Exception {
        when(mailSender.deliver(anyString(), anyString(), any(), any()))
                .thenReturn(DeliveryResult.transientFailure("connection refused"));
        OutboundMailItem item = enqueueAndFetch();

        // 1차: attempts=0 -> 1, delayAfterRetries=2 이므로 아직 QUEUE
        processor.process(item, OutboundSpoolService.QUEUE_DIR);
        assertEquals(1, spoolService.count(OutboundSpoolService.QUEUE_DIR));
        assertEquals(0, spoolService.count(OutboundSpoolService.DELAY_DIR));

        // 2차: attempts=1 -> 2, delayAfterRetries(2) 도달 -> DELAY로 이동
        OutboundMailItem afterFirst = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, Long.MAX_VALUE).get(0);
        processor.process(afterFirst, OutboundSpoolService.QUEUE_DIR);
        assertEquals(0, spoolService.count(OutboundSpoolService.QUEUE_DIR));
        assertEquals(1, spoolService.count(OutboundSpoolService.DELAY_DIR));

        // 3차: attempts=2 -> 3 == maxRetries, 여전히 실패이므로 다음 시도(4차)에서 소진
        OutboundMailItem afterSecond = spoolService.listDue(OutboundSpoolService.DELAY_DIR, Long.MAX_VALUE).get(0);
        processor.process(afterSecond, OutboundSpoolService.DELAY_DIR);
        OutboundMailItem afterThird = spoolService.listDue(OutboundSpoolService.DELAY_DIR, Long.MAX_VALUE).get(0);
        processor.process(afterThird, OutboundSpoolService.DELAY_DIR);

        assertEquals(0, spoolService.count(OutboundSpoolService.DELAY_DIR));
        assertEquals(1, spoolService.count(OutboundSpoolService.DEADLETTER_DIR));
    }
}
