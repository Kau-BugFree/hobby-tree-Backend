-- Issue #191: lets a single experiences row be found-or-created idempotently for a given
-- external business (e.g. a Naver local search result), instead of a new row per search.
-- NULL/NULL stays non-unique in Postgres, so existing admin-curated rows (both columns NULL)
-- are unaffected.
ALTER TABLE experiences
  ADD COLUMN external_source VARCHAR(20),
  ADD COLUMN external_ref VARCHAR(200);

ALTER TABLE experiences
  ADD CONSTRAINT uq_experiences_external_source_ref UNIQUE (external_source, external_ref);
