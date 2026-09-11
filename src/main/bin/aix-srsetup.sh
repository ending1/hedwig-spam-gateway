#!/bin/sh
# AIX SRC(System Resource Controller)에 게이트웨이를 respawn 서브시스템으로 등록하는 예시 스크립트.
# 스펙 3.4 워치독 요구사항의 AIX 대응. 1회성 등록 스크립트이며, root로 실행한다.
#
# 사용법: ./aix-srsetup.sh /opt/hedwig-gateway

GW_HOME="${1:-/opt/hedwig-gateway}"
JAVA_HOME="${JAVA_HOME:?JAVA_HOME must be set}"

mkssys -s hedwig_gateway \
    -p "$JAVA_HOME/bin/java -Xms256m -Xmx512m -jar $GW_HOME/hedwig-spam-gateway.jar --spring.config.location=$GW_HOME/conf/application.yml" \
    -u 0 \
    -S -n 15 -f 9 \
    -R \
    -G hedwig_gateway_grp

echo "등록 완료. 아래 명령으로 기동/상태확인:"
echo "  startsrc -s hedwig_gateway"
echo "  lssrc -s hedwig_gateway"
echo "  stopsrc -s hedwig_gateway"
