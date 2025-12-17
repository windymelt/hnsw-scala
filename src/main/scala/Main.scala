import scala.collection.mutable
import scala.util.Random

type Vector = Array[Double]

case class Node(
    id: Int,
    vector: Vector,
    level: Int,
    // レベルごとの近傍ノード。インデックス0が最下層(レイヤー0)に対応
    connections: Array[mutable.Map[Int, Vector]],
    label: String
)

class HNSW(
    val maxM: Int = 16, // 各ノードの最大近傍数
    val maxM0: Int = 32, // 最上位レベルの最大近傍数
    val efConstruction: Int = 200, // 構築時の探索幅
    val efSearch: Int = 100 // 検索時の探索幅
)(
    private val lm: Double = 1.0 / math.log(maxM.toDouble)
)(private val notifyChan: ox.channels.Channel[NotifyMessage]) {
  private val levelMultiplier: Double = 1.0 / math.log(maxM.toDouble)

  val nodes = mutable.Map[Int, Node]()
  private var entryPoint: Option[Int] = None
  private var maxLevel: Int = -1

  def euclideanDistance(v1: Vector, v2: Vector): Double = {
    math.sqrt(v1.zip(v2).map { case (a, b) => (a - b) * (a - b) }.sum)
  }

  def cosineDistance(v1: Vector, v2: Vector): Double = {
    val dot = v1.zip(v2).map { case (a, b) => a * b }.sum
    val norm1 = math.sqrt(v1.map(x => x * x).sum)
    val norm2 = math.sqrt(v2.map(x => x * x).sum)
    if (norm1 == 0 || norm2 == 0) 1.0 else 1.0 - dot / (norm1 * norm2)
  }

  def calculateLevel(): Int = {
    val randomValue = Random.nextDouble()
    math.floor(-math.log(randomValue) * levelMultiplier).toInt
  }

  def addVector(id: Int, vector: Vector, label: String): Unit = {
    val level = calculateLevel()
    val connections = Array.fill(level + 1)(mutable.Map[Int, Vector]())
    val newNode = Node(id, vector, level, connections, label)
    nodes.put(id, newNode)

    if (entryPoint.isEmpty) {
      entryPoint = Some(id)
      maxLevel = level
      notifyChan.send(NotifyMessage.NodeAdded(id, vector, level, label))
      return
    }

    var currEp = entryPoint.get
    var currDist = euclideanDistance(vector, nodes(currEp).vector)

    // Phase 1: 最上位層から level+1 まで降下 (接続はしない)
    for (l <- maxLevel to (level + 1) by -1) {
      // searchLayerはgreedyに1つだけ探索するモードとして使うこともできるが、
      // ここでは簡易的に「現在のcurrEpの近傍を見て、より近いものがあれば移動」を繰り返す
      // Greedy search implementation for descent
      var changed = true
      while (changed) {
        changed = false
        val currNode = nodes(currEp)
        // 現在のレイヤー l での近傍をチェック
        // もし自分よりレベルが低いノードがepならconnections(l)が無い可能性があるのでチェック
        if (l <= currNode.level) {
          for ((neighborId, neighborVec) <- currNode.connections(l)) {
            val d = euclideanDistance(vector, neighborVec)
            if (d < currDist) {
              currDist = d
              currEp = neighborId
              changed = true
            }
          }
        }
      }
    }

    // Phase 2: level から 0 まで降下しつつ接続
    val loopMax = math.min(level, maxLevel)
    for (l <- loopMax to 0 by -1) {
      val candidates = searchLayer(id, currEp, l, efConstruction)
      // 近い順にソート済み
      val M = if (l == 0) maxM0 else maxM
      val neighbors = candidates.take(M)

      // 双方向接続
      for ((neighborId, dist) <- neighbors) {
        val neighborNode = nodes(neighborId)
        val neighborVec = neighborNode.vector

        // 自分 -> 相手
        newNode.connections(l).put(neighborId, neighborVec)
        notifyChan.send(NotifyMessage.NewConnection(id, neighborId))

        // 相手 -> 自分
        neighborNode.connections(l).put(id, vector)
        notifyChan.send(NotifyMessage.NewConnection(neighborId, id))

        // 相手の接続数制限チェック (簡易的な枝刈り: 一番遠いものを削除)
        if (neighborNode.connections(l).size > M) {
          val farthest = neighborNode
            .connections(l)
            .map { case (nId, vec) =>
              (nId, euclideanDistance(neighborVec, vec))
            }
            .maxBy(_._2)
          neighborNode.connections(l).remove(farthest._1)
        }
      }

      // 次の層の開始点 (候補の中で一番近いもの)
      if (neighbors.nonEmpty) {
        currEp = neighbors.head._1
        currDist = neighbors.head._2
      }
    }

    // エントリポイントの更新 (新しいノードがより高いレベルを持つ場合)
    if (level > maxLevel) {
      maxLevel = level
      entryPoint = Some(id)
    }

    notifyChan.send(NotifyMessage.NodeAdded(id, vector, level, label))
  }

  // searchLayer: 特定のレイヤーでの近傍探索
  private def searchLayer(
      targetId: Int,
      entryPointId: Int,
      level: Int,
      ef: Int
  ): List[(Int, Double)] = {
    val targetVector = nodes(targetId).vector

    // 訪問候補 (MinHeap: 近い順に取り出す)
    val candidates = mutable.PriorityQueue[(Double, Int)]()(using
      Ordering.by[(Double, Int), Double](_._1).reverse
    )
    val visited = mutable.Set[Int]()

    // 結果セット (MaxHeap的な役割だが、ここではソート済みリストとして扱う簡易実装にするか、
    // あるいはPriorityQueue(MaxHeap)で「遠いものを捨てる」管理をするのが効率的)
    // ここでは簡潔さのため「訪問した全ノード」を保持し、最後にソートして上位efを返す形をとる(やや非効率だが正確)
    // HNSWの論文の `W` に相当
    val found = mutable.Map[Int, Double]()

    val epDist = euclideanDistance(targetVector, nodes(entryPointId).vector)
    candidates.enqueue((epDist, entryPointId))
    visited.add(entryPointId)
    found.put(entryPointId, epDist)

    while (candidates.nonEmpty) {
      val (currDist, currId) = candidates.dequeue()

      // 動的な停止条件:
      // 候補の中で一番近いものが、すでに見つかった「ef番目に近いノード」より遠ければ終了
      // (foundがef個未満ならまだ探索する)
      val farthestFoundDist = if (found.size >= ef) {
        // foundの中で一番遠い距離
        found.values.max
      } else {
        Double.MaxValue
      }

      if (currDist > farthestFoundDist) {
        // これ以上探索してもef内に入るノードは見つからない（candidatesは距離順なので）
        // ただし、candidatesの中にはまだ近いものがあるかもしれないので、
        // 「取り出したcurrDist」が閾値を超えたら終了で良い
        // (PriorityQueueから取り出しているので、これが最小。最小が閾値超えなら残りは全部だめ)
        return found.toList.sortBy(_._2).take(ef)
      }

      val currNode = nodes(currId)
      // レベルチェック: currNodeがこのレベルを持っているか
      if (level <= currNode.level) {
        for ((neighborId, neighborVec) <- currNode.connections(level)) {
          if (!visited.contains(neighborId)) {
            visited.add(neighborId)
            val d = euclideanDistance(targetVector, neighborVec)

            // 枝刈り: foundに入る余地があるか、あるいはfoundがまだ埋まっていないなら追加
            // foundの最悪値より近いなら候補に追加
            // (まだef個埋まってない or 最悪値より良い)
            val currentFarthest =
              if (found.size >= ef) found.values.max else Double.MaxValue

            if (found.size < ef || d < currentFarthest) {
              candidates.enqueue((d, neighborId))
              found.put(neighborId, d)

              // foundが溢れたら遠いものを消す (メモリ節約 & 閾値更新のため)
              if (found.size > ef) {
                val (farthestId, _) = found.maxBy(_._2)
                found.remove(farthestId)
              }
            }
          }
        }
      }
    }

    found.toList.sortBy(_._2).take(ef)
  }

  def searchNeighbors(
      targetId: Int,
      k: Int
  ): List[(nodeId: Int, dist: Double)] = {
    val targetNode = nodes(targetId)

    if (entryPoint.isEmpty) {
      return List.empty
    }

    // 最上位レベルから探索
    var currentLevel = maxLevel

    // 初期探索ポイント
    var currentId = entryPoint.get
    var currentDistance =
      euclideanDistance(targetNode.vector, nodes(currentId).vector)

    // Phase 1: 上層レベルからレベル1まで、グリーディに最寄りのエントリポイントを探して降下
    for (level <- maxLevel to 1 by -1) {
      var changed = true
      while (changed) {
        changed = false
        val currNode = nodes(currentId)
        if (level <= currNode.level) {
          for ((neighborId, neighborVec) <- currNode.connections(level)) {
            val d = euclideanDistance(targetNode.vector, neighborVec)
            if (d < currentDistance) {
              currentDistance = d
              currentId = neighborId
              changed = true
            }
          }
        }
      }
    }

    // Phase 2: 最下層(レベル0)でefSearchを使って広範囲探索
    val candidates = searchLayer(targetId, currentId, 0, efSearch)
    candidates.take(k)
  }

  def searchNeighborsFromPoint(
      vector: Vector,
      k: Int
  ): List[(nodeId: Int, dist: Double)] = {
    val tempId = 99999999
    addVector(tempId, vector, "temp_node")

    val results = searchNeighbors(tempId, k)

    removeNode(tempId)

    results
  }

  def removeNode(id: Int): Boolean = {
    if (!nodes.contains(id)) {
      return false
    }

    val node = nodes(id)

    // すべての近傍からこのノードを削除
    for (level <- 0 to node.level) {
      // このノードが持っている各レベルの近傍に対して
      for ((neighborId, _) <- node.connections(level)) {
        if (nodes.contains(neighborId)) {
          val neighborNode = nodes(neighborId)
          // 相手の同じレベルのコネクションから自分を削除
          if (level <= neighborNode.level) {
            neighborNode.connections(level).remove(id)
          }
        }
      }
    }

    // ノードを削除
    nodes.remove(id)

    // エントリポイントが削除された場合は再設定
    if (entryPoint.contains(id)) {
      entryPoint = nodes.keys.headOption
    }

    true
  }

  def printStats(): Unit = {
    println(s"Nodes: ${nodes.size}")
    println(s"Max Level: $maxLevel")
    println(s"Entry Point: ${entryPoint}")
  }
}
