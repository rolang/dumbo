CREATE TABLE test_v3 (
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
