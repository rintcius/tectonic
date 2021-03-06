/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tectonic

import scala.{sys, Array, Byte, Int, Long, StringContext, Unit}
import scala.annotation.switch
// import scala.Predef, Predef._

import java.lang.{AssertionError, CharSequence, SuppressWarnings}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.FinalVal",
    "org.wartremover.warts.PublicInference",
    "org.wartremover.warts.Var"))
final class EventCursor private (
    tagBuffer: Array[Long],
    tagLimit: Int,
    tagSubShiftLimit: Int,
    strsBuffer: Array[CharSequence],
    strsLimit: Int,
    intsBuffer: Array[Int],
    intsLimit: Int) {

  private[this] final var tagCursor: Int = 0
  private[this] final var tagSubShiftCursor: Int = 0
  private[this] final var strsCursor: Int = 0
  private[this] final var intsCursor: Int = 0

  private[this] final var tagCursorMark: Int = 0
  private[this] final var tagSubShiftCursorMark: Int = 0
  private[this] final var strsCursorMark: Int = 0
  private[this] final var intsCursorMark: Int = 0

  @SuppressWarnings(Array("org.wartremover.warts.Equals", "org.wartremover.warts.While"))
  def drive(plate: Plate[_]): Unit = {
    if (tagLimit > 0 || tagSubShiftLimit > 0) {
      var b: Byte = 0
      while (b == 0) {
        b = nextRow(plate)

        if (b != 1) {
          plate.finishRow()
        }
      }
    }
  }

  /**
   * Returns:
   *
   * - `0` if a row has been completed but there is still more data
   * - `1` if the data stream has terminated without ending the row
   * - `2` if the data stream has terminated *and* there is no more data
   */
  // TODO skips
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Equals",
      "org.wartremover.warts.While",
      "org.wartremover.warts.Throw"))
  def nextRow(plate: Plate[_]): Byte = {
    var continue = true
    var hasNext = !(tagCursor == tagLimit && tagSubShiftCursor == tagSubShiftLimit)
    while (continue && hasNext) {
      // we can't use @switch if we use vals here :-(
      val _ = (nextTag(): @switch) match {
        case 0x0 => plate.nul()
        case 0x1 => plate.fls()
        case 0x2 => plate.tru()
        case 0x3 => plate.map()
        case 0x4 => plate.arr()
        case 0x5 => plate.num(nextStr(), nextInt(), nextInt())
        case 0x6 => plate.str(nextStr())
        case 0x7 => plate.nestMap(nextStr())
        case 0x8 => plate.nestArr()
        case 0x9 => plate.nestMeta(nextStr())
        case 0xA => plate.unnest()
        case 0xB => continue = false
        case 0xC => plate.skipped(nextInt())
        case tag => sys.error(s"assertion failed: unrecognized tag = $tag")
      }

      hasNext = !(tagCursor == tagLimit && tagSubShiftCursor == tagSubShiftLimit)
    }

    if (!continue && hasNext)
      0
    else if (continue && !hasNext)
      1
    else if (!continue && !hasNext)
      2
    else
      throw new AssertionError
  }

  def length: Int = tagLimit * (64 / 4) + (tagSubShiftLimit / 4)

  /**
   * Marks the current location for subsequent rewinding. Overwrites any previous
   * mark.
   */
  def mark(): Unit = {
    tagCursorMark = tagCursor
    tagSubShiftCursorMark = tagSubShiftCursor
    strsCursorMark = strsCursor
    intsCursorMark = intsCursor
  }

  /**
   * Rewinds the cursor location to the last mark. If no mark has been set,
   * it resets to the beginning of the stream.
   */
  def rewind(): Unit = {
    tagCursor = tagCursorMark
    tagSubShiftCursor = tagSubShiftCursorMark
    strsCursor = strsCursorMark
    intsCursor = intsCursorMark
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  private[this] final def nextTag(): Int = {
    val back = ((tagBuffer(tagCursor) >>> tagSubShiftCursor) & 0xF).toInt

    if (tagSubShiftCursor == 60) {
      tagCursor += 1
      tagSubShiftCursor = 0
    } else {
      tagSubShiftCursor += 4
    }

    back
  }

  private[this] final def nextStr(): CharSequence = {
    val back = strsBuffer(strsCursor)
    strsCursor += 1
    back
  }

  private[this] final def nextInt(): Int = {
    val back = intsBuffer(intsCursor)
    intsCursor += 1
    back
  }

  def reset(): Unit = {
    tagCursor = 0
    tagSubShiftCursor = 0
    strsCursor = 0
    intsCursor = 0
  }

  def copy(): EventCursor = {
    new EventCursor(
      tagBuffer,
      tagLimit,
      tagSubShiftLimit,
      strsBuffer,
      strsLimit,
      intsBuffer,
      intsLimit)
  }

  // this will handle disk cleanup
  def finish(): Unit = ()
}

object EventCursor {
  private[tectonic] val Nul = 0x0
  private[tectonic] val Fls = 0x1
  private[tectonic] val Tru = 0x2
  private[tectonic] val Map = 0x3
  private[tectonic] val Arr = 0x4
  private[tectonic] val Num = 0x5
  private[tectonic] val Str = 0x6
  private[tectonic] val NestMap = 0x7
  private[tectonic] val NestArr = 0x8
  private[tectonic] val NestMeta = 0x9
  private[tectonic] val Unnest = 0xA
  private[tectonic] val FinishRow = 0xB
  private[tectonic] val Skipped = 0xC

  private[tectonic] def apply(
      tagBuffer: Array[Long],
      tagLimit: Int,
      tagSubShiftLimit: Int,
      strsBuffer: Array[CharSequence],
      strsLimit: Int,
      intsBuffer: Array[Int],
      intsLimit: Int)
      : EventCursor =
    new EventCursor(
      tagBuffer,
      tagLimit,
      tagSubShiftLimit,
      strsBuffer,
      strsLimit,
      intsBuffer,
      intsLimit)
}
