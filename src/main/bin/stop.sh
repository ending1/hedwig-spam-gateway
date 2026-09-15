#!/bin/sh
# start.sh가 -D<PNAME>로 표시해 둔 프로세스를 찾아 정상 종료 후, 남아있으면 강제 종료한다.
# (Hedwig assembly/src/release/bin/run.sh stop과 동일한 방식)

PNAME="HEDWIG_SPAM_GATEWAY"

pid=`ps -eaf | grep "$PNAME" | grep -v grep | awk '{print $2}'`
if [ -z "$pid" ]; then
    echo "$PNAME is not running."
    exit 0
fi

echo "Stopping $PNAME (pid=$pid)..."
kill $pid
sleep 2
if kill -0 $pid 2>/dev/null; then
    kill -9 $pid
fi
echo "$PNAME stopped."
