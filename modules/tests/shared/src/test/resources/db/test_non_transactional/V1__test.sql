CREATE TYPE test_enum_type AS ENUM ('T1');

CREATE TABLE test (template test_enum_type);

ALTER TYPE test_enum_type ADD VALUE 'T2';

INSERT INTO test (template) VALUES ('T2');
