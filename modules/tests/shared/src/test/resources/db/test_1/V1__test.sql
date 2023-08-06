CREATE TYPE test_enum_type AS ENUM ('T1_ONE', 't2_two', 't3_Three', 'T4_FOUR', 'T5_FIVE', 'T6Six', 'MULTIPLE_WORD_ENUM');

-- some comment
CREATE TABLE test (
  -- ignore this...
  id SERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  name text, -- ignore this too
  name_2 varchar NOT NULL, -- and this as well
  number int,
  template test_enum_type
);

--SELECT 1
