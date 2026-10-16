package com.hs.mail.gateway.chat;

import java.util.List;

/** 텍스트를 벡터로 바꾸는 임베딩 모델. 실제 구현은 Ollama, 테스트에서는 가짜로 대체한다. */
public interface TextEmbedder {

    /** 입력 순서대로 임베딩을 돌려준다. 연결 실패 등은 예외. */
    List<float[]> embed(List<String> texts) throws Exception;
}
