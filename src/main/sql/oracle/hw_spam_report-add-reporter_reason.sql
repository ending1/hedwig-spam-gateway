-- 신고자 사유 컬럼 추가(기존 DB용). 게이트웨이가 시작할 때 컬럼이 없으면 같은 내용을 한 번 자동 실행한다. 수동으로 먼저 실행해도 된다.
ALTER TABLE hw_spam_report ADD (reporter_reason varchar2(500 CHAR));
