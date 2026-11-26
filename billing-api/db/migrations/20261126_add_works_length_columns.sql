-- works の本文文字数を事前計算し、長編順の検索に使うインデックスを追加する。
-- 前提: MySQL 8.0。追加カラムとインデックスが存在しないことを確認し、
-- billing-api と react-app のロールバックタグを作成してから、works へ書き込む
-- n8n ワークフローを一時停止する。テーブルコピー用の空き容量を確保する。
-- 適用: mysql の --database=aozora_db を指定してこのファイルを実行する。
-- 所要時間の目安: MySQL 8.0.46、17,822 行、DATA_LENGTH 約 1.26 GB の
-- リハーサルで約 107 秒。本番は約 2 分を目安とし、I/O とロック待ちで変動する。
-- LOCK=SHARED ではコピー中の書き込みがブロックされる。
-- 適用後の確認 SQL:
-- SHOW CREATE TABLE works;
-- SELECT work_id, full_text_len, CHAR_LENGTH(full_text) AS full_chars,
--        body_text_len, CHAR_LENGTH(body_text) AS body_chars
-- FROM works ORDER BY work_id LIMIT 10;
-- SELECT work_id, body_text_len, CHAR_LENGTH(body_text) AS body_chars
-- FROM works WHERE category = 'Gutenberg' AND body_text IS NOT NULL
-- ORDER BY work_id LIMIT 10;
-- 確認後に停止したワークフローを再開し、billing-api をデプロイする。
-- ロールバック: 新版をデプロイ済みなら先に旧版 billing-api へ戻す。
-- aozora_db を選択し、以下を別途実行してから SHOW CREATE TABLE works で確認する。
-- ALTER TABLE works
--     DROP INDEX idx_works_full_text_len,
--     DROP INDEX idx_works_category_body_len,
--     DROP COLUMN full_text_len,
--     DROP COLUMN body_text_len;

ALTER TABLE works
    ADD COLUMN full_text_len INT GENERATED ALWAYS AS (CHAR_LENGTH(full_text)) STORED,
    ADD COLUMN body_text_len INT GENERATED ALWAYS AS (CHAR_LENGTH(body_text)) STORED,
    ADD INDEX idx_works_full_text_len (full_text_len DESC, work_id ASC),
    ADD INDEX idx_works_category_body_len (category, body_text_len DESC, work_id ASC),
    ALGORITHM=COPY, LOCK=SHARED;
