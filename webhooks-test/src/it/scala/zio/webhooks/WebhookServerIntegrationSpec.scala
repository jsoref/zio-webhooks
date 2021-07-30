package zio.webhooks

import zhttp.http._
import zhttp.service.Server
import zio._
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration._
import zio.json._
import zio.magic._
import zio.random.Random
import zio.stream._
import zio.test._
import zio.webhooks.WebhookServerIntegrationSpecUtil._
import zio.webhooks.backends.sttp.WebhookSttpClient
import zio.webhooks.testkit._

object WebhookServerIntegrationSpec extends DefaultRunnableSpec {
  val spec =
    suite("WebhookServerIntegrationSpec") {
      testM("all events are delivered eventually") {
        val n = 10000L // number of events

        {
          for {
            _                <- ZIO.foreach_(testWebhooks)(TestWebhookRepo.setWebhook)
            delivered        <- SubscriptionRef.make(Set.empty[Int])
            server           <- WebhookServer.start
            reliableEndpoint <- httpEndpointServer.start(port, reliableEndpoint(delivered)).fork
            // create events for webhooks with single delivery, at-most-once semantics
            _                <- events(webhookIdRange = (0, 250))
                                  .take(n / 4)
                                  // pace events so we don't overwhelm the endpoint
                                  .schedule(Schedule.spaced(20.micros))
                                  .foreach(TestWebhookEventRepo.createEvent)
            // create events for webhooks with batched delivery, at-most-once semantics
            // no need to pace events as batching minimizes requests sent
            _                <- events(webhookIdRange = (250, 500))
                                  .drop(n / 4)
                                  .take(n / 4)
                                  .foreach(TestWebhookEventRepo.createEvent)
            // wait to get half
            _                <- delivered.changes.filter(_.size == n / 2).runHead
            _                <- reliableEndpoint.interrupt
            _                <- server.shutdown
            // start restarting server and endpoint with random behavior
            eventQueue       <- Queue.bounded[WebhookEvent](1)
            _                <- RestartingWebhookServer.start(eventQueue).fork
            _                <- RandomEndpointBehavior.run(delivered).fork
            // send events for webhooks with at-least-once semantics
            _                <- events(webhookIdRange = (500, 750))
                                  .drop(n / 2)
                                  .take(n / 4)
                                  .schedule(Schedule.spaced(20.micros))
                                  .run(ZSink.fromQueue(eventQueue))
            _                <- events(webhookIdRange = (750, 1000))
                                  .drop(3 * n / 4)
                                  .take(n / 4)
                                  .run(ZSink.fromQueue(eventQueue))
            // wait to get second half
            _                <- delivered.changes.filter(_.size == n).runHead
          } yield assertCompletes
        }.provideSomeLayer[IntegrationEnv](Clock.live ++ console.Console.live ++ random.Random.live)
      }
    }.injectCustom(integrationEnv)
}

object WebhookServerIntegrationSpecUtil {

  // backport for 2.12
  implicit class EitherOps[A, B](either: Either[A, B]) {

    def orElseThat[A1 >: A, B1 >: B](or: => Either[A1, B1]): Either[A1, B1] =
      either match {
        case Right(_) => either
        case _        => or
      }
  }

  def events(webhookIdRange: (Int, Int)): ZStream[Random, Nothing, WebhookEvent] =
    UStream
      .iterate(0L)(_ + 1)
      .zip(UStream.repeatEffect(random.nextIntBetween(webhookIdRange._1, webhookIdRange._2)))
      .map {
        case (i, webhookId) =>
          WebhookEvent(
            WebhookEventKey(WebhookEventId(i), WebhookId(webhookId.toLong)),
            WebhookEventStatus.New,
            i.toString, // a single number string is valid JSON
            Chunk(("Accept", "*/*"), ("Content-Type", "application/json"))
          )
      }

  type IntegrationEnv = Has[WebhookEventRepo]
    with Has[TestWebhookEventRepo]
    with Has[WebhookRepo]
    with Has[TestWebhookRepo]
    with Has[WebhookStateRepo]
    with Has[WebhookHttpClient]
    with Has[WebhooksProxy]
    with Has[WebhookServerConfig]

  // alias for zio-http endpoint server
  lazy val httpEndpointServer = Server

  lazy val integrationEnv: URLayer[Clock, IntegrationEnv] =
    ZLayer
      .wireSome[Clock, IntegrationEnv](
        TestWebhookEventRepo.test,
        TestWebhookRepo.subscriptionUpdateMode,
        TestWebhookRepo.test,
        TestWebhookStateRepo.test,
        WebhookServerConfig.default,
        WebhookSttpClient.live,
        WebhooksProxy.live
      )
      .orDie

  lazy val port = 8081

  def reliableEndpoint(delivered: SubscriptionRef[Set[Int]]) =
    HttpApp.collectM {
      case request @ Method.POST -> Root / "endpoint" / (id @ _) =>
        val response =
          for {
            randomDelay <- random.nextIntBounded(200).map(_.millis)
            response    <- ZIO
                             .foreach_(request.getBodyAsString) { body =>
                               val singlePayload = body.fromJson[Int].map(Left(_))
                               val batchPayload  = body.fromJson[List[Int]].map(Right(_))
                               val payload       = singlePayload.orElseThat(batchPayload).toOption
                               ZIO.foreach_(payload) {
                                 case Left(i)   =>
                                   delivered.ref.update(set => UIO(set + i))
                                 case Right(is) =>
                                   delivered.ref.update(set => UIO(set ++ is))
                               }
                             }
                             .as(Response.status(Status.OK))
                             .delay(randomDelay) // simulate network/server latency
          } yield response
        response.uninterruptible
    }

  lazy val testWebhooks: IndexedSeq[Webhook] = (0 until 250).map { i =>
    Webhook(
      id = WebhookId(i.toLong),
      url = s"http://0.0.0.0:$port/endpoint/$i",
      label = s"test webhook $i",
      WebhookStatus.Enabled,
      WebhookDeliveryMode.SingleAtMostOnce
    )
  } ++ (250 until 500).map { i =>
    Webhook(
      id = WebhookId(i.toLong),
      url = s"http://0.0.0.0:$port/endpoint/$i",
      label = s"test webhook $i",
      WebhookStatus.Enabled,
      WebhookDeliveryMode.BatchedAtMostOnce
    )
  } ++ (500 until 750).map { i =>
    Webhook(
      id = WebhookId(i.toLong),
      url = s"http://0.0.0.0:$port/endpoint/$i",
      label = s"test webhook $i",
      WebhookStatus.Enabled,
      WebhookDeliveryMode.SingleAtLeastOnce
    )
  } ++ (750 until 1000).map { i =>
    Webhook(
      id = WebhookId(i.toLong),
      url = s"http://0.0.0.0:$port/endpoint/$i",
      label = s"test webhook $i",
      WebhookStatus.Enabled,
      WebhookDeliveryMode.BatchedAtLeastOnce
    )
  }

  lazy val webhookCount = 1000
}

sealed trait RandomEndpointBehavior extends Product with Serializable { self =>
  import RandomEndpointBehavior._

  def start(delivered: SubscriptionRef[Set[Int]]) =
    self match {
      case RandomEndpointBehavior.Down  =>
        ZIO.unit
      case RandomEndpointBehavior.Flaky =>
        httpEndpointServer.start(port, flakyBehavior(delivered))
    }
}

object RandomEndpointBehavior {
  case object Down  extends RandomEndpointBehavior
  case object Flaky extends RandomEndpointBehavior

  def flakyBehavior(delivered: SubscriptionRef[Set[Int]]) =
    HttpApp.collectM {
      case request @ Method.POST -> Root / "endpoint" / (id @ _) =>
        for {
          n           <- random.nextIntBounded(100)
          randomDelay <- random.nextIntBounded(200).map(_.millis)
          response    <- ZIO
                           .foreach(request.getBodyAsString) { body =>
                             val singlePayload = body.fromJson[Int].map(Left(_))
                             val batchPayload  = body.fromJson[List[Int]].map(Right(_))
                             val payload       = singlePayload.orElseThat(batchPayload).toOption
                             if (n < 60)
                               ZIO
                                 .foreach_(payload) {
                                   case Left(i)   =>
                                     delivered.ref.update(set => UIO(set + i))
                                   case Right(is) =>
                                     delivered.ref.update(set => UIO(set ++ is))
                                 }
                                 .as(Response.status(Status.OK))
                             else
                               UIO(Response.status(Status.NOT_FOUND))
                           }
                           .delay(randomDelay)
        } yield response.getOrElse(Response.fromHttpError(HttpError.BadRequest("empty body")))
    }

  // just an alias for a zio-http server to tell it apart from the webhook server
  lazy val httpEndpointServer: Server.type = Server

  val normalBehavior = HttpApp.collectM {
    case request @ Method.POST -> Root / "endpoint" / id =>
      val response =
        for {
          randomDelay <- random.nextIntBounded(200).map(_.millis)
          response    <- ZIO
                           .foreach(request.getBodyAsString) { str =>
                             putStrLn(s"""SERVER RECEIVED PAYLOAD: webhook: $id $str OK""")
                           }
                           .as(Response.status(Status.OK))
                           .orDie
                           .delay(randomDelay)
        } yield response
      response.uninterruptible
  }

  val randomBehavior: URIO[Random, RandomEndpointBehavior] =
    random.nextBoolean.map {
      case true  => Flaky
      case false => Down
    }

  def run(delivered: SubscriptionRef[Set[Int]]) =
    UStream.repeatEffect(randomBehavior).foreach { behavior =>
      for {
        _ <- putStrLn(s"Endpoint server behavior: $behavior")
        f <- behavior.start(delivered).fork
        _ <- f.interrupt.delay(3.seconds)
      } yield ()
    }
}

sealed trait RestartingWebhookServer extends Product with Serializable { self =>
  import RestartingWebhookServer.startServer

  def run(ref: Ref[Fiber.Runtime[Nothing, WebhookServer]], eventQueue: Queue[WebhookEvent]) =
    self match {
      case RestartingWebhookServer.Restart =>
        for {
          fiber  <- ref.get
          server <- fiber.join
          _      <- server.shutdown
          fiber  <- startServer(eventQueue).fork
          _      <- ref.set(fiber)
        } yield ()
      case RestartingWebhookServer.Failure =>
        for {
          fiber <- ref.get
          _     <- fiber.interrupt // just outright kill the server
          fiber <- startServer(eventQueue).fork
          _     <- ref.set(fiber)
        } yield ()
    }
}

object RestartingWebhookServer {
  case object Failure extends RestartingWebhookServer

  case object Restart extends RestartingWebhookServer

  import zio.console.{ putStrLn, putStrLnErr }

  private lazy val randomRestart: URIO[Random, Option[RestartingWebhookServer]] =
    random.nextIntBounded(3).map {
      case 0 => Some(Restart)
      case 1 => Some(Failure)
      case _ => None
    }

  def start(eventQueue: Queue[WebhookEvent]) =
    for {
      _     <- putStrLn(s"Starting webhook server")
      fiber <- startServer(eventQueue).fork
      ref   <- Ref.make(fiber)
      _     <- UStream.repeatEffect(randomRestart).schedule(Schedule.spaced(10.seconds)).foreach { behavior =>
                 putStrLn(s"Webhook server restart: $behavior") *>
                   ZIO.foreach_(behavior)(_.run(ref, eventQueue))
               }
    } yield ()

  private def startServer(eventQueue: Queue[WebhookEvent]) =
    for {
      server <- WebhookServer.start
      _      <- server.subscribeToErrors
                  .use(UStream.fromQueue(_).map(_.toString).foreach(putStrLnErr(_)))
                  .fork
      _      <- ZIO.foreach_(testWebhooks.drop(webhookCount / 2))(TestWebhookRepo.setWebhook)
      _      <- UStream.fromQueue(eventQueue).foreach(TestWebhookEventRepo.createEvent)
    } yield server
}
