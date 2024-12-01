// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.internal

import cats.effect.Sync
import dumbo.{DumboWithResourcesPartiallyApplied, ResourceFilePath}

private[dumbo] trait DumboPlatform {
  def withResourcesIn[F[_]: Sync](location: String): DumboWithResourcesPartiallyApplied[F] = {
    val (locationInfo, resources) = ResourceFilePath.fromResourcesDir(location)

    new DumboWithResourcesPartiallyApplied[F](
      ResourceReader.embeddedResources(
        readResources = resources,
        locationInfo = Some(locationInfo),
        locationRelative = Some(location),
      )
    )
  }
}
