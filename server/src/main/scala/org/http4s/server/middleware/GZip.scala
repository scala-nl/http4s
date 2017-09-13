package org.http4s
package server
package middleware

import java.util.zip.{CRC32, Deflater}
import javax.xml.bind.DatatypeConverter

import cats._
import fs2.Stream._
import fs2._
import fs2.compress._
import org.http4s.headers._
import org.log4s.getLogger

object GZip {
  private[this] val logger = getLogger

  // TODO: It could be possible to look for F.pure type bodies, and change the Content-Length header after
  // TODO      zipping and buffering all the input. Just a thought.
  def apply[F[_]: Functor](
      service: HttpService[F],
      bufferSize: Int = 32 * 1024,
      level: Int = Deflater.DEFAULT_COMPRESSION): HttpService[F] =
    Service.lift { req: Request[F] =>
      req.headers.get(`Accept-Encoding`) match {
        case Some(acceptEncoding) if satisfiedByGzip(acceptEncoding) =>
          service.map(zipOrPass(_, bufferSize, level)).apply(req)
        case _ => service(req)
      }
    }

  private def satisfiedByGzip(acceptEncoding: `Accept-Encoding`) =
    acceptEncoding.satisfiedBy(ContentCoding.gzip) || acceptEncoding.satisfiedBy(
      ContentCoding.`x-gzip`)

  private def zipOrPass[F[_]: Functor](
      response: MaybeResponse[F],
      bufferSize: Int,
      level: Int): MaybeResponse[F] =
    response match {
      case resp: Response[F] if isZippable(resp) => zipResponse(bufferSize, level, resp)
      case resp: Response[F] => resp // Don't touch it, Content-Encoding already set
      case Pass() => Pass()
    }

  private def isZippable[F[_]](resp: Response[F]): Boolean = {
    val contentType = resp.headers.get(`Content-Type`)
    resp.headers.get(`Content-Encoding`).isEmpty &&
    (contentType.isEmpty || contentType.get.mediaType.compressible ||
    (contentType.get.mediaType eq MediaType.`application/octet-stream`))
  }

  private def zipResponse[F[_]: Functor](
      bufferSize: Int,
      level: Int,
      resp: Response[F]): Response[F] = {
    logger.trace("GZip middleware encoding content")
    // Need to add the Gzip header and trailer
    val trailerGen = new TrailerGen()
    val b = chunk(header) ++
      resp.body
        .through(trailer(trailerGen))
        .through(
          deflate(
            level = level,
            nowrap = true,
            bufferSize = bufferSize
          )) ++
      chunk(trailerFinish(trailerGen))
    resp
      .removeHeader(`Content-Length`)
      .putHeaders(`Content-Encoding`(ContentCoding.gzip))
      .copy(body = b)
  }

  private val GZIP_MAGIC_NUMBER = 0x8b1f
  private val GZIP_LENGTH_MOD = Math.pow(2, 32).toLong

  private val header: Chunk[Byte] = Chunk.bytes(
    Array(
      GZIP_MAGIC_NUMBER.toByte, // Magic number (int16)
      (GZIP_MAGIC_NUMBER >> 8).toByte, // Magic number  c
      Deflater.DEFLATED.toByte, // Compression method
      0.toByte, // Flags
      0.toByte, // Modification time (int32)
      0.toByte, // Modification time  c
      0.toByte, // Modification time  c
      0.toByte, // Modification time  c
      0.toByte, // Extra flags
      0.toByte
    ) // Operating system
  )

  private final class TrailerGen(val crc: CRC32 = new CRC32(), var inputLength: Int = 0)

  private def trailer[F[_]](gen: TrailerGen): Pipe[Pure, Byte, Byte] =
    _.pull.uncons.flatMap(trailerStep(gen)).stream

  private def trailerStep(
      gen: TrailerGen): (Option[(Segment[Byte, Unit], Stream[Pure, Byte])]) => Pull[
    Pure,
    Byte,
    Option[Stream[Pure, Byte]]] = {
    case None => Pull.pure(None)
    case Some((segment, stream)) =>
      val chunkArray = segment.toChunk.toArray
      gen.crc.update(chunkArray)
      gen.inputLength = gen.inputLength + chunkArray.length
      Pull.output(segment) >> stream.pull.uncons.flatMap(trailerStep(gen))
  }

  private def trailerFinish(gen: TrailerGen): Chunk[Byte] =
    Chunk.bytes(
      DatatypeConverter.parseHexBinary("%08x".format(gen.crc.getValue)).reverse ++
        DatatypeConverter.parseHexBinary("%08x".format(gen.inputLength % GZIP_LENGTH_MOD)).reverse)
}
