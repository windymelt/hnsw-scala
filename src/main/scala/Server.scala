import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.Encoder
import sttp.tapir.*
import sttp.tapir.CodecFormat.*
import sttp.tapir.server.netty.sync.{NettySyncServer, OxStreams}
import ox.*
import ox.channels.Channel
import ox.flow.Flow
import cats.Id
import cats.instances.vector

enum NotifyMessage derives Encoder {
  case NodeAdded(
      id: Int,
      vector: Vector,
      level: Int,
      label: String
  )
  case NewConnection(
      fromId: Int,
      toId: Int
  )
}

object Server {
  import ox.*

  val notifyChan = ox.channels.Channel.rendezvous[NotifyMessage]
  val goChan = ox.channels.Channel.rendezvous[Unit]
  val hnsw =
    new HNSW(maxM = 2, maxM0 = 3, efConstruction = 200, efSearch = 100)()(
      notifyChan
    )

  // テスト用のベクトルを生成
  val strings =
    Seq(
      "柴犬",
      "秋田犬",
      "ゴールデン・レトリーバー",
      "ラブラドール・レトリーバー",
      "プードル",
      "チワワ",
      "ダックスフンド",
      "フレンチ・ブルドッグ",
      "ブルドッグ",
      "ポメラニアン",
      "シベリアン・ハスキー",
      "ジャーマン・シェパード",
      "ビーグル",
      "ボーダー・コリー",
      "パグ",
      "ヨークシャー・テリア",
      "ミニチュア・シュナウザー",
      "シー・ズー",
      "コーギー",
      "セント・バーナード",
      "日本猫",
      "アメリカン・ショートヘア",
      "ブリティッシュ・ショートヘア",
      "スコティッシュ・フォールド",
      "マンチカン",
      "ロシアンブルー",
      "メインクーン",
      "ラグドール",
      "ペルシャ",
      "ベンガル",
      "シャム",
      "ノルウェージャン・フォレスト・キャット",
      "アビシニアン",
      "サイベリアン",
      "エキゾチック・ショートヘア",
      "ヒマラヤン",
      "トンキニーズ",
      "バーマン",
      "オリエンタル・ショートヘア",
      "セルカーク・レックス",
      "カワラバト",
      "キジバト",
      "シラコバト",
      "アオバト",
      "カラスバト",
      "ヨナグニカラスバト",
      "アカガシラカラスバト",
      "シロガシラカラスバト",
      "ズアカアオバト",
      "チュウダイズアカアオバト",
      "ベニバト",
      "キンバト"
    )

  // インデックスの状態を表示
  hnsw.printStats()

  // (1)
  val wsEndpoint = endpoint.get
    .in("ws")
    .out(webSocketBody[String, TextPlain, String, TextPlain](OxStreams))

  // (2)
  def wsProcessor(using Ox): OxStreams.Pipe[String, String] = { requestStream =>
    val outgoing = Channel.bufferedDefault[String] // (1)
    fork {
      forever {
        val msg = notifyChan.receive()
        outgoing.send(msg.asJson.noSpaces)
      }
    }
    fork {
      import scala.concurrent.duration.given
      goChan.receive() // ブロックして待機
      val vectors = Embedding.generateEmbedding(strings).get()
      println("Generated embeddings for input strings")
      vectors.zipWithIndex.foreach { case (vector, id) =>
        hnsw.addVector(id, vector.toArray, strings(id))
        println(s"Added vector for node $id")
        sleep(500.millis)
      }
      println("All vectors added successfully")
    }
    fork {
      requestStream.runForeach { msg =>
        msg match {
          case "ok" =>
            println("Received ok message from client")
            goChan.send(())
            outgoing.send("""{"result": "ok"}""")
          case _ =>
        }
      }
    }
    Flow.fromSource(outgoing)

  }
  // (3)
  def wsServerEndpoint(using Ox) =
    wsEndpoint.serverLogicSuccess[Id](_ => wsProcessor)

  // (4)
  @main def echoWsServer(): Unit = supervised {
    NettySyncServer().addEndpoint(wsServerEndpoint).startAndWait()
  }
}
