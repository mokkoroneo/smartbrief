-- DDIA第7章対応：無料会員の日次閲覧カウンター実体化カラムの追加
ALTER TABLE users
    ADD COLUMN daily_view_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_view_date DATE NULL;