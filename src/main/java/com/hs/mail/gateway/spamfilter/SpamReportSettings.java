package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * 스팸 신고 처리 옵션(수동/자동 등). 초기값은 application.yml(gateway.spam-report.*)이고,
 * 관리자가 바꾼 값은 settingsFile(JSON)에 저장되어 재시작 후에도 우선 적용된다.
 * 설정 파일을 읽거나 쓰지 못해도 동작은 계속한다(yml 값/메모리 값 사용).
 */
@Component
public class SpamReportSettings {

    private static final Logger log = LoggerFactory.getLogger(SpamReportSettings.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final File file;
    private volatile boolean autoProcess;
    private volatile double autoMinConfidence;
    private volatile double autoMaxWeight;
    private volatile int autoMaxPerDay;
    private volatile boolean autoDismissHam;

    @Autowired
    public SpamReportSettings(GatewayProperties properties) {
        GatewayProperties.SpamReportOptions o = properties.getSpamReport();
        this.autoProcess = o.isAutoProcess();
        this.autoMinConfidence = o.getAutoMinConfidence();
        this.autoMaxWeight = o.getAutoMaxWeight();
        this.autoMaxPerDay = o.getAutoMaxPerDay();
        this.autoDismissHam = o.isAutoDismissHam();
        String path = o.getSettingsFile();
        this.file = path == null || path.trim().isEmpty() ? null : new File(path.trim());
        load();
    }

    private void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            JsonNode n = MAPPER.readTree(file);
            if (n.has("autoProcess")) autoProcess = n.get("autoProcess").asBoolean(autoProcess);
            if (n.has("autoMinConfidence")) autoMinConfidence = clamp(n.get("autoMinConfidence").asDouble(autoMinConfidence), 0.5, 1.0);
            if (n.has("autoMaxWeight")) autoMaxWeight = clamp(n.get("autoMaxWeight").asDouble(autoMaxWeight), 0.1, 5.0);
            if (n.has("autoMaxPerDay")) autoMaxPerDay = (int) clamp(n.get("autoMaxPerDay").asInt(autoMaxPerDay), 0, 1000);
            if (n.has("autoDismissHam")) autoDismissHam = n.get("autoDismissHam").asBoolean(autoDismissHam);
        } catch (IOException | RuntimeException e) {
            log.warn("스팸 신고 설정 파일을 읽지 못해 기본값을 사용합니다({}): {}", file, e.getMessage());
        }
    }

    /** null인 항목은 바꾸지 않는다. 범위를 벗어난 값은 허용 범위로 보정한다. */
    public synchronized void update(Boolean autoProcess, Double autoMinConfidence, Double autoMaxWeight,
                                    Integer autoMaxPerDay, Boolean autoDismissHam) {
        if (autoProcess != null) this.autoProcess = autoProcess;
        if (autoMinConfidence != null) this.autoMinConfidence = clamp(autoMinConfidence, 0.5, 1.0);
        if (autoMaxWeight != null) this.autoMaxWeight = clamp(autoMaxWeight, 0.1, 5.0);
        if (autoMaxPerDay != null) this.autoMaxPerDay = (int) clamp(autoMaxPerDay, 0, 1000);
        if (autoDismissHam != null) this.autoDismissHam = autoDismissHam;
        save();
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("autoProcess", autoProcess);
            n.put("autoMinConfidence", autoMinConfidence);
            n.put("autoMaxWeight", autoMaxWeight);
            n.put("autoMaxPerDay", autoMaxPerDay);
            n.put("autoDismissHam", autoDismissHam);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, n);
        } catch (IOException | RuntimeException e) {
            log.warn("스팸 신고 설정을 파일에 저장하지 못했습니다(재시작하면 이전 값으로 돌아갑니다) {}: {}", file, e.getMessage());
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public boolean isAutoProcess() { return autoProcess; }
    public double getAutoMinConfidence() { return autoMinConfidence; }
    public double getAutoMaxWeight() { return autoMaxWeight; }
    public int getAutoMaxPerDay() { return autoMaxPerDay; }
    public boolean isAutoDismissHam() { return autoDismissHam; }
}
