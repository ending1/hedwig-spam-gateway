package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import com.sun.mail.smtp.SMTPSendFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * 실제 인터넷 발송을 담당한다. Hedwig의 {@code RemoteDelivery}와 동일하게 JavaMail(com.sun.mail.smtp)을
 * 재사용해 TLS/STARTTLS/타임아웃 처리를 직접 구현하지 않는다. 도메인별 MX 호스트를 순서대로 시도한다.
 */
@Component
public class MailSender {

    private static final Logger log = LoggerFactory.getLogger(MailSender.class);

    private final DnsMxResolver dnsMxResolver;
    private final GatewayProperties properties;

    public MailSender(DnsMxResolver dnsMxResolver, GatewayProperties properties) {
        this.dnsMxResolver = dnsMxResolver;
        this.properties = properties;
    }

    public DeliveryResult deliver(String domain, String mailFrom, List<String> recipients, Path dataFile) {
        List<String> mxHosts = dnsMxResolver.resolveMailHosts(domain);
        if (mxHosts.isEmpty()) {
            return DeliveryResult.transientFailure("MX 조회 실패 또는 타임아웃: domain=" + domain);
        }

        GatewayProperties.Outbound outbound = properties.getOutbound();
        Properties mailProps = new Properties();
        mailProps.setProperty("mail.smtp.connectiontimeout", String.valueOf(outbound.getSmtpConnectTimeoutMillis()));
        mailProps.setProperty("mail.smtp.timeout", String.valueOf(outbound.getSmtpTimeoutMillis()));
        mailProps.setProperty("mail.smtp.sendpartial", "true");
        Session session = Session.getInstance(mailProps, null);

        InternetAddress[] addresses;
        try {
            addresses = toAddresses(recipients);
        } catch (Exception e) {
            return DeliveryResult.permanentFailure("잘못된 수신자 주소: " + e.getMessage());
        }

        MessagingException lastError = null;
        for (String mxHost : mxHosts) {
            try (InputStream in = Files.newInputStream(dataFile)) {
                MimeMessage message = new MimeMessage(session, in);
                if (mailFrom != null && !mailFrom.isEmpty()) {
                    message.setFrom(new InternetAddress(mailFrom));
                }

                Transport transport = session.getTransport("smtp");
                try {
                    transport.connect(mxHost, 25, null, null);
                    transport.sendMessage(message, addresses);
                    log.info("아웃바운드 발송 성공: host={}, from={}, to={}", mxHost, mailFrom, recipients);
                    return DeliveryResult.success();
                } finally {
                    try {
                        transport.close();
                    } catch (MessagingException ignored) {
                        // 종료 시점 예외는 발송 성공/실패 판정에 영향 없음
                    }
                }
            } catch (SMTPSendFailedException e) {
                if (e.getReturnCode() >= 500 && e.getReturnCode() <= 599) {
                    return DeliveryResult.permanentFailure("영구 실패(" + e.getReturnCode() + "): " + e.getMessage());
                }
                lastError = e;
            } catch (SendFailedException e) {
                // sendPartial=true인 상태에서 수신자가 서버에 의해 거부되면(예: 존재하지 않는 계정)
                // 반환코드 없는 일반 SendFailedException("Invalid Addresses")으로 올라온다.
                // Hedwig RemoteDelivery와 동일하게 invalidAddresses가 있으면 영구실패로 취급한다.
                if (e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0) {
                    return DeliveryResult.permanentFailure("영구 실패(잘못된 수신자): " + e.getMessage());
                }
                lastError = e;
            } catch (MessagingException e) {
                // 연결 실패 등은 다음 MX 호스트로 계속 시도
                log.debug("MX 호스트 접속 실패, 다음 호스트 시도: host={}", mxHost, e);
                lastError = e;
            } catch (IOException e) {
                return DeliveryResult.transientFailure("스풀 파일 읽기 실패: " + e.getMessage());
            }
        }
        String reason = lastError != null ? lastError.getMessage() : "발송 가능한 MX 서버 없음";
        return DeliveryResult.transientFailure(reason);
    }

    private InternetAddress[] toAddresses(List<String> recipients) throws Exception {
        InternetAddress[] addresses = new InternetAddress[recipients.size()];
        for (int i = 0; i < recipients.size(); i++) {
            addresses[i] = new InternetAddress(recipients.get(i));
        }
        return addresses;
    }
}
