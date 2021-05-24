package zio.webhooks

import zio._
import zio.clock.Clock
import zio.duration._
import zio.test.Assertion._
import zio.test.DefaultRunnableSpec
import zio.test.TestAspect._
import zio.test._
import zio.test.environment.Live
import zio.webhooks.WebhookServerSpecUtil._
import zio.webhooks.testkit._

import java.time.Instant

object WebhookServerSpec extends DefaultRunnableSpec {
  def spec =
    suite("WebhookServerSpec")(
      testM("dispatches correct request given event") {
        val webhook = singleWebhook(0, WebhookStatus.Enabled, WebhookDeliveryMode.SingleAtMostOnce)

        val event = WebhookEvent(
          WebhookEventKey(WebhookEventId(0), webhook.id),
          WebhookEventStatus.New,
          "event payload",
          Chunk(("Accept", "*/*"))
        )

        val expectedRequest = WebhookHttpRequest(webhook.url, event.content, event.headers)

        assertRequestsMade(
          stubResponses = List(WebhookHttpResponse(200)),
          webhooks = List(webhook),
          events = List(event),
          requestsAssertion = queue => assertM(queue.take)(equalTo(expectedRequest))
        )
      },
      testM("can dispatch single event to n webhooks") {
        val n                 = 100
        val webhooks          = createWebhooks(n)(WebhookStatus.Enabled, WebhookDeliveryMode.SingleAtMostOnce)
        val eventsToNWebhooks = webhooks.map(_.id).flatMap(createWebhookEvents(1))

        assertRequestsMade(
          stubResponses = List.fill(n)(WebhookHttpResponse(200)),
          webhooks = webhooks,
          events = eventsToNWebhooks,
          requestsAssertion = queue => assertM(queue.takeN(n))(hasSize(equalTo(n)))
        )
      },
      testM("dispatches no events for disabled webhooks") {
        val n       = 100
        val webhook = singleWebhook(0, WebhookStatus.Disabled, WebhookDeliveryMode.SingleAtMostOnce)

        assertRequestsMade(
          stubResponses = List.fill(n)(WebhookHttpResponse(200)),
          webhooks = List(webhook),
          events = createWebhookEvents(n)(webhook.id),
          requestsAssertion = queue => assertM(queue.takeAll.map(_.size))(equalTo(0)),
          sleepDuration = Some(100.millis)
        )
      },
      testM("dispatches no events for unavailable webhooks") {
        val n       = 100
        val webhook = singleWebhook(0, WebhookStatus.Unavailable(Instant.EPOCH), WebhookDeliveryMode.SingleAtMostOnce)

        assertRequestsMade(
          stubResponses = List.fill(n)(WebhookHttpResponse(200)),
          webhooks = List(webhook),
          events = createWebhookEvents(n)(webhook.id),
          requestsAssertion = queue => assertM(queue.takeAll.map(_.size))(equalTo(0)),
          sleepDuration = Some(100.millis)
        )
      },
      testM("supports max batch sizes for at-most-once event delivery") {
        val n                    = 100
        val maxBatchSize         = 10
        val maxWaitDuration      = 30.seconds
        val webhook              = singleWebhook(
          id = 0,
          WebhookStatus.Enabled,
          WebhookDeliveryMode.batchedAtMostOnce(maxBatchSize, maxWaitDuration)
        )
        val expectedRequestsMade = n / maxBatchSize

        assertRequestsMade(
          stubResponses = List.fill(n)(WebhookHttpResponse(200)),
          webhooks = List(webhook),
          events = createWebhookEvents(n)(webhook.id),
          requestsAssertion =
            queue => assertM(queue.takeBetween(expectedRequestsMade, n).map(_.size))(equalTo(expectedRequestsMade)),
          sleepDuration = Some(10.millis)
        )
      }
      // TODO: test that successfully dispatched events are marked delivered
      // TODO: test that `WebhookError`s in the subscription crash the server?
      // TODO: what to do with non-existent webhook or webhook events?
      // TODO: test that after 7 days have passed since webhook event delivery failure, a webhook is set unavailable
    ).provideSomeLayer[Has[Live.Service] with Has[Annotations.Service]](testEnv) @@ timeout(5.seconds)
}

object WebhookServerSpecUtil {

  def assertRequestsMade(
    stubResponses: Iterable[WebhookHttpResponse],
    webhooks: Iterable[Webhook],
    events: Iterable[WebhookEvent],
    requestsAssertion: Queue[WebhookHttpRequest] => UIO[TestResult],
    sleepDuration: Option[Duration] = None
  ): URIO[TestEnv, TestResult] =
    for {
      responseQueue <- Queue.unbounded[WebhookHttpResponse]
      _             <- responseQueue.offerAll(stubResponses)
      _             <- TestWebhookHttpClient.setResponse(_ => Some(responseQueue))
      _             <- ZIO.foreach_(webhooks)(TestWebhookRepo.createWebhook(_))
      _             <- ZIO.foreach_(events)(TestWebhookEventRepo.createEvent(_))
      requestQueue  <- TestWebhookHttpClient.requests
      // let test fiber sleep as we have to let requests be made to fail some tests
      // TODO: there's a better way to do this: poll the queue repeatedly with a timeout
      // TODO: see https://github.com/zio/zio/blob/31d9eacbb400c668460735a8a44fb68af9e5c311/core-tests/shared/src/test/scala/zio/ZQueueSpec.scala#L862 fo
      _             <- sleepDuration.map(Clock.Service.live.sleep(_)).getOrElse(ZIO.unit)
      testResult    <- requestsAssertion(requestQueue)
    } yield testResult

  def createWebhooks(n: Int)(status: WebhookStatus, deliveryMode: WebhookDeliveryMode): Iterable[Webhook] =
    (0 until n).map(i => singleWebhook(i.toLong, status, deliveryMode))

  def createWebhookEvents(n: Int)(webhookId: WebhookId): Iterable[WebhookEvent] =
    (0 until n).map { i =>
      WebhookEvent(
        WebhookEventKey(WebhookEventId(i.toLong), webhookId),
        WebhookEventStatus.New,
        "lorem ipsum " + i,
        Chunk(("Accept", "*/*"))
      )
    }

  def singleWebhook(id: Long, status: WebhookStatus, deliveryMode: WebhookDeliveryMode): Webhook =
    Webhook(
      WebhookId(id),
      "http://example.org/" + id,
      "testWebhook" + id,
      status,
      deliveryMode
    )

  type TestEnv = Has[WebhookEventRepo]
    with Has[TestWebhookEventRepo]
    with Has[WebhookRepo]
    with Has[TestWebhookRepo]
    with Has[WebhookStateRepo]
    with Has[TestWebhookHttpClient]
    with Has[WebhookHttpClient]
    with Has[WebhookServer]

  val testEnv: ULayer[TestEnv] = {
    val repos =
      (TestWebhookRepo.test >+> TestWebhookEventRepo.test) ++ TestWebhookStateRepo.test ++ TestWebhookHttpClient.test
    repos ++ (repos >>> WebhookServer.live)
  }.orDie
}
