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

enum NotifyMessage derives Encoder {
  case NodeAdded(
      id: Int,
      vector: Vector,
      level: Int
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
    new HNSW(maxM = 3, maxM0 = 4, efConstruction = 200, efSearch = 100)()(
      notifyChan
    )

  // テスト用のベクトルを生成
  val vectors = for {
    i <- 0 until 10
    j <- 0 until 10
    k <- 0 until 10
  } yield Array(
    Math.sin(i.toDouble),
    Math.cos(j.toDouble),
    Math.cos(k.toDouble)
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
      goChan.receive() // ブロックして待機
      vectors.zipWithIndex.foreach { case (vector, id) =>
        hnsw.addVector(id, vector)
        println(s"Added vector for node $id")
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
