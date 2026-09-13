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
  ssl: ConnectionConfig.SSL = ConnectionConfig.SSL.None,
)

object ConnectionConfig:
  enum SSL:
    case None    // `SSL` which indicates that SSL is not to be used
    case Trusted // `SSL` which trusts all certificates
    case System  // `SSL` from the system default `SSLContext`
