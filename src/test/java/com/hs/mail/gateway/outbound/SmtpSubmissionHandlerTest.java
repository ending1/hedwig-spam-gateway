package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpSubmissionHandlerTest {

    @TempDir
    Path tempDir;

    private EmbeddedChannel channel;
    private OutboundSpoolService spoolService;

    @BeforeEach
    void setUp() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getOutbound().setSpoolDir(tempDir.toString());
        spoolService = new OutboundSpoolService(properties);

        channel = new EmbeddedChannel(
                new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()),
                new StringDecoder(),
                new StringEncoder(),
                new SmtpSubmissionHandler(spoolService, properties.getOutbound(), "gateway-test"));
    }

    private void send(String line) {
        channel.writeInbound(Unpooled.copiedBuffer(line + "\r\n", CharsetUtil.UTF_8));
    }

    private String readAllResponses() {
        StringBuilder sb = new StringBuilder();
        ByteBuf buf;
        while ((buf = channel.readOutbound()) != null) {
            sb.append(buf.toString(CharsetUtil.UTF_8));
            buf.release();
        }
        return sb.toString();
    }

    @Test
    void 정상_트랜잭션은_큐잉되고_스풀에_파일이_생긴다() {
        String greeting = readAllResponses();
        assertTrue(greeting.startsWith("220"));

        send("EHLO hedwig");
        send("MAIL FROM:<sender@hedwig.local>");
        send("RCPT TO:<rcpt@target.example>");
        send("DATA");
        send("Subject: hello");
        send("");
        send("body line");
        send(".");

        String responses = readAllResponses();
        assertTrue(responses.contains("250"), "EHLO/MAIL/RCPT 응답에 250이 포함되어야 함: " + responses);
        assertTrue(responses.contains("354"), "DATA 시작 응답 354가 있어야 함: " + responses);
        assertTrue(responses.contains("250 2.6.0 Queued"), "큐잉 완료 응답이 있어야 함: " + responses);
        assertEquals(1, spoolService.count(OutboundSpoolService.QUEUE_DIR));
    }

    @Test
    void MAIL_FROM_없이_RCPT_TO_보내면_503() {
        readAllResponses();
        send("EHLO hedwig");
        readAllResponses();
        send("RCPT TO:<rcpt@target.example>");
        String responses = readAllResponses();
        assertTrue(responses.startsWith("503"), "MAIL 없이 RCPT 보내면 503이어야 함: " + responses);
    }

    @Test
    void 점_스터핑된_본문_라인은_점_하나가_제거된다() throws Exception {
        readAllResponses();
        send("EHLO hedwig");
        send("MAIL FROM:<sender@hedwig.local>");
        send("RCPT TO:<rcpt@target.example>");
        send("DATA");
        send("..leading dot line");
        send(".");
        readAllResponses();

        OutboundMailItem item = spoolService.listDue(OutboundSpoolService.QUEUE_DIR, System.currentTimeMillis()).get(0);
        String content = new String(java.nio.file.Files.readAllBytes(item.getDataFile()), CharsetUtil.UTF_8);
        assertTrue(content.contains(".leading dot line"), "점 스터핑 해제 후 내용: " + content);
        assertTrue(!content.contains("..leading dot line"));
    }
}
