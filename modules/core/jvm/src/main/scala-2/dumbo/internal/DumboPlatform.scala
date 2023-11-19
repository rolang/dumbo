// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import cats.effect.Sync
import dumbo.internal.FsPlatform
import dumbo.{DumboWithResourcesPartiallyApplied, SourceFilePath}

private[dumbo] trait DumboPlatform {
  def withResourcesIn[F[_]: Sync](location: String): DumboWithResourcesPartiallyApplied[F] =
    new DumboWithResourcesPartiallyApplied[F](FsPlatform.embeddedResources(SourceFilePath.fromResourcesDir(location)))
}
