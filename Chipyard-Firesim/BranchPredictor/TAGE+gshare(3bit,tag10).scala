// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package freechips.rocketchip.rocket

import chisel3._
import chisel3.util._
import chisel3.internal.InstanceId
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tile.HasCoreParameters
import freechips.rocketchip.util._

case class BHTParams(
  nEntries: Int = 2048, 
  counterLength: Int = 3, 
  historyLength: Int = 80,
  historyBits: Int = 5)

case class BTBParams(
  nEntries: Int = 28,
  nMatchBits: Int = 14, 
  nPages: Int = 6, 
  nRAS: Int = 6,
  bhtParams: Option[BHTParams] = Some(BHTParams()),
  updatesOutOfOrder: Boolean = false)

trait HasBtbParameters extends HasCoreParameters { this: InstanceId =>
  val btbParams = tileParams.btb.getOrElse(BTBParams(nEntries = 0))
  val matchBits = btbParams.nMatchBits max log2Ceil(p(CacheBlockBytes) * tileParams.icache.get.nSets)
  val entries = btbParams.nEntries
  val updatesOutOfOrder = btbParams.updatesOutOfOrder
  val nPages = (btbParams.nPages + 1) / 2 * 2 // control logic assumes 2 divides pages
}

abstract class BtbModule(implicit val p: Parameters) extends Module with HasBtbParameters {
  Annotated.params(this, btbParams)
}

abstract class BtbBundle(implicit val p: Parameters) extends Bundle with HasBtbParameters

class RAS(nras: Int) {
  def push(addr: UInt): Unit = { //RAS에 주소를 push
    when (count < nras.U) { count := count + 1.U } //RAS가 가득 차있지 않는 경우에만 push
    val nextPos = Mux((isPow2(nras)).B || pos < (nras-1).U, pos+1.U, 0.U) //다음 위치를 계산하고 주소를 스택에 저장한다.
    stack(nextPos) := addr
    pos := nextPos
  }
  def peek: UInt = stack(pos) // RAS에서 가장 최근의 주소를 확인한다.
  def pop(): Unit = when (!isEmpty) { // 주소를 pop한다.(비어있지 않은 경우에만)
    count := count - 1.U
    pos := Mux((isPow2(nras)).B || pos > 0.U, pos-1.U, (nras-1).U) //다음 위치를 계산하고 업데이트한다.
  }
  def clear(): Unit = count := 0.U //RAS 초기화
  def isEmpty: Bool = count === 0.U

  private val count = RegInit(0.U(log2Up(nras+1).W))
  private val pos = RegInit(0.U(log2Up(nras).W))
  private val stack = Reg(Vec(nras, UInt()))
}

class BHTResp(implicit p: Parameters) extends BtbBundle()(p) {
  val history = UInt(btbParams.bhtParams.map(_.historyLength).getOrElse(1).W) 
  val value = UInt(btbParams.bhtParams.map(_.counterLength).getOrElse(1).W)
  def taken = value(2) 
  def strongly_taken = value > 4.U
}

// BHT contains table of 2-bit counters and a global history register.
// The BHT only predicts and updates when there is a BTB hit.
// The global history:
//    - updated speculatively in fetch (if there's a BTB hit).
//    - on a mispredict, the history register is reset (again, only if BTB hit).
// The counter table:
//    - each counter corresponds with the address of the fetch packet ("fetch pc").
//    - updated when a branch resolves (and BTB was a hit for that branch).
//      The updating branch must provide its "fetch pc".
class BHT(params: BHTParams)(implicit val p: Parameters) extends HasCoreParameters {
  def index(addr: UInt, history: UInt) = {
    def hashHistory(hist: UInt) = {
      hist(params.historyLength-1, params.historyLength-params.historyBits)
    }
    def hashAddr(addr: UInt) = {
      val hi = addr >> log2Ceil(fetchBytes)
      hi(log2Ceil(params.nEntries)-1, 0) ^ (hi >> log2Ceil(params.nEntries))(1, 0)
    }
    hashAddr(addr) ^ (hashHistory(history) << (log2Up(params.nEntries) - params.historyBits))
  }

  def index1(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    hi(9,0) ^ history(79,70)
  }

  def index2(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    hi(9,0) ^ history(79,70) ^ history(69,60)
  }

  def index3(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    hi(9,0) ^ history(79,70) ^ history(69,60) ^ history(59,50) ^ history(49,40)
  }

  def index4(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    hi(9,0) ^ history(9,0) ^ history(19,10) ^ history(29,20) ^ history(39,30) ^ history(49,40) ^ history(59,50) ^ history(69,60) ^ history(79,70)
  }

  def tag1(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    val hii = hi >> 10
    hii(9,0) ^ history(79,70)
  }

  def tag2(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    val hii = hi >> 10
    hii(9,0) ^ history(79,70) ^ history(69,60)
  }

  def tag3(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    val hii = hi >> 10
    hii(9,0) ^ history(79,70) ^ history(69,60) ^ history(59,50) ^ history(49,40)
  }

  def tag4(addr: UInt, history: UInt) = {
    val hi = addr >> log2Ceil(fetchBytes)
    val hii = hi >> 10
    hii(9,0) ^ history(9,0) ^ history(19,10) ^ history(29,20) ^ history(39,30) ^ history(49,40) ^ history(59,50) ^ history(69,60) ^ history(79,70)
  }

  def get(addr: UInt): BHTResp = {   // add를 입력받아서 BHT에서 해당 주소에 대한 결과를 반호나한다.
    //accessCount := accessCount + 1.U
    //printf(cf"accesss count = $accessCount\n")
    
    val res = Wire(new BHTResp)

    val tagMatch1 = Mux((T1_tag(index1(addr, history)) === tag1(addr, history)), 1.U, 0.U)
    val tagMatch2 = Mux((T2_tag(index2(addr, history)) === tag2(addr, history)), 1.U, 0.U)
    val tagMatch3 = Mux((T3_tag(index3(addr, history)) === tag3(addr, history)), 1.U, 0.U)
    val tagMatch4 = Mux((T4_tag(index4(addr, history)) === tag4(addr, history)), 1.U, 0.U)

    when ((tagMatch4 === 1.U)) {
      res.value := Mux(resetting, 3.U, T4_pred(index4(addr, history)))
    }.elsewhen ((tagMatch3 === 1.U)) {
      res.value := Mux(resetting, 3.U, T3_pred(index3(addr, history)))
    }.elsewhen ((tagMatch2 === 1.U)) {
      res.value := Mux(resetting, 3.U, T2_pred(index2(addr, history)))
    }.elsewhen ((tagMatch1 === 1.U)) {
      res.value := Mux(resetting, 3.U, T1_pred(index1(addr, history)))
    }.otherwise (res.value := Mux(resetting, 3.U, table(index(addr, history))))

    res.history := history
    res
  }


  def updateTable(addr: UInt, d: BHTResp, taken: Bool, mispredict: Bool): Unit = {
    val tagMatch11 = Mux((T1_tag(index1(addr, d.history)) === tag1(addr, d.history)), 1.U, 0.U)
    val tagMatch22 = Mux((T2_tag(index2(addr, d.history)) === tag2(addr, d.history)), 1.U, 0.U)
    val tagMatch33 = Mux((T3_tag(index3(addr, d.history)) === tag3(addr, d.history)), 1.U, 0.U)
    val tagMatch44 = Mux((T4_tag(index4(addr, d.history)) === tag4(addr, d.history)), 1.U, 0.U)

    printf(cf"tagMatch11 = $tagMatch11\n tagMatch22 = $tagMatch22\n tagMatch33 = $tagMatch33\n tagMatch44 = $tagMatch44\n")


    val u4 = T4_u(index4(addr, d.history))
    val u3 = T3_u(index3(addr, d.history))
    val u2 = T2_u(index2(addr, d.history))
    val u1 = T1_u(index1(addr, d.history))

    printf(cf"u1 = $u1\n u2 = $u2\n u3 = $u3\n u4 = $u4\n")
    
      when ((tagMatch44 === 1.U)) {
        wen_T4_pred := true.B
        wen_T3_pred := false.B
        wen_T2_pred := false.B
        wen_T1_pred := false.B
        wen := false.B
        waddr_T4 := index4(addr, d.history)
        wdata_T4_pred := Mux(!taken, Mux(d.value === 0.U, 0.U, d.value - 1.U), Mux(d.value === 7.U, 7.U, d.value + 1.U))
        
        when (tagMatch33 === 1.U) {
          when (T3_pred(index3(addr, d.history))(2) =/= T4_pred(index4(addr, d.history))(2)){
            wen_T4_u := true.B
            wen_T3_u := false.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_u := Mux(mispredict, Mux(u4 === 0.U, 0.U, u4 - 1.U),
                                          Mux(u4 === 3.U, 3.U, u4 + 1.U))
          }
        }.elsewhen (tagMatch22 === 1.U) {
          when (T2_pred(index2(addr, d.history))(2) =/= T4_pred(index4(addr, d.history))(2)){
            wen_T4_u := true.B
            wen_T3_u := false.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_u := Mux(mispredict, Mux(u4 === 0.U, 0.U, u4 - 1.U),
                                          Mux(u4 === 3.U, 3.U, u4 + 1.U))
          }
        }.elsewhen (tagMatch11 === 1.U) {
          when (T1_pred(index1(addr, d.history))(2) =/= T4_pred(index4(addr, d.history))(2)){
            wen_T4_u := true.B
            wen_T3_u := false.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_u := Mux(mispredict, Mux(u4 === 0.U, 0.U, u4 - 1.U),
                                          Mux(u4 === 3.U, 3.U, u4 + 1.U))
          }
        }.otherwise {
          when (table(index(addr, d.history))(2) =/= T4_pred(index4(addr, d.history))(2)){
            wen_T4_u := true.B
            wen_T3_u := false.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_u := Mux(mispredict, Mux(u4 === 0.U, 0.U, u4 - 1.U),
                                          Mux(u4 === 3.U, 3.U, u4 + 1.U))
          }
        }     
      }.elsewhen ((tagMatch33 === 1.U)) {
        wen_T4_pred := false.B
        wen_T3_pred := true.B
        wen_T2_pred := false.B
        wen_T1_pred := false.B
        wen := false.B
        waddr_T3 := index3(addr, d.history)
        wdata_T3_pred := Mux(!taken, Mux(d.value === 0.U, 0.U, d.value - 1.U), Mux(d.value === 7.U, 7.U, d.value + 1.U))

        when (tagMatch22 === 1.U) {
          when (T2_pred(index2(addr, d.history))(2) =/= T3_pred(index3(addr, d.history))(2)){
            wen_T4_u := false.B
            wen_T3_u := true.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T3 := index3(addr, d.history)
            wdata_T3_u := Mux(mispredict, Mux(u3 === 0.U, 0.U, u3 - 1.U),
                                          Mux(u3 === 3.U, 3.U, u3 + 1.U))
          }
        }.elsewhen (tagMatch11 === 1.U) {
          when (T1_pred(index1(addr, d.history))(2) =/= T3_pred(index3(addr, d.history))(2)){
            wen_T4_u := false.B
            wen_T3_u := true.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T3 := index3(addr, d.history)
            wdata_T3_u := Mux(mispredict, Mux(u3 === 0.U, 0.U, u3 - 1.U),
                                          Mux(u3 === 3.U, 3.U, u3 + 1.U))
          }
        }.otherwise {
          when (table(index(addr, d.history))(2) =/= T3_pred(index3(addr, d.history))(2)){
            wen_T4_u := false.B
            wen_T3_u := true.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T3 := index3(addr, d.history)
            wdata_T3_u := Mux(mispredict, Mux(u3 === 0.U, 0.U, u3 - 1.U),
                                          Mux(u3 === 3.U, 3.U, u3 + 1.U))
          }
        }

        when (mispredict) {
          when (u4 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := false.B
            wen_T4_tag := true.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_tag := tag4(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := false.B
            wen_T4_pred := true.B
            wdata_T4_pred := Mux(taken, 4.U, 3.U)

          }.otherwise {
            wen_T4_u := true.B
            wen_T3_u := false.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_u := Mux(u4 === 0.U, 0.U, u4 - 1.U)
          }
        }

      }.elsewhen ((tagMatch22 === 1.U)) {
        wen_T4_pred := false.B
        wen_T3_pred := false.B
        wen_T2_pred := true.B
        wen_T1_pred := false.B
        wen := false.B
        waddr_T2 := index2(addr, d.history)
        wdata_T2_pred := Mux(!taken, Mux(d.value === 0.U, 0.U, d.value - 1.U), Mux(d.value === 7.U, 7.U, d.value + 1.U))

        when (tagMatch11 === 1.U) {
          when (T1_pred(index1(addr, d.history))(2) =/= T2_pred(index2(addr, d.history))(2)){
            wen_T4_u := false.B
            wen_T3_u := false.B
            wen_T2_u := true.B
            wen_T1_u := false.B
            waddr_T2 := index2(addr, d.history)
            wdata_T2_u := Mux(mispredict, Mux(u2 === 0.U, 0.U, u2 - 1.U),
                                          Mux(u2 === 3.U, 3.U, u2 + 1.U))
          }
        }.otherwise {
          when (table(index(addr, d.history))(2) =/= T2_pred(index2(addr, d.history))(2)){
            wen_T4_u := false.B
            wen_T3_u := false.B
            wen_T2_u := true.B
            wen_T1_u := false.B
            waddr_T2 := index2(addr, d.history)
            wdata_T2_u := Mux(mispredict, Mux(u2 === 0.U, 0.U, u2 - 1.U),
                                          Mux(u2 === 3.U, 3.U, u2 + 1.U))
          }
        }

        when (mispredict) {
          when (u3 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := true.B
            wen_T4_tag := false.B
            waddr_T3 := index3(addr, d.history)
            wdata_T3_tag := tag3(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := true.B
            wen_T4_pred := false.B
            wdata_T3_pred := Mux(taken, 4.U, 3.U)

          }.elsewhen (u4 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := false.B
            wen_T4_tag := true.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_tag := tag4(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := false.B
            wen_T4_pred := true.B
            wdata_T4_pred := Mux(taken, 4.U, 3.U)

          }.otherwise {
            wen_T4_u := true.B
            wen_T3_u := true.B
            wen_T2_u := false.B
            wen_T1_u := false.B
            waddr_T3 := index3(addr, d.history)
            waddr_T4 := index4(addr, d.history)
            wdata_T3_u := Mux(u3 === 0.U, 0.U, u3 - 1.U)
            wdata_T4_u := Mux(u4 === 0.U, 0.U, u4 - 1.U)
          }
        }
      
      }.elsewhen ((tagMatch11 === 1.U)) {
        wen_T4_pred := false.B
        wen_T3_pred := false.B
        wen_T2_pred := false.B
        wen_T1_pred := true.B
        wen := false.B
        waddr_T1 := index1(addr, d.history)
        wdata_T1_pred := Mux(!taken, Mux(d.value === 0.U, 0.U, d.value - 1.U), Mux(d.value === 7.U, 7.U, d.value + 1.U))

        when (table(index(addr, d.history))(2) =/= T1_pred(index1(addr, d.history))(2)){
          wen_T4_u := false.B
          wen_T3_u := false.B
          wen_T2_u := false.B
          wen_T1_u := true.B
          waddr_T1 := index1(addr, d.history)
          wdata_T1_u := Mux(mispredict, Mux(u1 === 0.U, 0.U, u1 - 1.U),
                                        Mux(u1 === 3.U, 3.U, u1 + 1.U))
        }

        when (mispredict) {
          when (u2 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := true.B
            wen_T3_tag := false.B
            wen_T4_tag := false.B
            waddr_T2 := index2(addr, d.history)
            wdata_T2_tag := tag2(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := true.B
            wen_T3_pred := false.B
            wen_T4_pred := false.B
            wdata_T2_pred := Mux(taken, 4.U, 3.U)

          }.elsewhen (u3 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := true.B
            wen_T4_tag := false.B
            waddr_T3 := index3(addr, d.history)
            wdata_T3_tag := tag3(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := true.B
            wen_T4_pred := false.B
            wdata_T3_pred := Mux(taken, 4.U, 3.U)

          }.elsewhen (u4 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := false.B
            wen_T4_tag := true.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_tag := tag4(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := false.B
            wen_T4_pred := true.B
            wdata_T4_pred := Mux(taken, 4.U, 3.U)

          }.otherwise {
            wen_T4_u := true.B
            wen_T3_u := true.B
            wen_T2_u := true.B
            wen_T1_u := false.B
            waddr_T2 := index2(addr, d.history)
            waddr_T3 := index3(addr, d.history)
            waddr_T4 := index4(addr, d.history)
            wdata_T2_u := Mux(u2 === 0.U, 0.U, u2 - 1.U)
            wdata_T3_u := Mux(u3 === 0.U, 0.U, u3 - 1.U)
            wdata_T4_u := Mux(u4 === 0.U, 0.U, u4 - 1.U)
          }
        }

      }.otherwise {
        wen_T4_pred := false.B
        wen_T3_pred := false.B
        wen_T2_pred := false.B
        wen_T1_pred := false.B
        wen := true.B
        waddr := index(addr, d.history)
        wdata := Mux(!taken, Mux(d.value === 0.U, 0.U, d.value - 1.U), Mux(d.value === 7.U, 7.U, d.value + 1.U))

        when (mispredict) {
          when (u1 === 0.U) {
            wen_T1_tag := true.B
            wen_T2_tag := false.B
            wen_T3_tag := false.B
            wen_T4_tag := false.B
            waddr_T1 := index1(addr, d.history)
            wdata_T1_tag := tag1(addr, d.history)

            wen_T1_pred := true.B
            wen_T2_pred := false.B
            wen_T3_pred := false.B
            wen_T4_pred := false.B
            wdata_T1_pred := Mux(taken, 4.U, 3.U)

          }.elsewhen (u2 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := true.B
            wen_T3_tag := false.B
            wen_T4_tag := false.B
            waddr_T2 := index2(addr, d.history)
            wdata_T2_tag := tag2(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := true.B
            wen_T3_pred := false.B
            wen_T4_pred := false.B
            wdata_T2_pred := Mux(taken, 4.U, 3.U)

          }.elsewhen (u3 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := true.B
            wen_T4_tag := false.B
            waddr_T3 := index3(addr, d.history)
            wdata_T3_tag := tag3(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := true.B
            wen_T4_pred := false.B
            wdata_T3_pred := Mux(taken, 4.U, 3.U)

          }.elsewhen (u4 === 0.U) {
            wen_T1_tag := false.B
            wen_T2_tag := false.B
            wen_T3_tag := false.B
            wen_T4_tag := true.B
            waddr_T4 := index4(addr, d.history)
            wdata_T4_tag := tag4(addr, d.history)

            wen_T1_pred := false.B
            wen_T2_pred := false.B
            wen_T3_pred := false.B
            wen_T4_pred := true.B
            wdata_T4_pred := Mux(taken, 4.U, 3.U)

          }.otherwise {
            wen_T4_u := true.B
            wen_T3_u := true.B
            wen_T2_u := true.B
            wen_T1_u := true.B
            waddr_T1 := index1(addr, d.history)
            waddr_T2 := index2(addr, d.history)
            waddr_T3 := index3(addr, d.history)
            waddr_T4 := index4(addr, d.history)
            wdata_T1_u := Mux(u1 === 0.U, 0.U, u1 - 1.U)
            wdata_T2_u := Mux(u2 === 0.U, 0.U, u2 - 1.U)
            wdata_T3_u := Mux(u3 === 0.U, 0.U, u3 - 1.U)
            wdata_T4_u := Mux(u4 === 0.U, 0.U, u4 - 1.U)
          }
        }
      }
  }



  def resetHistory(d: BHTResp): Unit = {
    history := d.history
  }


  def updateHistory(addr: UInt, d: BHTResp, taken: Bool): Unit = {
    history := Cat(taken, d.history >> 1)
  }


  def advanceHistory(taken: Bool): Unit = {
    history := Cat(taken, history >> 1)
  }

  //base predictor table
  private val table = Mem(params.nEntries, UInt(params.counterLength.W))
  val history = RegInit(0.U(params.historyLength.W))

  //tagged components
  private val T1_pred = Mem(1024, UInt(3.W))
  private val T1_tag = Mem(1024, UInt(10.W))
  private val T1_u = Mem(1024, UInt(2.W))

  private val T2_pred = Mem(1024, UInt(3.W))
  private val T2_tag = Mem(1024, UInt(10.W))
  private val T2_u = Mem(1024, UInt(2.W))

  private val T3_pred = Mem(1024, UInt(3.W))
  private val T3_tag = Mem(1024, UInt(10.W))
  private val T3_u = Mem(1024, UInt(2.W))

  private val T4_pred = Mem(1024, UInt(3.W))
  private val T4_tag = Mem(1024, UInt(10.W))
  private val T4_u = Mem(1024, UInt(2.W))



  private val reset_waddr = RegInit(0.U((params.nEntries.log2+1).W)) 
  private val resetting = !reset_waddr(params.nEntries.log2) 
  private val wen = WireInit(resetting) 
  private val waddr = WireInit(reset_waddr) 
  private val wdata = WireInit(3.U) 
  when (resetting) { reset_waddr := reset_waddr + 1.U } 
  when (wen) { table(waddr) := wdata } 

  private val reset_waddr_T1 = RegInit(0.U((11).W)) 
  private val resetting_T1 = !reset_waddr(10) 
  private val wen_T1_pred = WireInit(resetting)
  private val wen_T1_tag = WireInit(resetting) 
  private val wen_T1_u = WireInit(resetting) 
  private val waddr_T1 = WireInit(reset_waddr) 
  private val wdata_T1_pred = WireInit(0.U)
  private val wdata_T1_tag = WireInit(0.U) 
  private val wdata_T1_u = WireInit(0.U) 
  when (resetting_T1) { reset_waddr_T1 := reset_waddr_T1 + 1.U } 
  when (wen_T1_pred) { T1_pred(waddr_T1) := wdata_T1_pred } 
  when (wen_T1_tag) { T1_tag(waddr_T1) := wdata_T1_tag }
  when (wen_T1_u) { T1_u(waddr_T1) := wdata_T1_u } 

  private val reset_waddr_T2 = RegInit(0.U((11).W)) 
  private val resetting_T2 = !reset_waddr(10) 
  private val wen_T2_pred = WireInit(resetting)
  private val wen_T2_tag = WireInit(resetting) 
  private val wen_T2_u = WireInit(resetting) 
  private val waddr_T2 = WireInit(reset_waddr) 
  private val wdata_T2_pred = WireInit(0.U)
  private val wdata_T2_tag = WireInit(0.U) 
  private val wdata_T2_u = WireInit(0.U) 
  when (resetting_T2) { reset_waddr_T2 := reset_waddr_T2 + 1.U } 
  when (wen_T2_pred) { T2_pred(waddr_T2) := wdata_T2_pred } 
  when (wen_T2_tag) { T2_tag(waddr_T2) := wdata_T2_tag }
  when (wen_T2_u) { T2_u(waddr_T2) := wdata_T2_u }

  private val reset_waddr_T3 = RegInit(0.U((11).W)) 
  private val resetting_T3 = !reset_waddr(10) 
  private val wen_T3_pred = WireInit(resetting)
  private val wen_T3_tag = WireInit(resetting) 
  private val wen_T3_u = WireInit(resetting) 
  private val waddr_T3 = WireInit(reset_waddr) 
  private val wdata_T3_pred = WireInit(0.U)
  private val wdata_T3_tag = WireInit(0.U) 
  private val wdata_T3_u = WireInit(0.U) 
  when (resetting_T3) { reset_waddr_T3 := reset_waddr_T3 + 1.U } 
  when (wen_T3_pred) { T3_pred(waddr_T3) := wdata_T3_pred } 
  when (wen_T3_tag) { T3_tag(waddr_T3) := wdata_T3_tag }
  when (wen_T3_u) { T3_u(waddr_T3) := wdata_T3_u }

  private val reset_waddr_T4 = RegInit(0.U((11).W)) 
  private val resetting_T4 = !reset_waddr(10) 
  private val wen_T4_pred = WireInit(resetting)
  private val wen_T4_tag = WireInit(resetting) 
  private val wen_T4_u = WireInit(resetting) 
  private val waddr_T4 = WireInit(reset_waddr) 
  private val wdata_T4_pred = WireInit(0.U)
  private val wdata_T4_tag = WireInit(0.U) 
  private val wdata_T4_u = WireInit(0.U) 
  when (resetting_T4) { reset_waddr_T4 := reset_waddr_T4 + 1.U } 
  when (wen_T4_pred) { T4_pred(waddr_T4) := wdata_T4_pred } 
  when (wen_T4_tag) { T4_tag(waddr_T4) := wdata_T4_tag }
  when (wen_T4_u) { T4_u(waddr_T4) := wdata_T4_u }
}

object CFIType {
  def SZ = 2
  def apply() = UInt(SZ.W)
  def branch = 0.U
  def jump = 1.U
  def call = 2.U
  def ret = 3.U
}

// BTB update occurs during branch resolution (and only on a mispredict).
//  - "pc" is what future fetch PCs will tag match against.
//  - "br_pc" is the PC of the branch instruction.
class BTBUpdate(implicit p: Parameters) extends BtbBundle()(p) {
  val prediction = new BTBResp
  val pc = UInt(vaddrBits.W)
  val target = UInt(vaddrBits.W)
  val taken = Bool()
  val isValid = Bool()
  val br_pc = UInt(vaddrBits.W)
  val cfiType = CFIType()
}

// BHT update occurs during branch resolution on all conditional branches.
//  - "pc" is what future fetch PCs will tag match against.
class BHTUpdate(implicit p: Parameters) extends BtbBundle()(p) {
  val prediction = new BHTResp
  val pc = UInt(vaddrBits.W)
  val branch = Bool()
  val taken = Bool()
  val mispredict = Bool()
}

class RASUpdate(implicit p: Parameters) extends BtbBundle()(p) {
  val cfiType = CFIType()
  val returnAddr = UInt(vaddrBits.W)
}

//  - "bridx" is the low-order PC bits of the predicted branch (after
//     shifting off the lowest log(inst_bytes) bits off).
//  - "mask" provides a mask of valid instructions (instructions are
//     masked off by the predicted taken branch from the BTB).
class BTBResp(implicit p: Parameters) extends BtbBundle()(p) {
  val cfiType = CFIType()
  val taken = Bool()
  val mask = Bits(fetchWidth.W)
  val bridx = Bits(log2Up(fetchWidth).W)
  val target = UInt(vaddrBits.W)
  val entry = UInt(log2Up(entries + 1).W)
  val bht = new BHTResp
}

class BTBReq(implicit p: Parameters) extends BtbBundle()(p) {
   val addr = UInt(vaddrBits.W) 
}

// fully-associative branch target buffer
// Higher-performance processors may cause BTB updates to occur out-of-order,
// which requires an extra CAM port for updates (to ensure no duplicates get
// placed in BTB).

 //coreI==>8 indx==>14-3
class BTB(implicit p: Parameters) extends BtbModule {
  val io = IO(new Bundle {
    val req = Flipped(Valid(new BTBReq))
    val resp = Valid(new BTBResp)
    val btb_update = Flipped(Valid(new BTBUpdate))
    val bht_update = Flipped(Valid(new BHTUpdate))
    val bht_advance = Flipped(Valid(new BTBResp))
    val ras_update = Flipped(Valid(new RASUpdate))
    val ras_head = Valid(UInt(vaddrBits.W))
    val flush = Input(Bool())
    //val count = 0
  })

  val accessCount = RegInit(0.U(32.W))
  val flushCount = RegInit(0.U(32.W))

  val idxs = Reg(Vec(entries, UInt((matchBits - log2Up(coreInstBytes)).W))) 
  val idxPages = Reg(Vec(entries, UInt(log2Up(nPages).W)))
  val tgts = Reg(Vec(entries, UInt((matchBits - log2Up(coreInstBytes)).W)))
  val tgtPages = Reg(Vec(entries, UInt(log2Up(nPages).W)))
  val pages = Reg(Vec(nPages, UInt((vaddrBits - matchBits).W)))
  val pageValid = RegInit(0.U(nPages.W))
  val pagesMasked = (pageValid.asBools zip pages).map { case (v, p) => Mux(v, p, 0.U) }

  val isValid = RegInit(0.U(entries.W))
  val cfiType = Reg(Vec(entries, CFIType()))
  val brIdx = Reg(Vec(entries, UInt(log2Up(fetchWidth).W)))

  private def page(addr: UInt) = addr >> matchBits
  private def pageMatch(addr: UInt) = {
    val p = page(addr) 
    pageValid & pages.map(_ === p).asUInt 
  }
  private def idxMatch(addr: UInt) = {
    val idx = addr(matchBits-1, log2Up(coreInstBytes))
    idxs.map(_ === idx).asUInt & isValid
  }

  val r_btb_update = Pipe(io.btb_update)
  val update_target = io.req.bits.addr

  val pageHit = pageMatch(io.req.bits.addr)
  val idxHit = idxMatch(io.req.bits.addr)

  val updatePageHit = pageMatch(r_btb_update.bits.pc)
  val (updateHit, updateHitAddr) =
    if (updatesOutOfOrder) {
      val updateHits = (pageHit << 1)(Mux1H(idxMatch(r_btb_update.bits.pc), idxPages))
      (updateHits.orR, OHToUInt(updateHits))
    } else (r_btb_update.bits.prediction.entry < entries.U, r_btb_update.bits.prediction.entry)

  val useUpdatePageHit = updatePageHit.orR
  val usePageHit = pageHit.orR
  val doIdxPageRepl = !useUpdatePageHit
  val nextPageRepl = RegInit(0.U(log2Ceil(nPages).W))
  val idxPageRepl = Cat(pageHit(nPages-2,0), pageHit(nPages-1)) | Mux(usePageHit, 0.U, UIntToOH(nextPageRepl))
  val idxPageUpdateOH = Mux(useUpdatePageHit, updatePageHit, idxPageRepl)
  val idxPageUpdate = OHToUInt(idxPageUpdateOH)
  val idxPageReplEn = Mux(doIdxPageRepl, idxPageRepl, 0.U)

  val samePage = page(r_btb_update.bits.pc) === page(update_target)
  val doTgtPageRepl = !samePage && !usePageHit
  val tgtPageRepl = Mux(samePage, idxPageUpdateOH, Cat(idxPageUpdateOH(nPages-2,0), idxPageUpdateOH(nPages-1)))
  val tgtPageUpdate = OHToUInt(pageHit | Mux(usePageHit, 0.U, tgtPageRepl))
  val tgtPageReplEn = Mux(doTgtPageRepl, tgtPageRepl, 0.U)

  when (r_btb_update.valid && (doIdxPageRepl || doTgtPageRepl)) {
    val both = doIdxPageRepl && doTgtPageRepl
    val next = nextPageRepl + Mux[UInt](both, 2.U, 1.U)
    nextPageRepl := Mux(next >= nPages.U, next(0), next)
  }
 

  val repl = new PseudoLRU(entries)
  val waddr = Mux(updateHit, updateHitAddr, repl.way)
  val r_resp = Pipe(io.resp)
  when (r_resp.valid && r_resp.bits.taken || r_btb_update.valid) {
    repl.access(Mux(r_btb_update.valid, waddr, r_resp.bits.entry))
  }

  when (r_btb_update.valid) {
    val mask = UIntToOH(waddr)
    idxs(waddr) := r_btb_update.bits.pc(matchBits-1, log2Up(coreInstBytes))
    tgts(waddr) := update_target(matchBits-1, log2Up(coreInstBytes))
    idxPages(waddr) := idxPageUpdate +& 1.U // the +1 corresponds to the <<1 on io.resp.valid
    tgtPages(waddr) := tgtPageUpdate
    cfiType(waddr) := r_btb_update.bits.cfiType
    isValid := Mux(r_btb_update.bits.isValid, isValid | mask, isValid & ~mask)
    if (fetchWidth > 1)
      brIdx(waddr) := r_btb_update.bits.br_pc >> log2Up(coreInstBytes)

    require(nPages % 2 == 0)
    val idxWritesEven = !idxPageUpdate(0)

    def writeBank(i: Int, mod: Int, en: UInt, data: UInt) =
      for (i <- i until nPages by mod)
        when (en(i)) { pages(i) := data }

    writeBank(0, 2, Mux(idxWritesEven, idxPageReplEn, tgtPageReplEn),
      Mux(idxWritesEven, page(r_btb_update.bits.pc), page(update_target)))
    writeBank(1, 2, Mux(idxWritesEven, tgtPageReplEn, idxPageReplEn),
      Mux(idxWritesEven, page(update_target), page(r_btb_update.bits.pc)))
    pageValid := pageValid | tgtPageReplEn | idxPageReplEn
  }

  io.resp.valid := (pageHit << 1)(Mux1H(idxHit, idxPages))
  io.resp.bits.taken := true.B
  io.resp.bits.target := Cat(pagesMasked(Mux1H(idxHit, tgtPages)), Mux1H(idxHit, tgts) << log2Up(coreInstBytes))
  io.resp.bits.entry := OHToUInt(idxHit)
  io.resp.bits.bridx := (if (fetchWidth > 1) Mux1H(idxHit, brIdx) else 0.U)
  io.resp.bits.mask := Cat((1.U << ~Mux(io.resp.bits.taken, ~io.resp.bits.bridx, 0.U))-1.U, 1.U)
  io.resp.bits.cfiType := Mux1H(idxHit, cfiType)

  // if multiple entries for same PC land in BTB, zap them
  when (PopCountAtLeast(idxHit, 2)) {
    isValid := isValid & ~idxHit
  }
  when (io.flush) {
    //count +=1
    isValid := 0.U
  }

  if (btbParams.bhtParams.nonEmpty) {
    val bht = new BHT(Annotated.params(this, btbParams.bhtParams.get))
    val isBranch = (idxHit & cfiType.map(_ === CFIType.branch).asUInt).orR
    val res = bht.get(io.req.bits.addr)
    when (io.bht_advance.valid) {
      bht.advanceHistory(io.bht_advance.bits.bht.taken)
    }
    when (io.bht_update.valid) {
      when (io.bht_update.bits.branch) {
        accessCount := accessCount + 1.U
        printf(cf"accesss count = $accessCount\n")
        bht.updateTable(io.bht_update.bits.pc, io.bht_update.bits.prediction, io.bht_update.bits.taken, io.bht_update.bits.mispredict)
        when (io.bht_update.bits.mispredict) {
          bht.updateHistory(io.bht_update.bits.pc, io.bht_update.bits.prediction, io.bht_update.bits.taken)
          flushCount := flushCount + 1.U
          printf(cf"misprediction count = $flushCount\n")
        }
      }.elsewhen (io.bht_update.bits.mispredict) {
        bht.resetHistory(io.bht_update.bits.prediction)
      }
    }
    when (!res.taken && isBranch) { io.resp.bits.taken := false.B }
    io.resp.bits.bht := res
  }

  if (btbParams.nRAS > 0) {
    val ras = new RAS(btbParams.nRAS)
    val doPeek = (idxHit & cfiType.map(_ === CFIType.ret).asUInt).orR
    io.ras_head.valid := !ras.isEmpty
    io.ras_head.bits := ras.peek
    when (!ras.isEmpty && doPeek) {
      io.resp.bits.target := ras.peek
    }
    when (io.ras_update.valid) {
      when (io.ras_update.bits.cfiType === CFIType.call) {
        ras.push(io.ras_update.bits.returnAddr)
      }.elsewhen (io.ras_update.bits.cfiType === CFIType.ret) {
        ras.pop()
      }
    }
  }
  //println(s"total count: $count")
}

