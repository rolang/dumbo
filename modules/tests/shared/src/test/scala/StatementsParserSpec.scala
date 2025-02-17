// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

class StatementsSpec extends ffstest.FTest {

  test("split into single statements") {
    val res = Statements.intoSingleStatements(
      """
        |INSERT INTO test (template) VALUES ('T2');
        |
        |CREATE FUNCTION add(integer, integer) RETURNS integer
        |    AS 'select $1 + $2;'
        |    -- a comment in function body
        |    LANGUAGE SQL
        |    IMMUTABLE
        |    RETURNS NULL ON NULL INPUT;
        |
        |CREATE OR REPLACE FUNCTION increment(i integer) RETURNS integer AS $$
        |  -- a comment in function body 2
        |  BEGIN
        |          RETURN i + 1;
        |  END;
        |$$ LANGUAGE plpgsql;
        |
        |/* multiline; comment
        |* with; nesting: /*; nested block comment */
        |;*/
        |CREATE TABLE table_name( id character varying(50),
        |                         data json NOT NULL,
        |                         active boolean NOT NULL,
        |                         created_at timestamp with time zone NOT NULL,
        |                         updated_at timestamp with time zone NOT NULL,
        |                         CONSTRAINT table_name_pkey PRIMARY KEY (id)
        |                       );
        |
        |INSERT INTO table_name (id, data, active, created_at, updated_at)
        |VALUES (
        |  '1',
        |  format('{
        |     "id": "1",
        |     "data":{
        |        "text": ";'';abc;''; \"efg\" ; -- include me in this text"
        |     },
        |     "active":true,
        |     "created_at": "%s",
        |     "updated_at": "%s"}',
        |     now(),
        |     now()
        |  )::json,
        | true,
        | now(),
        | now());
        |
        |-- ignore this
        |  -- and this
        |
        |  /* ad this; multiline comment
        |* with nesting; /* nested block comment; */
        |;*/
        |select 1
        |""".stripMargin
    )

    assertEquals(res.length, 6)

    assertEquals(res(0).trim, "INSERT INTO test (template) VALUES ('T2')")

    assertEquals(
      res(1).trim,
      """|CREATE FUNCTION add(integer, integer) RETURNS integer
         |    AS 'select $1 + $2;'
         |    -- a comment in function body
         |    LANGUAGE SQL
         |    IMMUTABLE
         |    RETURNS NULL ON NULL INPUT""".stripMargin,
    )

    assertEquals(
      res(2).trim,
      """|CREATE OR REPLACE FUNCTION increment(i integer) RETURNS integer AS $$
         |  -- a comment in function body 2
         |  BEGIN
         |          RETURN i + 1;
         |  END;
         |$$ LANGUAGE plpgsql""".stripMargin,
    )

    assertEquals(
      res(3).trim,
      """|/* multiline; comment
         |* with; nesting: /*; nested block comment */
         |;*/
         |CREATE TABLE table_name( id character varying(50),
         |                         data json NOT NULL,
         |                         active boolean NOT NULL,
         |                         created_at timestamp with time zone NOT NULL,
         |                         updated_at timestamp with time zone NOT NULL,
         |                         CONSTRAINT table_name_pkey PRIMARY KEY (id)
         |                       )""".stripMargin,
    )

    assertEquals(
      res(4).trim,
      """|INSERT INTO table_name (id, data, active, created_at, updated_at)
         |VALUES (
         |  '1',
         |  format('{
         |     "id": "1",
         |     "data":{
         |        "text": ";'';abc;''; \"efg\" ; -- include me in this text"
         |     },
         |     "active":true,
         |     "created_at": "%s",
         |     "updated_at": "%s"}',
         |     now(),
         |     now()
         |  )::json,
         | true,
         | now(),
         | now())""".stripMargin,
    )

    assertEquals(
      res(5).trim,
      """|-- ignore this
         |  -- and this
         |
         |  /* ad this; multiline comment
         |* with nesting; /* nested block comment; */
         |;*/
         |select 1""".stripMargin,
    )
  }

}
