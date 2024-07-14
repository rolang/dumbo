// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package object dumbo {
  type Matchable = Any

  type ResourceFileVersioned  = (ResourceVersion.Versioned, ResourceFile)
  type ResourceFileRepeatable = (ResourceVersion.Repeatable, ResourceFile)
}
