-- Read-only source audit, usable BEFORE the candidate ledger migration.
-- Run only in an approved READ ONLY / REPEATABLE READ session. This task never uses RDS.
-- Results describe anomalies; no row is corrected, hidden, completed, or deleted.
SELECT conrelid::regclass AS relation, conname, contype, convalidated,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN ('activity_records'::regclass,'user_hobbies'::regclass,'hobbies'::regclass)
ORDER BY conrelid,conname;

SELECT attname,attnotnull
FROM pg_attribute
WHERE attrelid='activity_records'::regclass AND attnum>0 AND NOT attisdropped
ORDER BY attnum;

SELECT r.activity_record_id,r.user_hobby_id,r.activity_id,r.activity_status,
       r.growth_counted,r.growth_stage_number
FROM activity_records r
LEFT JOIN user_hobbies t ON t.user_hobby_id=r.user_hobby_id
LEFT JOIN activities a ON a.activity_id=r.activity_id
WHERE t.user_hobby_id IS NULL OR a.activity_id IS NULL OR r.growth_counted IS NULL
   OR (r.growth_counted AND (r.activity_status IS DISTINCT FROM 'COMPLETED' OR r.growth_stage_number IS NULL
       OR r.growth_stage_number NOT BETWEEN 0 AND 5))
   OR (NOT r.growth_counted AND r.growth_stage_number IS NOT NULL);

SELECT user_hobby_id,activity_id,count(*) AS credited_rows
FROM activity_records WHERE growth_counted
GROUP BY user_hobby_id,activity_id HAVING count(*)>1;

WITH totals AS (
    SELECT t.user_hobby_id,t.current_stage_number AS stage,h.activities_per_stage AS k,
           count(r.activity_record_id) FILTER(WHERE r.growth_counted) AS n,
           count(r.activity_record_id) FILTER
               (WHERE r.growth_counted AND r.growth_stage_number=t.current_stage_number) AS c
    FROM user_hobbies t JOIN hobbies h USING(hobby_id)
    LEFT JOIN activity_records r ON r.user_hobby_id=t.user_hobby_id
    GROUP BY t.user_hobby_id,t.current_stage_number,h.activities_per_stage
)
SELECT *,least(5,n/k) AS diagnostic_stage,n-stage::bigint*k::bigint AS diagnostic_bucket
FROM totals
WHERE stage<>least(5,n/k) OR c<>n-stage::bigint*k::bigint;

-- Informational for A1; these existing rows remain untouched. A2 needs its own policy gate.
SELECT user_hobby_id,count(*) AS in_progress_count,
       array_agg(activity_record_id ORDER BY activity_record_id) AS record_ids
FROM activity_records WHERE activity_status='IN_PROGRESS'
GROUP BY user_hobby_id HAVING count(*)>1;
