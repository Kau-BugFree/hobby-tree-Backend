-- Issue #200: nicknames must no longer be allowed to duplicate (API.md API-06 previously
-- documented "중복 허용 제안" - that proposal is reversed here). Postgres's default UNIQUE
-- constraint on a text column is already exact/case-sensitive (no collation-based folding), and
-- treats multiple NULLs as distinct, so users who haven't set a nickname yet never conflict.
ALTER TABLE users
  ADD CONSTRAINT uq_users_nickname UNIQUE (nickname);
