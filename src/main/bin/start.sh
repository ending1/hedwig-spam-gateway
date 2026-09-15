#!/bin/bash
# Hedwig assembly/src/release/bin/run.sh와 동일한 인터페이스(-D<PNAME> 프로세스 식별, ps -eaf로 조회)를
# 따르되, 시작/종료를 start.sh/stop.sh로 분리했다. conf/log4j2.xml을 지정해 Hedwig처럼 로그 파일
# 위치/롤링/레벨을 재빌드 없이 바꿀 수 있게 한다(파일이 없으면 jar 내장 기본 로그 설정을 그대로 쓴다).
#
# 한 호스트에 여러 인스턴스(예: backend별 게이트웨이)를 띄울 때는 인스턴스마다 GATEWAY_PNAME을
# 다르게 지정해야 ps -eaf 기반 식별이 서로 충돌하지 않는다:
#   GATEWAY_PNAME=HEDWIG_SPAM_GATEWAY_NODE2 ./bin/start.sh

if [ -z "$JAVA_HOME" ]; then
    echo "Cannot find JAVA_HOME. Please set JAVA_HOME before running this script."
    exit 1
fi

cd "$(dirname "$0")"
GW_HOME="$(cd .. && pwd)"

PNAME="${GATEWAY_PNAME:-HEDWIG_SPAM_GATEWAY}"
JAR="$GW_HOME/hedwig-spam-gateway.jar"
LOG4J_CONF="$GW_HOME/conf/log4j2.xml"
JAVA_OPTS="$JAVA_OPTS -Xms256m -Xmx512m -D$PNAME"

if [ -f "$LOG4J_CONF" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dlog4j.configurationFile=file:$LOG4J_CONF"
fi

RUN_CMD="$JAVA_HOME/bin/java $JAVA_OPTS -jar $JAR --spring.config.location=$GW_HOME/conf/application.yml"

# "-D$PNAME"(java의 -D 옵션 형태)로 매칭해야 한다. 단순히 "$PNAME"으로 grep하면 이 스크립트를
# 호출한 부모 셸의 커맨드라인 자체("GATEWAY_PNAME=... ./bin/start.sh" 등, env var 대입 형태로
# $PNAME 문자열을 그대로 포함)까지 오탐으로 잡혀버리는 문제가 실제로 있었다.
running=`ps -eaf | grep -- "-D$PNAME" | grep -v grep | awk '{print $2}'`
if [ -n "$running" ]; then
    echo "$PNAME is already running (pid=$running)"
    exit 1
fi

mkdir -p "$GW_HOME/logs"
echo "Starting $PNAME..."
# sudo(포트 25/143처럼 privileged 포트 바인딩 시 필요)로 기동하는 경우, nohup만으로는
# sudo가 할당한 pty/세션이 정리될 때 백그라운드 자식까지 함께 끊기는 현상이 관찰되었다.
# setsid로 완전히 새 세션을 만들어 controlling terminal/부모 세션과 분리해야 안전하다.
setsid $RUN_CMD < /dev/null > "$GW_HOME/logs/gateway.console" 2>&1 &
echo "$PNAME started (pid=$!)."
