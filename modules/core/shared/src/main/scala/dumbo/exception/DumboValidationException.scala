// Copyright (c) 2023 by Roman Langolf
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dumbo.exception

class DumboValidationException private[dumbo] (message: String) extends Throwable(message)
