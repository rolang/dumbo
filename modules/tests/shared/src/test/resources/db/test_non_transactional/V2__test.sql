ALTER TYPE test_enum_type ADD VALUE 'T2';
CREATE TABLE test (template test_enum_type);
INSERT INTO test (template) VALUES ('T2');
