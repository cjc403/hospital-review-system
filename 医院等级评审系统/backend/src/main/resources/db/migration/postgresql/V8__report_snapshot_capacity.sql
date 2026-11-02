-- 扩大容量，保留原快照；报告仍按生成时的全量内容固定。
ALTER TABLE v3_report ALTER COLUMN snapshot_json TYPE VARCHAR(4000000);
