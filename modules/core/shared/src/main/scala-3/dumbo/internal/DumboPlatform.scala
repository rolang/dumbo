// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import cats.effect.Sync
import dumbo.SourceFilePath
import dumbo.DumboWithResourcesPartiallyApplied

private[dumbo] trait DumboPlatform {
  inline def withResourcesIn[F[_]: Sync](location: String): DumboWithResourcesPartiallyApplied[F] = {
    val resources = SourceFilePath.fromResourcesDir(location)
    new DumboWithResourcesPartiallyApplied[F](FsPlatform.embeddedResources(Sync[F].pure(resources)))
  }
}
