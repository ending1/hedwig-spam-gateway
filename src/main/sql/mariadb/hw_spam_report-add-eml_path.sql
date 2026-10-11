-- 신고 메일 원본(EML) 파일 이름 컬럼 추가(기존 DB용). 게이트웨이가 시작할 때 컬럼이 없으면 같은 내용을 한 번 자동 실행한다.
ALTER TABLE hw_spam_report ADD COLUMN eml_path VARCHAR(255) NULL;
