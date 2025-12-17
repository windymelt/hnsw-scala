import scala.concurrent.ExecutionContext
import akka.stream.Materializer
import akka.actor.ActorSystem
import io.cequence.openaiscala.service.OpenAIChatCompletionServiceFactory
import io.cequence.openaiscala.service.OpenAICoreServiceFactory
import io.cequence.openaiscala.domain.settings.CreateEmbeddingsSettings
import scala.concurrent.Future
import io.cequence.wsclient.domain.WsRequestContext

object Embedding {
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val materializer: Materializer = Materializer(ActorSystem())
  val service = OpenAICoreServiceFactory(
    "https://api.ai.sakura.ad.jp/v1/",
    WsRequestContext(
      None,
      List(
        "Authorization" -> s"Bearer ${sys.env.getOrElse("OPENAI_SCALA_CLIENT_API_KEY", "")}"
      )
    )
  )

  def generateEmbedding(input: Seq[String]): Future[Seq[Seq[Double]]] = {
    val settings = CreateEmbeddingsSettings("multilingual-e5-large")
    val result = service.createEmbeddings(input, settings)
    result.map(_.data.map(_.embedding))
  }
}
