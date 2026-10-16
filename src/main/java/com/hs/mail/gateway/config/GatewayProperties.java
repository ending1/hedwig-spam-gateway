package com.hs.mail.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * hedwig-spam-gateway-spec.md 7장 설정 항목을 매핑한다. 하드코딩 금지 원칙에 따라
 * 모든 임계치/주기/정책은 application.yml에서 주입한다.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private int listenPort = 2525;
    private String instanceId = "gateway-1";
    /** gateway-sql.properties 키 접두사 (ansi/oracle/mariadb/mssql). Hedwig의 dbtype 접두사 컨벤션과 동일. */
    private String dbDialect = "ansi";
    /**
     * true면 Linux epoll SO_REUSEPORT로 동일 포트를 여러 인스턴스가 공유해 커널이 커넥션을 분배한다
     * (스펙 3.1 "로드밸런서 없이 단순 병렬 기동"). Linux가 아니면 자동으로 무시되고 일반 NIO로 동작한다.
     */
    private boolean reusePort = true;

    private final RateLimit rateLimit = new RateLimit();
    private final Ban ban = new Ban();
    private final Failover failover = new Failover();
    private final OsBlock osBlock = new OsBlock();
    private final Backend backend = new Backend();
    private final Outbound outbound = new Outbound();
    private final SpamFilter spamFilter = new SpamFilter();
    private final Rbl rbl = new Rbl();
    private final Greylist greylist = new Greylist();
    private final MailList mailList = new MailList();
    private final RuleFilter ruleFilter = new RuleFilter();
    private final Admin admin = new Admin();
    private final EventLog eventLog = new EventLog();
    private final Chat chat = new Chat();
    private final Auth auth = new Auth();
    private final Recipient recipient = new Recipient();
    private final SpamReportOptions spamReport = new SpamReportOptions();

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getDbDialect() {
        return dbDialect;
    }

    public void setDbDialect(String dbDialect) {
        this.dbDialect = dbDialect;
    }

    public boolean isReusePort() {
        return reusePort;
    }

    public void setReusePort(boolean reusePort) {
        this.reusePort = reusePort;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Ban getBan() {
        return ban;
    }

    public Failover getFailover() {
        return failover;
    }

    public OsBlock getOsBlock() {
        return osBlock;
    }

    public Backend getBackend() {
        return backend;
    }

    public Outbound getOutbound() {
        return outbound;
    }

    public SpamFilter getSpamFilter() {
        return spamFilter;
    }

    public Rbl getRbl() {
        return rbl;
    }

    public Greylist getGreylist() {
        return greylist;
    }

    public MailList getMailList() {
        return mailList;
    }

    public RuleFilter getRuleFilter() {
        return ruleFilter;
    }

    public Admin getAdmin() {
        return admin;
    }

    public EventLog getEventLog() {
        return eventLog;
    }

    public Chat getChat() {
        return chat;
    }

    public Auth getAuth() {
        return auth;
    }

    public Recipient getRecipient() {
        return recipient;
    }

    /**
     * 수신자 도메인 정책(RCPT 단계). 게이트웨이 뒤의 Hedwig는 모든 접속을 게이트웨이(127.0.0.1)로 보므로 자체 릴레이 제한이
     * 외부 발신자에게 적용되지 않는다. 그래서 게이트웨이가 "우리 도메인으로 오는 메일만 받는다"를 직접 지킨다.
     * localDomains가 비어 있으면 이 정책은 꺼진 것과 같다(기존 동작 유지).
     */
    public static class Recipient {
        /** 이 서버가 받는 수신 도메인(대소문자 무시). ".example.com"처럼 점으로 시작하면 그 하위 도메인 전체. */
        private java.util.List<String> localDomains = new java.util.ArrayList<>();
        /** 우리 도메인이 아닌 곳으로도 보낼 수 있는(릴레이 허용) 접속 대역. 비워 두면 누구도 릴레이할 수 없다. */
        private java.util.List<String> relayNetworks = new java.util.ArrayList<>();

        public java.util.List<String> getLocalDomains() { return localDomains; }
        public void setLocalDomains(java.util.List<String> localDomains) { this.localDomains = localDomains; }
        public java.util.List<String> getRelayNetworks() { return relayNetworks; }
        public void setRelayNetworks(java.util.List<String> relayNetworks) { this.relayNetworks = relayNetworks; }
    }

    /**
     * 수신 메일 인증 검증(SPF/DKIM/DMARC). 결과는 Authentication-Results 헤더와 룰 점수(auth-* 구조 룰)로만 쓰이며
     * 메일을 거부하지 않는다. DNS 조회가 많아 메일당 전체 시간 상한(timeoutMillis)을 두고, 초과/오류는 temperror(통과).
     */
    public static class Auth {
        private boolean enabled = true;
        private int timeoutMillis = 6000;
        private int dnsTimeoutMillis = 2000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
        public int getDnsTimeoutMillis() { return dnsTimeoutMillis; }
        public void setDnsTimeoutMillis(int dnsTimeoutMillis) { this.dnsTimeoutMillis = dnsTimeoutMillis; }
    }

    /**
     * 관리자 챗봇. 로컬 LLM(Ollama)이 질문의 의도만 분류하고, 조회와 답변 작성은 서버의 고정된 코드가 한다.
     * 모델은 환경변수 GATEWAY_CHAT_BASEURL / GATEWAY_CHAT_MODEL로 바꿀 수 있다.
     */
    public static class Chat {
        private boolean enabled = true;
        private String baseUrl = "";
        private String model = "qwen3-coder:30b";
        private int timeoutMillis = 60000;
        private String keepAlive = "30m";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMillis() { return timeoutMillis; }
        public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
        public String getKeepAlive() { return keepAlive; }
        public void setKeepAlive(String keepAlive) { this.keepAlive = keepAlive; }
    }

    /**
     * 메일 처리 이벤트 로그(통계/로그 검색/챗봇 질의의 원천 데이터). 보관 기간은 환경변수
     * {@code GATEWAY_EVENTLOG_RETENTIONDAYS}(또는 gateway.event-log.retention-days)로 지정한다. 0이면 삭제하지 않는다.
     */
    public static class EventLog {
        private boolean enabled = true;
        private int retentionDays = 90;
        private int flushSeconds = 5;
        private int maxQueue = 20000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getFlushSeconds() { return flushSeconds; }
        public void setFlushSeconds(int flushSeconds) { this.flushSeconds = flushSeconds; }
        public int getMaxQueue() { return maxQueue; }
        public void setMaxQueue(int maxQueue) { this.maxQueue = maxQueue; }
    }

    public SpamReportOptions getSpamReport() {
        return spamReport;
    }

    public static class RateLimit {
        private int windowSeconds = 10;
        private int threshold = 100;

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }
    }

    public static class Ban {
        private int defaultDurationMinutes = 60;
        private int pollIntervalSeconds = 5;

        public int getDefaultDurationMinutes() {
            return defaultDurationMinutes;
        }

        public void setDefaultDurationMinutes(int defaultDurationMinutes) {
            this.defaultDurationMinutes = defaultDurationMinutes;
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds;
        }

        public void setPollIntervalSeconds(int pollIntervalSeconds) {
            this.pollIntervalSeconds = pollIntervalSeconds;
        }
    }

    public enum FailoverPolicy {
        FAIL_OPEN, FAIL_CLOSED
    }

    public static class Failover {
        private FailoverPolicy policy = FailoverPolicy.FAIL_OPEN;

        public FailoverPolicy getPolicy() {
            return policy;
        }

        public void setPolicy(FailoverPolicy policy) {
            this.policy = policy;
        }
    }

    public static class OsBlock {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Backend {
        private String host = "127.0.0.1";
        private int port = 25;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    /**
     * Hedwig의 {@code smtp_gateway} 설정으로 발신 메일을 받아 실제 인터넷 발송을 대신 처리하는
     * 아웃바운드 완충(스풀+재시도+격리) 기능 설정. Hedwig의 spool/remote + spool/delay +
     * remote.executor/delay.executor 패턴을 참고해 게이트웨이 프로세스 내부에서 독립 구현한다.
     */
    public static class Outbound {
        private boolean enabled = true;
        private String bindHost = "127.0.0.1";
        private int listenPort = 2526;
        private boolean reusePort = true;
        private String spoolDir = "../spool/outbound";
        private int maxRetries = 5;
        private int retryDelaySeconds = 120;
        private int delayAfterRetries = 2;
        private int workerPoolSize = 20;
        private int workerMaxPoolSize = 40;
        private int delayWorkerPoolSize = 5;
        private int delayWorkerMaxPoolSize = 10;
        private int dnsTimeoutMillis = 5000;
        private int smtpConnectTimeoutMillis = 5000;
        private int smtpTimeoutMillis = 5000;
        private boolean authRequired = false;
        private String authUsername;
        private String authPassword;
        private final Dkim dkim = new Dkim();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Dkim getDkim() {
            return dkim;
        }

        public String getBindHost() {
            return bindHost;
        }

        public void setBindHost(String bindHost) {
            this.bindHost = bindHost;
        }

        public int getListenPort() {
            return listenPort;
        }

        public void setListenPort(int listenPort) {
            this.listenPort = listenPort;
        }

        public boolean isReusePort() {
            return reusePort;
        }

        public void setReusePort(boolean reusePort) {
            this.reusePort = reusePort;
        }

        public String getSpoolDir() {
            return spoolDir;
        }

        public void setSpoolDir(String spoolDir) {
            this.spoolDir = spoolDir;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getRetryDelaySeconds() {
            return retryDelaySeconds;
        }

        public void setRetryDelaySeconds(int retryDelaySeconds) {
            this.retryDelaySeconds = retryDelaySeconds;
        }

        public int getDelayAfterRetries() {
            return delayAfterRetries;
        }

        public void setDelayAfterRetries(int delayAfterRetries) {
            this.delayAfterRetries = delayAfterRetries;
        }

        public int getWorkerPoolSize() {
            return workerPoolSize;
        }

        public void setWorkerPoolSize(int workerPoolSize) {
            this.workerPoolSize = workerPoolSize;
        }

        public int getWorkerMaxPoolSize() {
            return workerMaxPoolSize;
        }

        public void setWorkerMaxPoolSize(int workerMaxPoolSize) {
            this.workerMaxPoolSize = workerMaxPoolSize;
        }

        public int getDelayWorkerPoolSize() {
            return delayWorkerPoolSize;
        }

        public void setDelayWorkerPoolSize(int delayWorkerPoolSize) {
            this.delayWorkerPoolSize = delayWorkerPoolSize;
        }

        public int getDelayWorkerMaxPoolSize() {
            return delayWorkerMaxPoolSize;
        }

        public void setDelayWorkerMaxPoolSize(int delayWorkerMaxPoolSize) {
            this.delayWorkerMaxPoolSize = delayWorkerMaxPoolSize;
        }

        public int getDnsTimeoutMillis() {
            return dnsTimeoutMillis;
        }

        public void setDnsTimeoutMillis(int dnsTimeoutMillis) {
            this.dnsTimeoutMillis = dnsTimeoutMillis;
        }

        public int getSmtpConnectTimeoutMillis() {
            return smtpConnectTimeoutMillis;
        }

        public void setSmtpConnectTimeoutMillis(int smtpConnectTimeoutMillis) {
            this.smtpConnectTimeoutMillis = smtpConnectTimeoutMillis;
        }

        public int getSmtpTimeoutMillis() {
            return smtpTimeoutMillis;
        }

        public void setSmtpTimeoutMillis(int smtpTimeoutMillis) {
            this.smtpTimeoutMillis = smtpTimeoutMillis;
        }

        public boolean isAuthRequired() {
            return authRequired;
        }

        public void setAuthRequired(boolean authRequired) {
            this.authRequired = authRequired;
        }

        public String getAuthUsername() {
            return authUsername;
        }

        public void setAuthUsername(String authUsername) {
            this.authUsername = authUsername;
        }

        public String getAuthPassword() {
            return authPassword;
        }

        public void setAuthPassword(String authPassword) {
            this.authPassword = authPassword;
        }

        /**
         * 이 게이트웨이를 거쳐 나가는 발신 메일에 DKIM-Signature 헤더를 추가한다 (RFC 6376,
         * relaxed/relaxed 정규화, rsa-sha256 고정). 개인키가 없으면 enabled=false로 두면 그만이다.
         */
        public static class Dkim {
            private boolean enabled = false;
            private String domain;
            private String selector = "default";
            private String privateKeyPath;
            private java.util.List<String> headersToSign = new java.util.ArrayList<>(
                    java.util.Arrays.asList("From", "To", "Subject", "Date", "Message-ID"));

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getDomain() {
                return domain;
            }

            public void setDomain(String domain) {
                this.domain = domain;
            }

            public String getSelector() {
                return selector;
            }

            public void setSelector(String selector) {
                this.selector = selector;
            }

            public String getPrivateKeyPath() {
                return privateKeyPath;
            }

            public void setPrivateKeyPath(String privateKeyPath) {
                this.privateKeyPath = privateKeyPath;
            }

            public java.util.List<String> getHeadersToSign() {
                return headersToSign;
            }

            public void setHeadersToSign(java.util.List<String> headersToSign) {
                this.headersToSign = headersToSign;
            }
        }
    }

    public enum SpamFilterProvider {
        NONE, GEMMA_LOCAL, GEMINI, CLAUDE
    }

    /**
     * 인바운드 메일 헤더/제목/본문/수신자를 LLM으로 분석해 스팸 여부를 판정한다 (첨부파일은 범위 밖).
     * 하드 거절에는 쓰지 않고 헤더 태그만 주입하며, 분류기 장애/타임아웃 시 fail-open(정상 처리)한다.
     */
    public static class SpamFilter {
        private boolean enabled = false;
        private SpamFilterProvider provider = SpamFilterProvider.GEMMA_LOCAL;
        private int timeoutMillis = 15000;
        private int maxBodyChars = 4000;
        private int workerPoolSize = 4;

        private final Gemma gemma = new Gemma();
        private final Gemini gemini = new Gemini();
        private final Claude claude = new Claude();
        private final Rag rag = new Rag();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public SpamFilterProvider getProvider() {
            return provider;
        }

        public void setProvider(SpamFilterProvider provider) {
            this.provider = provider;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public int getMaxBodyChars() {
            return maxBodyChars;
        }

        public void setMaxBodyChars(int maxBodyChars) {
            this.maxBodyChars = maxBodyChars;
        }

        public int getWorkerPoolSize() {
            return workerPoolSize;
        }

        public void setWorkerPoolSize(int workerPoolSize) {
            this.workerPoolSize = workerPoolSize;
        }

        public Gemma getGemma() {
            return gemma;
        }

        public Gemini getGemini() {
            return gemini;
        }

        public Claude getClaude() {
            return claude;
        }

        public Rag getRag() {
            return rag;
        }

        /**
         * LLM 판정 시 과거에 스팸으로 확인된 유사 사례를 검색해 프롬프트에 근거로 넣는다(RAG). 사례 파일은
         * 실제 사내 메일에서 파생된 데이터라 저장소/jar에 넣지 않고 서버의 외부 파일로만 둔다.
         */
        public static class Rag {
            private boolean enabled = false;
            /** JSONL(한 줄에 subject/snippet/domain/count). 비어있거나 없으면 RAG는 조용히 비활성. */
            private String examplesFile = "";
            private int topK = 3;
            /** 이 값 미만으로 유사한 사례는 프롬프트에 넣지 않는다(무관한 사례가 판단을 오염시키는 것 방지). */
            private double minSimilarity = 0.35;
            private int maxExampleChars = 300;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getExamplesFile() {
                return examplesFile;
            }

            public void setExamplesFile(String examplesFile) {
                this.examplesFile = examplesFile;
            }

            public int getTopK() {
                return topK;
            }

            public void setTopK(int topK) {
                this.topK = topK;
            }

            public double getMinSimilarity() {
                return minSimilarity;
            }

            public void setMinSimilarity(double minSimilarity) {
                this.minSimilarity = minSimilarity;
            }

            public int getMaxExampleChars() {
                return maxExampleChars;
            }

            public void setMaxExampleChars(int maxExampleChars) {
                this.maxExampleChars = maxExampleChars;
            }
        }

        public static class Gemma {
            private String baseUrl = "http://localhost:11434";
            private String model = "gemma2:7b";

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }

        public static class Gemini {
            private String apiKey;
            private String model = "gemini-3.5-flash-lite";

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }

        public static class Claude {
            private String apiKey;
            private String model = "claude-3-5-haiku-20241022";

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }
    }

    /** RBL(DNSBL) 조회 - 발신 IP를 공개 블랙리스트 존에 조회해 알려진 스팸 발신원을 차단한다. */
    public static class Rbl {
        private boolean enabled = false;
        private java.util.List<String> zones = new java.util.ArrayList<>(
                java.util.Arrays.asList("zen.spamhaus.org", "bl.spamcop.net"));
        private int timeoutMillis = 3000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.util.List<String> getZones() {
            return zones;
        }

        public void setZones(java.util.List<String> zones) {
            this.zones = zones;
        }

        public int getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }

    /**
     * 그레이리스팅 - (발신IP, MAIL FROM, RCPT TO) 삼중항을 처음 보면 일시 거부(450)하고, 표준 재시도
     * 간격 이후 다시 오면 통과시킨다. 스팸봇은 대부분 재시도하지 않는다는 점을 이용한 저비용 필터.
     */
    public static class Greylist {
        private boolean enabled = false;
        private int minRetryDelayMinutes = 5;
        private int maxWindowHours = 24;
        private int trustPeriodDays = 30;
        private int pollIntervalSeconds = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinRetryDelayMinutes() {
            return minRetryDelayMinutes;
        }

        public void setMinRetryDelayMinutes(int minRetryDelayMinutes) {
            this.minRetryDelayMinutes = minRetryDelayMinutes;
        }

        public int getMaxWindowHours() {
            return maxWindowHours;
        }

        public void setMaxWindowHours(int maxWindowHours) {
            this.maxWindowHours = maxWindowHours;
        }

        public int getTrustPeriodDays() {
            return trustPeriodDays;
        }

        public void setTrustPeriodDays(int trustPeriodDays) {
            this.trustPeriodDays = trustPeriodDays;
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds;
        }

        public void setPollIntervalSeconds(int pollIntervalSeconds) {
            this.pollIntervalSeconds = pollIntervalSeconds;
        }
    }

    public enum BlacklistAction {
        REJECT, TAG
    }

    /**
     * 관리자/사용자 화이트리스트-블랙리스트. 발신자(이메일 또는 도메인) 단위로 관리하며,
     * 화이트리스트는 RBL/그레이리스팅/룰기반/LLM 스팸판정을 모두 건너뛰고, 블랙리스트는
     * blacklist-action 설정에 따라 즉시 거절하거나 헤더 태그만 남긴다.
     */
    public static class MailList {
        private boolean enabled = false;
        private BlacklistAction blacklistAction = BlacklistAction.REJECT;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public BlacklistAction getBlacklistAction() {
            return blacklistAction;
        }

        public void setBlacklistAction(BlacklistAction blacklistAction) {
            this.blacklistAction = blacklistAction;
        }
    }

    /** 전통적인 규칙기반(SpamAssassin류) 스팸 점수 필터. LLM 호출 전에 먼저 돌려 확실한 스팸을 빠르게 거른다. */
    public static class RuleFilter {
        private boolean enabled = false;
        private double spamThreshold = 5.0;
        private java.util.List<String> extraKeywords = new java.util.ArrayList<>();
        /** 자사 도메인 - 이 도메인을 발신자로 쓰는데 외부 IP에서 들어오면 사칭(internal-domain-spoof)으로 본다. 비우면 룰 비활성. */
        private java.util.List<String> internalDomains = new java.util.ArrayList<>();
        /**
         * 신뢰(내부) 네트워크 CIDR. 접속 IP가 여기 속하면 Received 헤더를 거슬러 올라가 실제 외부 발신 IP를
         * 찾는다(SpamAssassin의 trusted_networks와 같은 개념). 자사 도메인으로 정상 발송하는 외부 SaaS/릴레이의
         * IP도 여기 넣으면 사칭 판정에서 제외된다.
         */
        private java.util.List<String> trustedNetworks = new java.util.ArrayList<>(java.util.Arrays.asList(
                "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"));

        public java.util.List<String> getInternalDomains() {
            return internalDomains;
        }

        public void setInternalDomains(java.util.List<String> internalDomains) {
            this.internalDomains = internalDomains;
        }

        public java.util.List<String> getTrustedNetworks() {
            return trustedNetworks;
        }

        public void setTrustedNetworks(java.util.List<String> trustedNetworks) {
            this.trustedNetworks = trustedNetworks;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getSpamThreshold() {
            return spamThreshold;
        }

        public void setSpamThreshold(double spamThreshold) {
            this.spamThreshold = spamThreshold;
        }

        public java.util.List<String> getExtraKeywords() {
            return extraKeywords;
        }

        public void setExtraKeywords(java.util.List<String> extraKeywords) {
            this.extraKeywords = extraKeywords;
        }
    }

    /** 관리자 REST API(/admin/*) 접근 제어. api-key가 비어있으면 인증 없이 열려있다 (개발용 기본값). */
    public static class Admin {
        /** 관리자 웹 로그인 계정(ID + BCrypt 해시). 비어 있으면 웹 로그인은 비활성이고 API 키만 쓴다. */
        private java.util.List<AdminUser> users = new java.util.ArrayList<>();
        /** 로그인 세션 유지 시간(분). 마지막 요청 이후 이 시간이 지나면 만료. */
        private int sessionMinutes = 60;
        /** 로그인 연속 실패 허용 횟수. 넘으면 lockMinutes 동안 해당 IP+ID의 로그인을 막는다. */
        private int maxLoginFailures = 5;
        private int lockMinutes = 5;
        private String apiKey;

        public java.util.List<AdminUser> getUsers() {
            return users;
        }

        public void setUsers(java.util.List<AdminUser> users) {
            this.users = users;
        }

        public int getSessionMinutes() {
            return sessionMinutes;
        }

        public void setSessionMinutes(int sessionMinutes) {
            this.sessionMinutes = sessionMinutes;
        }

        public int getMaxLoginFailures() {
            return maxLoginFailures;
        }

        public void setMaxLoginFailures(int maxLoginFailures) {
            this.maxLoginFailures = maxLoginFailures;
        }

        public int getLockMinutes() {
            return lockMinutes;
        }

        public void setLockMinutes(int lockMinutes) {
            this.lockMinutes = lockMinutes;
        }

        /** 스팸 신고 접수(POST /report)용 별도 키. 그룹웨어가 이 키만 갖고 신고를 전달한다. 비어 있으면 admin 키로만 접수. */
        private String reportApiKey;

        public String getReportApiKey() {
            return reportApiKey;
        }

        public void setReportApiKey(String reportApiKey) {
            this.reportApiKey = reportApiKey;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /** 관리자 웹 로그인 계정. passwordHash는 BCrypt($2a$/$2b$) 해시. */
    public static class AdminUser {
        private String username;
        private String passwordHash;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }
    }

    /**
     * 사용자 스팸 신고 처리 방식. 기본은 수동(관리자가 검토/승인). 자동으로 바꾸면 LLM 판정이 기준 이상일 때
     * 관리자 승인 없이 룰을 추가한다. 여기 값은 초기값이고, 대시보드/API에서 바꾼 값은 settingsFile에 저장되어 우선한다.
     */
    public static class SpamReportOptions {
        /** true면 자동 처리(LLM 판정으로 룰 추가/기각), false면 수동(관리자 승인). */
        private boolean autoProcess = false;
        /** 자동 처리에 필요한 LLM 확신도(0~1). */
        private double autoMinConfidence = 0.9;
        /** 자동 추가되는 룰 가중치 상한. 임계치(spam-threshold)보다 낮게 두어 단독으로 스팸 확정하지 못하게 한다. */
        private double autoMaxWeight = 2.0;
        /** 24시간 동안 자동으로 추가할 수 있는 룰 개수 상한(악의적 대량 신고 방어). */
        private int autoMaxPerDay = 20;
        /** 자동 처리 시 확신도 높은 정상(HAM) 판정은 자동 기각할지. */
        private boolean autoDismissHam = true;
        /** 대시보드/API로 바꾼 설정을 저장하는 파일. 상대경로면 게이트웨이 실행 디렉터리 기준. */
        private String settingsFile = "spam-report-settings.json";
        /**
         * 신고된 메일 원본(EML)을 보관하는 디렉터리. 파일은 "{신고ID}.eml"로 저장하고 DB에는 파일 이름만 둔다.
         * 여러 인스턴스를 운영하면 모든 인스턴스가 같은 공유 경로(NFS 등)를 가리키게 설정한다. 상대경로면 실행 디렉터리 기준.
         */
        private String emlDir = "eml";
        /** 신고 메일 원본(EML) 최대 크기(바이트). 넘으면 업로드를 거부한다. 기본 10MB. */
        private long emlMaxBytes = 10L * 1024 * 1024;

        public boolean isAutoProcess() { return autoProcess; }
        public void setAutoProcess(boolean autoProcess) { this.autoProcess = autoProcess; }
        public double getAutoMinConfidence() { return autoMinConfidence; }
        public void setAutoMinConfidence(double autoMinConfidence) { this.autoMinConfidence = autoMinConfidence; }
        public double getAutoMaxWeight() { return autoMaxWeight; }
        public void setAutoMaxWeight(double autoMaxWeight) { this.autoMaxWeight = autoMaxWeight; }
        public int getAutoMaxPerDay() { return autoMaxPerDay; }
        public void setAutoMaxPerDay(int autoMaxPerDay) { this.autoMaxPerDay = autoMaxPerDay; }
        public boolean isAutoDismissHam() { return autoDismissHam; }
        public void setAutoDismissHam(boolean autoDismissHam) { this.autoDismissHam = autoDismissHam; }
        public String getSettingsFile() { return settingsFile; }
        public void setSettingsFile(String settingsFile) { this.settingsFile = settingsFile; }
        public String getEmlDir() { return emlDir; }
        public void setEmlDir(String emlDir) { this.emlDir = emlDir; }
        public long getEmlMaxBytes() { return emlMaxBytes; }
        public void setEmlMaxBytes(long emlMaxBytes) { this.emlMaxBytes = emlMaxBytes; }
    }
}
