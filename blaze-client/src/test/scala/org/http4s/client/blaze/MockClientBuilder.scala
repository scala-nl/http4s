package org.http4s
package client
package blaze

import java.nio.ByteBuffer

import cats.effect.IO
import org.http4s.blaze.pipeline.{HeadStage, LeafBuilder}

private object MockClientBuilder {
  def builder(
      head: => HeadStage[ByteBuffer],
      tail: => BlazeConnection[IO]): ConnectionBuilder[IO, BlazeConnection[IO]] = { req =>
    IO {
      val t = tail
      LeafBuilder(t).base(head)
      t
    }
  }

  def manager(
      head: => HeadStage[ByteBuffer],
      tail: => BlazeConnection[IO]): ConnectionManager[IO, BlazeConnection[IO]] =
    ConnectionManager.basic(builder(head, tail))
}
