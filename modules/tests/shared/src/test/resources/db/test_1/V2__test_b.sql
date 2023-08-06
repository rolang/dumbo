CREATE TABLE test_v2 (
  key_a VARCHAR NOT NULL,
  key_b VARCHAR NOT NULL,
  val_1 VARCHAR[] NOT NULL,
  val_2 INT[],
  val_3 JSON,
  val_4 JSONB,
  val_5 INT[],
  date DATE,
  PRIMARY KEY (key_a, key_b)
);

CREATE FUNCTION add(integer, integer) RETURNS integer
    AS 'select $1 + $2;'
    LANGUAGE SQL
    IMMUTABLE
    RETURNS NULL ON NULL INPUT;

CREATE OR REPLACE FUNCTION increment(i integer) RETURNS integer AS $$
  BEGIN
          RETURN i + 1;
  END;
$$ LANGUAGE plpgsql;
