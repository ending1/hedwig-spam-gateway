package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM 판정 전에 현재 메일과 유사한 "과거 스팸 확인 사례"를 찾아주는 RAG 검색 서비스.
 * 사례 파일(JSONL)을 기동 시 한 번 읽어 메모리 인덱스로 만든다. 파일이 없거나 깨져 있어도 예외를 던지지 않고
 * 비활성 상태로 남는다 - RAG는 판정 품질을 높이는 보조 수단이지 필수 경로가 아니다(fail-open).
 */
@Component
public class SpamRagService {

    private static final Logger log = LoggerFactory.getLogger(SpamRagService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 질의로 쓰는 본문 발췌 길이 - 템플릿 식별에는 앞부분이면 충분하고, 길수록 잡음이 는다. */
    private static final int QUERY_BODY_CHARS = 600;

    private final GatewayProperties.SpamFilter.Rag config;
    private final SpamExampleIndex index;

    @Autowired
    public SpamRagService(GatewayProperties properties) {
        this.config = properties.getSpamFilter().getRag();
        this.index = load(config);
    }

    /** 테스트/도구용: 이미 만든 인덱스를 그대로 쓴다. */
    public SpamRagService(GatewayProperties.SpamFilter.Rag config, SpamExampleIndex index) {
        this.config = config;
        this.index = index;
    }

    public boolean isActive() {
        return index != null;
    }

    public List<SpamExampleIndex.Hit> retrieve(SpamCheckRequest request) {
        if (index == null) {
            return Collections.emptyList();
        }
        try {
            String body = RagText.visibleText(request.getBody());
            if (body.length() > QUERY_BODY_CHARS) {
                body = body.substring(0, QUERY_BODY_CHARS);
            }
            List<SpamExampleIndex.Hit> hits = index.search(
                    (request.getSubject() == null ? "" : request.getSubject()) + " " + body,
                    config.getTopK(), config.getMinSimilarity());
            if (log.isDebugEnabled()) {
                log.debug("RAG 검색: 유사 사례 {}건", hits.size());
            }
            return hits;
        } catch (Exception e) {
            log.warn("RAG 검색 실패, 사례 없이 판정한다: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public int maxExampleChars() {
        return config.getMaxExampleChars();
    }

    private static SpamExampleIndex load(GatewayProperties.SpamFilter.Rag config) {
        if (!config.isEnabled()) {
            return null;
        }
        String path = config.getExamplesFile();
        if (path == null || path.trim().isEmpty() || !new File(path).isFile()) {
            log.warn("RAG가 켜져 있지만 사례 파일이 없어 비활성으로 동작한다: '{}'", path);
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            List<SpamExampleIndex.Example> examples = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonNode n = MAPPER.readTree(line);
                examples.add(new SpamExampleIndex.Example(n.path("subject").asText(""), n.path("snippet").asText(""),
                        n.path("domain").asText(""), n.path("count").asInt(1)));
            }
            SpamExampleIndex built = SpamExampleIndex.build(examples);
            log.info("RAG 사례 인덱스 로드 완료: {}건 ({})", built.size(), path);
            return built;
        } catch (Exception e) {
            log.warn("RAG 사례 파일 로드 실패, 비활성으로 동작한다: {}", e.getMessage());
            return null;
        }
    }
}
