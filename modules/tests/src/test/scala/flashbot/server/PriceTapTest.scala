package flashbot.server
import java.time.Instant

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import flashbot.models.{Candle, TimeRange}
import org.scalatest.{FlatSpec, Matchers}
import flashbot.core.PriceTap
import flashbot.util.time._
import flashbot.util.timeseries
import flashbot.util.timeseries.Scannable.BaseFoldable
import flashbot.util.timeseries.TimeSeriesLike
import org.ta4j.core.Bar

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

class PriceTapTest extends FlatSpec with Matchers {
  "PriceTap" should "emit times" in {
    implicit val system = ActorSystem("test")
    implicit val mat = ActorMaterializer()
    val timeRange = TimeRange.build(Instant.now(), 2 days)
    val timeStep =  5 minutes
    val src: Source[Instant, NotUsed] =
      PriceTap(timeRange, timeStep)
    var lastInstant: Option[Instant] = None
    var count = 0
    val done: Future[Done] = src.zipWithIndex.runForeach(x => {
      if (lastInstant.isDefined) {
        (x._1.micros - lastInstant.get.micros) shouldEqual timeStep.toMicros
      }
      lastInstant = Some(x._1)
      count += 1
    })
    println(Await.result(done, 30 seconds))
    count shouldEqual 2 * 24 * 12
  }

  "PriceTap" should "emit prices" in {
    val timerange = TimeRange.build(Instant.now(), 30 days)
    val timeStep = 5 minutes
    val it = PriceTap.iterator(200, .55, .35, timerange, timeStep)
    var count = 0
    it.zipWithIndex.foreach(x => {
      count += 1
    })
    count shouldEqual 30 * 24 * 12
  }

  "PriceTap" should "aggregate prices into candles" in {
    val timerange = TimeRange.build(Instant.now(), 30 days)
    println(s"Time Range: ${timerange.startInstant} to ${timerange.endInstant}")

    val timeStep = (5 minutes).javaDuration
    val srcSeq: Seq[(Instant, Double)] = PriceTap.iterator(200, .55, .35, timerange, timeStep).toVector

    srcSeq.length shouldEqual 30 * 24 * 12
    srcSeq.head._1.micros shouldEqual timeStep.normalizeTimeStepMicros(timerange.start)
    srcSeq.last._1.micros shouldEqual timeStep.normalizeTimeStepMicros(timerange.end) - timeStep.toMicros

    println(s"1. Start: ${srcSeq.head._1}\tEnd: ${srcSeq.last._1}")

    // Scan prices into 30 minute candles
    val scanned = timeseries.scan[(Instant, Double), Candle](srcSeq, 30 minutes, false, false).toVector
    println(s"2. Start: ${scanned.head.instant}\tEnd: ${scanned.last.instant}")

    // Scan those candles into 30 minute TA4J TimeSeries bars
    val bars = timeseries.scan[Candle, Bar](scanned, 30 minutes, false, false).toVector
    println(s"3. Start: ${bars.head.getBeginTime.toInstant}\tEnd: ${bars.last.getBeginTime.toInstant}")

    // Scan them back into 30 minute candles. Ensure that they are identical to the originals.
    val reverted = timeseries.scan[Bar, Candle](bars, 30 minutes, false, false).toVector
    println(s"4. Start: ${reverted.head.instant}\tEnd: ${reverted.last.instant}")

    val backToBars = timeseries.scan[Candle, Bar](reverted, 30 minutes, false, false).toVector
    println(s"5. Start: ${backToBars.head.getBeginTime.toInstant}\tEnd: ${backToBars.last.getBeginTime.toInstant}")

    var A = timeseries.scan[Bar, Candle](bars, 30 minutes).toVector
    var B = timeseries.scan[Bar, Candle](backToBars, 30 minutes).toVector

    A shouldEqual B

    A = timeseries.scan[Candle, Candle](A, 2 hours).toVector
    B = timeseries.scan[Candle, Candle](B, 2 hours).toVector

    A shouldEqual B

  }
}
