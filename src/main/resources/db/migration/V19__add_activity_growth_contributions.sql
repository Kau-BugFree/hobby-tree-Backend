-- LOCAL DEPENDENCY CANDIDATE: requires PR #123's actual V18. Not a reserved develop version.
-- Pause all record/growth/baseline writers across migration AND reader rollout. These locks
-- stabilize this transaction only; they do not synchronize old writers after COMMIT.
LOCK TABLE hobbies, user_hobbies, activity_records IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_index i ON i.indexrelid = c.conindid
         WHERE c.conrelid = 'activity_records'::regclass AND c.contype = 'u'
           AND c.convalidated AND i.indisvalid AND i.indisunique
           AND cardinality(c.conkey) = 2
           AND c.conkey @> ARRAY[
               (SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='user_hobby_id'),
               (SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='activity_id')
           ]::smallint[]
    ) THEN
        RAISE EXCEPTION 'Growth ledger audit: existing record pair UNIQUE is missing or invalid' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_index i ON i.indexrelid=c.conindid
        WHERE c.conrelid='activity_records'::regclass AND c.contype='p'
          AND i.indisvalid AND c.conkey=ARRAY[
              (SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='activity_record_id')
          ]::smallint[]
    ) OR (SELECT count(*) FROM pg_constraint c
          WHERE c.conrelid='activity_records'::regclass AND c.contype='f' AND c.convalidated
            AND ((c.confrelid='user_hobbies'::regclass
                  AND c.conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='user_hobby_id')]::smallint[]
                  AND c.confkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=c.confrelid AND attname='user_hobby_id')]::smallint[])
              OR (c.confrelid='activities'::regclass
                  AND c.conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=c.conrelid AND attname='activity_id')]::smallint[]
                  AND c.confkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=c.confrelid AND attname='activity_id')]::smallint[]))) <> 2 THEN
        RAISE EXCEPTION 'Growth ledger audit: predecessor primary/foreign key is missing or invalid' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid IN ('activity_records'::regclass, 'user_hobbies'::regclass, 'hobbies'::regclass)
                 AND NOT convalidated) THEN
        RAISE EXCEPTION 'Growth ledger audit: predecessor constraints are not validated' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_attribute WHERE attrelid='activity_records'::regclass
          AND attname IN ('activity_record_id','user_hobby_id','activity_id','activity_status',
                          'step_progress','attempt_count','growth_counted','created_at','updated_at')
          AND NOT attnotnull
    ) THEN
        RAISE EXCEPTION 'Growth ledger audit: predecessor NOT NULL constraint was weakened' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM activity_records r
        LEFT JOIN user_hobbies t ON t.user_hobby_id=r.user_hobby_id
        LEFT JOIN activities a ON a.activity_id=r.activity_id
        WHERE t.user_hobby_id IS NULL OR a.activity_id IS NULL OR r.growth_counted IS NULL
           OR (r.growth_counted AND (r.activity_status IS DISTINCT FROM 'COMPLETED'
               OR r.growth_stage_number IS NULL OR r.growth_stage_number NOT BETWEEN 0 AND 5))
           OR (NOT r.growth_counted AND r.growth_stage_number IS NOT NULL)
    ) OR EXISTS (
        SELECT 1 FROM activity_records WHERE growth_counted
        GROUP BY user_hobby_id, activity_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Growth ledger audit: invalid or duplicate historical contribution; no data corrected' USING ERRCODE = '23514';
    END IF;
    -- Diagnose a baseline mismatch; never recalculate or redistribute historical stages.
    IF EXISTS (
        SELECT 1 FROM user_hobbies t JOIN hobbies h USING (hobby_id)
        LEFT JOIN activity_records r ON r.user_hobby_id=t.user_hobby_id
        GROUP BY t.user_hobby_id, t.current_stage_number, h.activities_per_stage
        HAVING t.current_stage_number <> least(5,
                   count(r.activity_record_id) FILTER (WHERE r.growth_counted) / h.activities_per_stage)
            OR count(r.activity_record_id) FILTER
                   (WHERE r.growth_counted AND r.growth_stage_number=t.current_stage_number)
               <> count(r.activity_record_id) FILTER (WHERE r.growth_counted)
                    - t.current_stage_number::bigint * h.activities_per_stage::bigint
    ) THEN
        RAISE EXCEPTION 'Growth ledger audit: tree stage or credited-stage bucket mismatch; approval required, no data corrected' USING ERRCODE = '23514';
    END IF;
END;
$$;

-- Retain the existing pair UNIQUE; the triple key validates provenance without changing rows.
ALTER TABLE activity_records ADD CONSTRAINT uq_records_origin_tree_activity
    UNIQUE(activity_record_id, user_hobby_id, activity_id);

CREATE TABLE activity_growth_contributions (
    contribution_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_hobby_id BIGINT NOT NULL REFERENCES user_hobbies(user_hobby_id) ON DELETE CASCADE,
    activity_id BIGINT NOT NULL REFERENCES activities(activity_id),
    origin_record_id BIGINT NOT NULL,
    credited_stage_number SMALLINT NOT NULL CHECK (credited_stage_number BETWEEN 0 AND 5),
    source VARCHAR(20) NOT NULL CHECK (source IN ('LEGACY_RECORD','SELF','EXPERIENCE')),
    credited_at TIMESTAMPTZ,
    migrated_at TIMESTAMPTZ,
    CONSTRAINT uq_growth_tree_activity UNIQUE(user_hobby_id, activity_id),
    CONSTRAINT uq_growth_origin_record UNIQUE(origin_record_id),
    CONSTRAINT fk_growth_origin_tree_activity FOREIGN KEY(origin_record_id,user_hobby_id,activity_id)
        REFERENCES activity_records(activity_record_id,user_hobby_id,activity_id),
    CONSTRAINT ck_growth_provenance_time CHECK (
        (source='LEGACY_RECORD' AND migrated_at IS NOT NULL)
        OR (source IN ('SELF','EXPERIENCE') AND credited_at IS NOT NULL AND migrated_at IS NULL))
);
CREATE INDEX idx_growth_tree_stage ON activity_growth_contributions(user_hobby_id,credited_stage_number);
COMMENT ON COLUMN activity_growth_contributions.credited_at IS
    'Actual known credit time, NULL when legacy recognition time cannot be established. Never inferred from finished_at.';
COMMENT ON COLUMN activity_growth_contributions.migrated_at IS
    'Actual ledger import time, not a historical credit or content modification time.';

INSERT INTO activity_growth_contributions
    (user_hobby_id,activity_id,origin_record_id,credited_stage_number,source,credited_at,migrated_at)
SELECT user_hobby_id,activity_id,activity_record_id,growth_stage_number,'LEGACY_RECORD',NULL,clock_timestamp()
FROM activity_records WHERE growth_counted;

DO $$
BEGIN
    -- Both directions: counts alone could hide a missing or incorrectly mapped contribution.
    IF EXISTS (
        SELECT 1 FROM activity_records r
        FULL JOIN activity_growth_contributions g ON g.origin_record_id=r.activity_record_id
        WHERE (r.growth_counted IS TRUE AND
                (g.contribution_id IS NULL OR g.user_hobby_id IS DISTINCT FROM r.user_hobby_id
                 OR g.activity_id IS DISTINCT FROM r.activity_id
                 OR g.credited_stage_number IS DISTINCT FROM r.growth_stage_number
                 OR g.source <> 'LEGACY_RECORD' OR g.credited_at IS NOT NULL OR g.migrated_at IS NULL))
           OR (g.contribution_id IS NOT NULL AND r.growth_counted IS DISTINCT FROM TRUE)
    ) OR EXISTS (
        (SELECT user_hobby_id,growth_stage_number,count(*) FROM activity_records WHERE growth_counted
         GROUP BY user_hobby_id,growth_stage_number
         EXCEPT
         SELECT user_hobby_id,credited_stage_number,count(*) FROM activity_growth_contributions
         GROUP BY user_hobby_id,credited_stage_number)
        UNION ALL
        (SELECT user_hobby_id,credited_stage_number,count(*) FROM activity_growth_contributions
         GROUP BY user_hobby_id,credited_stage_number
         EXCEPT
         SELECT user_hobby_id,growth_stage_number,count(*) FROM activity_records WHERE growth_counted
         GROUP BY user_hobby_id,growth_stage_number)
    ) THEN
        RAISE EXCEPTION 'Growth ledger verification: row or stage-count mismatch; rolling back entire migration' USING ERRCODE = '23514';
    END IF;
END;
$$;
