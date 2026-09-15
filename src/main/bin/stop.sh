#!/bin/sh
# start.sh가 -D<PNAME>로 표시해 둔 프로세스를 찾아 정상 종료 후, 남아있으면 강제 종료한다.
# (Hedwig assembly/src/release/bin/run.sh stop과 동일한 방식)
# 여러 인스턴스를 띄운 경우 start.sh와 동일한 GATEWAY_PNAME을 지정해야 한다.

PNAME="${GATEWAY_PNAME:-HEDWIG_SPAM_GATEWAY}"

# start.sh와 동일한 이유로 "-D$PNAME"(java -D 옵션 형태)로 매칭한다 - 단순 "$PNAME" 매칭은
# 이 스크립트를 호출한 부모 셸의 "GATEWAY_PNAME=..." 커맨드라인 자체를 오탐해 자기 자신(부모 셸)을
# 죽여버리는 사고로 이어질 수 있다(실제로 발생했던 문제).
pid=`ps -eaf | grep -- "-D$PNAME" | grep -v grep | awk '{print $2}'`
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
