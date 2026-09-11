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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
            private String model = "gemini-2.0-flash-lite";

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
}
