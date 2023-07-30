// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo
import java.time.LocalDateTime

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

final case class HistoryEntry(
  installedRank: Int,
  version: Option[String],
  description: String,
  `type`: String,
  script: String,
  checksum: Option[Int],
  installedBy: String,
  installedOn: LocalDateTime,
  executionTimeMs: Int,
  success: Boolean,
)

object HistoryEntry {
  final case class New(
    installedRank: Int,
    version: String,
    description: String,
    `type`: String,
    script: String,
    checksum: Option[Int],
    executionTimeMs: Int,
    success: Boolean,
  )

  object New {
    val codec: Codec[New] =
      (int4 *: varchar(50) *: varchar(200) *: varchar(20) *: varchar(1000) *: int4.opt *: int4 *: bool)
        .to[New]
  }

  val codec: Codec[HistoryEntry] =
    (int4 *: varchar(50).opt *: varchar(200) *: varchar(20) *: varchar(1000) *: int4.opt *: varchar(
      100
    ) *: timestamp *: int4 *: bool)
      .to[HistoryEntry]

  val fieldNames =
    "installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success"
}

class History(tableName: String) {
  val createTableCommand: Command[Void] = sql"""
    CREATE TABLE IF NOT EXISTS #${tableName} (
      installed_rank  INT NOT NULL PRIMARY KEY,
      version         VARCHAR(50) NULL,
      description     VARCHAR(200) NOT NULL,
      type            VARCHAR(20) NOT NULL,
      script          VARCHAR(1000) NOT NULL,
      checksum        INT NULL,
      installed_by    VARCHAR(100) NOT NULL,
      installed_on    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      execution_time  INT NOT NULL,
      success         BOOL NOT NULL
    )""".command

  val loadAllQuery: Query[Void, HistoryEntry] = sql"""
    SELECT #${HistoryEntry.fieldNames}
    FROM #${tableName} ORDER BY installed_rank ASC""".query(HistoryEntry.codec)

  val insertSQLEntry: Query[HistoryEntry.New, HistoryEntry] = sql"""
    INSERT INTO #${tableName}
    (installed_rank, version, description, type, script, checksum, execution_time, success, installed_on, installed_by)
    VALUES (${HistoryEntry.New.codec}, CURRENT_TIMESTAMP, CURRENT_USER)
    RETURNING #${HistoryEntry.fieldNames}"""
    .query(HistoryEntry.codec)

  val insertSchemaEntry: Command[String] = sql"""
    INSERT INTO #${tableName}
    (installed_rank, description, type, script, execution_time, success, installed_on, installed_by)
    VALUES 
    (0, '<< Flyway Schema Creation >>', 'SCHEMA', ${varchar(1000)}, 0, true, CURRENT_TIMESTAMP, CURRENT_USER)
    ON CONFLICT DO NOTHING""".command
}

object History {
  def apply(tableName: String) = new History(tableName)
}
