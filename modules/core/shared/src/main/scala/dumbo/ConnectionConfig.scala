// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo

final case class ConnectionConfig(
  host: String,
  port: Int = Dumbo.defaults.port,
  user: String,
  database: String,
  password: Option[String] = None,
  ssl: skunk.SSL = skunk.SSL.None,
)
