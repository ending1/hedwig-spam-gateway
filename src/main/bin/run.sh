#!/bin/sh
# Hedwig assembly/src/release/bin/run.sh와 동일한 인터페이스(start|stop|restart)로 통일해
# 현장 엔지니어의 학습 비용을 낮춘다. systemd/SRC가 없는 환경(수동 운영)에서 사용.

usage() {
    echo "Usage: $0 {start|stop|restart}"
    exit 1
}

[ $# -gt 0 ] || usage

if [ -z "$JAVA_HOME" ]; then
    echo "Cannot find JAVA_HOME. Please set JAVA_HOME before running this script."
    exit 1
fi

PNAME="HEDWIG_SPAM_GATEWAY"
JAR="../hedwig-spam-gateway.jar"
JAVA_OPTS="$JAVA_OPTS -Xms256m -Xmx512m"

RUN_CMD="$JAVA_HOME/bin/java -D$PNAME $JAVA_OPTS -jar $JAR --spring.config.location=../conf/application.yml"

start() {
    running=`ps -eaf | grep "$PNAME" | grep -v grep | awk '{print $2}'`
    if [ -n "$running" ]; then
        echo "$PNAME is already running (pid=$running)"
        exit 1
    fi
    echo "Starting $PNAME..."
    nohup $RUN_CMD > ../logs/gateway.console 2>&1 &
    echo "$PNAME started."
}

stop() {
    pid=`ps -eaf | grep "$PNAME" | grep -v grep | awk '{print $2}'`
    if [ -z "$pid" ]; then
        echo "$PNAME is not running."
        return
    fi
    echo "Stopping $PNAME (pid=$pid)..."
    kill $pid
    sleep 2
    if kill -0 $pid 2>/dev/null; then
        kill -9 $pid
    fi
    echo "$PNAME stopped."
}

case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        stop
        sleep 5
        start
        ;;
    *)
        usage
        ;;
esac
