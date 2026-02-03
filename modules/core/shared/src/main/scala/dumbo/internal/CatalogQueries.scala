// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

// Queries for schema object discovery, mirroring Flyway's PostgreSQLSchema.doClean()
// https://code.yawk.at/org.flywaydb/flyway-core/6.4.0/org/flywaydb/core/internal/database/postgresql/PostgreSQLSchema.java
private[dumbo] object CatalogQueries {

  val listMaterializedViewsQuery: Query[String, String] =
    sql"""SELECT c.relname
          FROM pg_catalog.pg_class c
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
          WHERE c.relkind = 'm' AND n.nspname = ${name}"""
      .query(name)

  val listViewsQuery: Query[String, String] =
    sql"""SELECT c.relname
          FROM pg_catalog.pg_class c
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
          WHERE c.relkind = 'v' AND n.nspname = ${name}
            AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend d
              WHERE d.objid = c.oid AND d.deptype = 'e'
            )"""
      .query(name)

  val listTablesQuery: Query[String, String] =
    sql"""SELECT c.relname
          FROM pg_catalog.pg_class c
          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
          WHERE c.relkind = 'r' AND n.nspname = ${name}
            AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend d
              WHERE d.objid = c.oid AND d.deptype = 'e'
            )"""
      .query(name)

  val listBaseTypesQuery: Query[String, String] =
    sql"""SELECT t.typname
          FROM pg_catalog.pg_type t
          JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
          LEFT JOIN pg_catalog.pg_class c ON c.oid = t.typrelid
          WHERE n.nspname = ${name}
            AND t.typtype = 'b'
            AND (c.relkind IS NULL OR c.relkind = 'c')
            AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend d
              WHERE d.objid = t.oid AND d.deptype = 'e'
            )"""
      .query(name)

  // Returns (kind, signature) e.g. ("FUNCTION", "my_func(integer, text)")
  val listRoutinesQuery: Query[String, (String, String)] =
    sql"""SELECT
            CASE p.prokind
              WHEN 'a' THEN 'AGGREGATE'
              WHEN 'p' THEN 'PROCEDURE'
              ELSE 'FUNCTION'
            END,
            p.proname || '(' || pg_catalog.pg_get_function_identity_arguments(p.oid) || ')'
          FROM pg_catalog.pg_proc p
          JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
          WHERE n.nspname = ${name}
            AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend d
              WHERE d.objid = p.oid AND d.deptype = 'e'
            )"""
      .query(text ~ text)

  val listEnumsQuery: Query[String, String] =
    sql"""SELECT t.typname
          FROM pg_catalog.pg_type t
          JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
          WHERE n.nspname = ${name} AND t.typtype = 'e'
            AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend d
              WHERE d.objid = t.oid AND d.deptype = 'e'
            )"""
      .query(name)

  val listDomainsQuery: Query[String, String] =
    sql"""SELECT t.typname
          FROM pg_catalog.pg_type t
          JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
          WHERE n.nspname = ${name} AND t.typtype = 'd'
            AND NOT EXISTS (
              SELECT 1 FROM pg_catalog.pg_depend d
              WHERE d.objid = t.oid AND d.deptype = 'e'
            )"""
      .query(name)

  val listSequencesQuery: Query[String, String] =
    sql"""SELECT sequence_name::text
          FROM information_schema.sequences
          WHERE sequence_schema = ${text}"""
      .query(text)

}
