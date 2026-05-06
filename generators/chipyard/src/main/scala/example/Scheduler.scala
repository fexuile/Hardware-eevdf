package chipyard.example

import chisel3._
import chisel3.util._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS}
import org.chipsalliance.cde.config.{Config, Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.tilelink._

//--------------------------------------------------------------------------
// Parameters
//--------------------------------------------------------------------------
case class EEVDFSchedulerParams(
  address: BigInt = 0x10051000L,
  maxTasks:   Int = 64,
  vrWidth:    Int = 64,
  dlWidth:    Int = 64,
  wtWidth:    Int = 20,
  baseWeight: Int = 1024)
{
  require(maxTasks > 0 && (maxTasks & (maxTasks - 1)) == 0,
    s"maxTasks must be power of 2, got $maxTasks")
  val idWidth = log2Ceil(maxTasks)
}

case object EEVDFSchedulerKey extends Field[Option[EEVDFSchedulerParams]](None)

//--------------------------------------------------------------------------
// Linux nice-to-weight table (scaled by 1024)
// nice -20 = index 0 (highest weight), nice +19 = index 39 (lowest)
// Stored as plain Ints; converted to hardware Vec inside a Module.
//--------------------------------------------------------------------------
object NiceTable {
  val MIN_NICE = -20
  val MAX_NICE =  19

  private val weightInts = Seq(
    88761, 71755, 56483, 46273, 36291,
    29154, 23254, 18705, 14949, 11916,
     9548,  7620,  6100,  4904,  3906,
     3121,  2501,  1991,  1586,  1277,
     1024,   820,   655,   526,   423,
      335,   272,   215,   172,   137,
      110,    87,    70,    56,    45,
       36,    29,    23,    18,    15
  )

  // Call this from inside a Module to get hardware LUT
  def lut: Vec[UInt] = VecInit(weightInts.map(_.U(17.W)))

  // 256-entry LUT: maps 8-bit nice value directly to weight.
  // Clamping to [-20, 19] is baked into the LUT values.
  // This avoids Chisel/CIRCT width inference issues with signed mux.
  def fullLut: Vec[UInt] = {
    val entries = (0 until 256).map { i =>
      val signedNice = i.toByte  // -128 to 127
      val clamped = if (signedNice < MIN_NICE) MIN_NICE
                     else if (signedNice > MAX_NICE) MAX_NICE
                     else signedNice
      weightInts(clamped - MIN_NICE).U(17.W)
    }
    VecInit(entries)
  }

  def toWeight(nice: SInt, lut: Vec[UInt]): UInt = {
    // Use full 8-bit unsigned interpretation as LUT index.
    // Clamping is baked into lut values, so no hardware clamp needed.
    lut(nice.asUInt)
  }
}

//--------------------------------------------------------------------------
// Candidate: represents one entity in the reduction tree
//--------------------------------------------------------------------------
class SchedCandidate(params: EEVDFSchedulerParams) extends Bundle {
  val valid    = Bool()
  val taskId   = UInt(params.idWidth.W)
  val deadline = UInt(params.dlWidth.W)
  val vruntime = UInt(params.vrWidth.W)
}

object SchedCandidate {
  def invalid(params: EEVDFSchedulerParams): SchedCandidate = {
    val c = Wire(new SchedCandidate(params))
    c.valid    := false.B
    c.taskId   := 0.U
    c.deadline := ~0.U(params.dlWidth.W)
    c.vruntime := ~0.U(params.vrWidth.W)
    c
  }
}

//--------------------------------------------------------------------------
// EEVDF Scheduler Core
//--------------------------------------------------------------------------
class EEVDFSchedulerCore(params: EEVDFSchedulerParams) extends Module {
  val io = IO(new Bundle {
    val cmdValid    = Input(Bool())
    val cmdOp       = Input(UInt(3.W))              // 0=nop 1=enq 2=deq 3=renice 4=tick 5=clear
    val cmdTaskId   = Input(UInt(params.idWidth.W))
    val cmdNice     = Input(SInt(8.W))
    val cmdVruntime = Input(UInt(params.vrWidth.W))
    val cmdTickDelta = Input(UInt(32.W))

    val bestValid    = Output(Bool())
    val bestTaskId   = Output(UInt(params.idWidth.W))
    val bestDeadline = Output(UInt(params.dlWidth.W))
    val needPreempt  = Output(Bool())
    val nrRunning    = Output(UInt((params.idWidth + 1).W))
    val virtualTime  = Output(UInt(params.vrWidth.W))
    val busy         = Output(Bool())
  })

  // Nice LUT — instantiated once in the Module
  val niceLut = NiceTable.fullLut

  // Per-entity state
  val runnable     = RegInit(VecInit(Seq.fill(params.maxTasks)(false.B)))
  val vruntime     = RegInit(VecInit(Seq.fill(params.maxTasks)(0.U(params.vrWidth.W))))
  val deadline     = RegInit(VecInit(Seq.fill(params.maxTasks)(0.U(params.dlWidth.W))))
  val weight       = RegInit(VecInit(Seq.fill(params.maxTasks)(0.U(params.wtWidth.W))))
  val requestTime  = RegInit(VecInit(Seq.fill(params.maxTasks)(0.U(32.W))))

  // Global state
  val virtualTimeReg  = RegInit(0.U(params.vrWidth.W))
  val curTaskId       = RegInit(0.U(params.idWidth.W))
  val curTaskValid    = RegInit(false.B)
  val busyReg         = RegInit(false.B)

  // Combinational nice → weight → request_time for the current command
  val cmdWeight      = NiceTable.toWeight(io.cmdNice, niceLut)
  val cmdRequestTime = (6000.U(32.W) * params.baseWeight.U) /
                        Mux(cmdWeight === 0.U, 1.U, cmdWeight)

  //----------------------------------------------------------------------
  // Reduction tree: find minimum deadline among eligible entities
  //----------------------------------------------------------------------
  def buildCandidates(
    run:  Vec[Bool],
    vr:   Vec[UInt],
    dl:   Vec[UInt],
    vtim: UInt
  ): IndexedSeq[SchedCandidate] = {
    (0 until params.maxTasks).map { i =>
      val c = Wire(new SchedCandidate(params))
      c.valid    := run(i) && (vr(i) <= vtim)
      c.taskId   := i.U
      c.deadline := dl(i)
      c.vruntime := vr(i)
      c
    }
  }

  def pickBest(cs: IndexedSeq[SchedCandidate]): SchedCandidate = {
    if (cs.length == 1) {
      cs.head
    } else {
      val pairs = cs.grouped(2).map { pair =>
        val a = pair(0)
        val b = pair.lift(1).getOrElse(SchedCandidate.invalid(params))
        val winner = Wire(new SchedCandidate(params))
        val aWins = a.valid && (
          !b.valid ||
          a.deadline < b.deadline ||
          (a.deadline === b.deadline && a.vruntime < b.vruntime) ||
          (a.deadline === b.deadline && a.vruntime === b.vruntime && a.taskId < b.taskId))
        when (aWins) { winner := a } .otherwise { winner := b }
        winner
      }.toIndexedSeq
      pickBest(pairs)
    }
  }

  // Current-state best
  val curCandidates = buildCandidates(runnable, vruntime, deadline, virtualTimeReg)
  val curBest = pickBest(curCandidates)

  //----------------------------------------------------------------------
  // Next-state wires
  //----------------------------------------------------------------------
  val nextRunnable    = Wire(Vec(params.maxTasks, Bool()))
  val nextVruntime    = Wire(Vec(params.maxTasks, UInt(params.vrWidth.W)))
  val nextDeadline    = Wire(Vec(params.maxTasks, UInt(params.dlWidth.W)))
  val nextWeight      = Wire(Vec(params.maxTasks, UInt(params.wtWidth.W)))
  val nextRequestTime = Wire(Vec(params.maxTasks, UInt(32.W)))
  val nextVirtualTime = Wire(UInt(params.vrWidth.W))
  val nextCurTaskId   = Wire(UInt(params.idWidth.W))
  val nextCurValid    = Wire(Bool())

  for (i <- 0 until params.maxTasks) {
    nextRunnable(i)     := runnable(i)
    nextVruntime(i)     := vruntime(i)
    nextDeadline(i)     := deadline(i)
    nextWeight(i)       := weight(i)
    nextRequestTime(i)  := requestTime(i)
  }
  nextVirtualTime := virtualTimeReg
  nextCurTaskId   := curTaskId
  nextCurValid    := curTaskValid

  when (io.cmdValid) {
    busyReg := true.B

    switch (io.cmdOp) {
      //---- enqueue --------------------------------------------------
      is (1.U) {
        val startVr = Mux(io.cmdVruntime < virtualTimeReg,
                          virtualTimeReg, io.cmdVruntime)
        nextRunnable(io.cmdTaskId)     := true.B
        nextVruntime(io.cmdTaskId)     := startVr
        nextDeadline(io.cmdTaskId)     := startVr + cmdRequestTime
        nextWeight(io.cmdTaskId)       := cmdWeight
        nextRequestTime(io.cmdTaskId)  := cmdRequestTime
      }

      //---- dequeue --------------------------------------------------
      is (2.U) {
        nextRunnable(io.cmdTaskId)     := false.B
        nextWeight(io.cmdTaskId)       := 0.U
        nextRequestTime(io.cmdTaskId)  := 0.U
        when (curTaskId === io.cmdTaskId && curTaskValid) {
          nextCurValid := false.B
        }
      }

      //---- renice ---------------------------------------------------
      is (3.U) {
        when (runnable(io.cmdTaskId)) {
          nextWeight(io.cmdTaskId)      := cmdWeight
          nextRequestTime(io.cmdTaskId) := cmdRequestTime
          nextDeadline(io.cmdTaskId)    := vruntime(io.cmdTaskId) + cmdRequestTime
        }
      }

      //---- tick -----------------------------------------------------
      is (4.U) {
        // Advance virtual time
        val sumW = (0 until params.maxTasks).foldLeft(0.U(32.W)) { (acc, i) =>
          acc + Mux(nextRunnable(i), nextWeight(i), 0.U)
        }
        val sumWnz = Mux(sumW === 0.U, 1.U, sumW)
        val vDelta = (io.cmdTickDelta * params.baseWeight.U) / sumWnz
        nextVirtualTime := virtualTimeReg + vDelta

        // Advance the ticked entity's vruntime & deadline
        val taskW = Mux(nextWeight(io.cmdTaskId) === 0.U,
                        1.U, nextWeight(io.cmdTaskId))
        val vrDelta = (io.cmdTickDelta * params.baseWeight.U) / taskW
        val newVr = vruntime(io.cmdTaskId) + vrDelta
        nextVruntime(io.cmdTaskId) := newVr
        nextDeadline(io.cmdTaskId) := newVr + nextRequestTime(io.cmdTaskId)

        nextCurTaskId := io.cmdTaskId
        nextCurValid  := true.B
      }

      //---- clear_all ------------------------------------------------
      is (5.U) {
        for (i <- 0 until params.maxTasks) {
          nextRunnable(i)     := false.B
          nextVruntime(i)     := 0.U
          nextDeadline(i)     := 0.U
          nextWeight(i)       := 0.U
          nextRequestTime(i)  := 0.U
        }
        nextVirtualTime := 0.U
        nextCurTaskId   := 0.U
        nextCurValid    := false.B
      }
    }
  } .otherwise {
    busyReg := false.B
  }

  //----------------------------------------------------------------------
  // Next-state best
  //----------------------------------------------------------------------
  val nextCandidates = buildCandidates(nextRunnable, nextVruntime,
                                       nextDeadline, nextVirtualTime)
  val nextBest = pickBest(nextCandidates)

  val needPreemptNext = nextCurValid && nextBest.valid &&
                         (nextBest.taskId =/= nextCurTaskId)

  //----------------------------------------------------------------------
  // State update
  //----------------------------------------------------------------------
  for (i <- 0 until params.maxTasks) {
    runnable(i)     := nextRunnable(i)
    vruntime(i)     := nextVruntime(i)
    deadline(i)     := nextDeadline(i)
    weight(i)       := nextWeight(i)
    requestTime(i)  := nextRequestTime(i)
  }
  virtualTimeReg := nextVirtualTime
  when (io.cmdValid) {
    curTaskId    := nextCurTaskId
    curTaskValid := nextCurValid
  }

  //----------------------------------------------------------------------
  // Outputs
  //----------------------------------------------------------------------
  io.bestValid    := curBest.valid
  io.bestTaskId   := curBest.taskId
  io.bestDeadline := curBest.deadline
  io.needPreempt  := needPreemptNext
  io.nrRunning    := PopCount(nextRunnable)
  io.virtualTime  := nextVirtualTime
  io.busy         := busyReg
}

//--------------------------------------------------------------------------
// TileLink MMIO wrapper
//
// Register map (all base + offset):
//   0x00  STATUS (r, 64b)  packed status word (see below)
//   0x08  CMD    (w, 64b)  {taskId[8:3], opcode[2:0]}
//   0x10  VRUNTIME_LO (w)  low  32 bits of vruntime for enqueue
//   0x18  VRUNTIME_HI (w)  high 32 bits
//   0x20  NICE          (w) nice value (-20..+19) for enqueue / renice
//   0x28  TICK_DELTA    (w) virtual-time delta for tick
//
// STATUS layout (64 bits, read-only):
//   [0]      : busy
//   [1]      : bestValid
//   [2]      : needPreempt
//   [8:3]    : bestTaskId (6 bits for 64 tasks)
//   [15:9]   : nrRunning   (7 bits, up to 64)
//   [47:16]  : virtualTime low 32 bits
//   [63:48]  : reserved
//--------------------------------------------------------------------------
class EEVDFSchedulerTL(params: EEVDFSchedulerParams, beatBytes: Int)
                      (implicit p: Parameters)
  extends ClockSinkDomain(ClockSinkParameters())(p) {

  val device = new SimpleDevice("eevdf-scheduler",
                 Seq("ucbbar,eevdf-scheduler"))
  val node = TLRegisterNode(
    address   = Seq(AddressSet(params.address, 4096 - 1)),
    device    = device,
    beatBytes = beatBytes)

  override lazy val module = new SchedModuleImpl
  class SchedModuleImpl extends Impl {
    withClockAndReset(clock, reset) {
      val core = Module(new EEVDFSchedulerCore(params))

      // Staging registers
      val vrLo  = RegInit(0.U(32.W))
      val vrHi  = RegInit(0.U(32.W))
      val nice  = RegInit(0.U(8.W))
      val delta = RegInit(0.U(32.W))

      core.io.cmdVruntime := Cat(vrHi, vrLo)
      core.io.cmdNice     := nice.asSInt.asSInt
      core.io.cmdTickDelta := delta

      def doCmd(valid: Bool, bits: UInt): Bool = {
        core.io.cmdValid  := valid
        core.io.cmdOp     := bits(2, 0)
        core.io.cmdTaskId := bits(8, 3)
        true.B
      }

      // STATUS: [63:48]=rsvd [47:16]=vtime [15:9]=nrRunning [8:3]=bestTaskId [2]=preempt [1]=valid [0]=busy
      val status = Cat(
        0.U(16.W),                     // [63:48] reserved
        core.io.virtualTime(31, 0),    // [47:16] virtual time (low 32 bits)
        core.io.nrRunning,             // [15:9]  nr running
        core.io.bestTaskId,            // [8:3]   best task id
        core.io.needPreempt,           // [2]     need preempt
        core.io.bestValid,             // [1]     best valid
        core.io.busy                   // [0]     busy
      )

      node.regmap(
        0x00 -> Seq(RegField.r(64, status)),
        0x08 -> Seq(RegField.w(64, doCmd(_, _))),
        0x10 -> Seq(RegField(32, vrLo)),
        0x18 -> Seq(RegField(32, vrHi)),
        0x20 -> Seq(RegField(8,  nice)),
        0x28 -> Seq(RegField(32, delta))
      )
    }
  }
}

//--------------------------------------------------------------------------
// Cake pattern trait — mixed into DigitalTop
//--------------------------------------------------------------------------
trait CanHavePeripheryEEVDFScheduler { this: BaseSubsystem =>
  private val pbus = locateTLBusWrapper(PBUS)

  p(EEVDFSchedulerKey).foreach { params =>
    val sched = LazyModule(new EEVDFSchedulerTL(params, pbus.beatBytes)(p))
    sched.clockNode := pbus.fixedClockNode
    pbus.coupleTo("eevdfScheduler") {
      sched.node := TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
    }
  }
}

//--------------------------------------------------------------------------
// Config fragment
//--------------------------------------------------------------------------
class WithEEVDFScheduler(
  address:  BigInt = 0x10051000L,
  maxTasks: Int   = 64) extends Config((site, here, up) => {
  case EEVDFSchedulerKey => Some(EEVDFSchedulerParams(
    address  = address,
    maxTasks = maxTasks))
})
