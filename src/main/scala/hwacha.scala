package hwacha

import Chisel._
import uncore._
import Constants._
import rocket.NTLBEntries

case object HwachaNBanks extends Field[Int]
case object HwachaNDTLB extends Field[Int]
case object HwachaNPTLB extends Field[Int]
case object HwachaCacheBlockOffsetBits extends Field[Int]
case object HwachaScalarDataBits extends Field[Int]
case object HwachaNScalarRegs extends Field[Int]
case object HwachaLocalScalarFPU extends Field[Boolean]
case object HwachaNVectorLoadMetaBufferEntries extends Field[Int]

abstract class HwachaModule(clock: Clock = null, _reset: Bool = null) extends Module(clock, _reset) with UsesHwachaParameters
abstract class HwachaBundle extends Bundle with UsesHwachaParameters

abstract trait UsesHwachaParameters extends UsesParameters {
  val nbanks = params(HwachaNBanks)
  val szbanks = log2Up(nbanks)

  val nbdepth = params(HwachaNSRAMRFEntries)
  val szbdepth = log2Up(nbdepth)
  val nbregs = nbdepth * (SZ_DATA / SZ_D)
  val szbregs = log2Up(nbregs)

  val nregs = nbanks * nbregs
  val szregs = log2Up(nregs)
  val szvlen = szregs

  val nvregs = 256
  val szvregs = log2Up(nvregs)
  val npregs = 16
  val szpregs = log2Up(npregs)

  val nsregs = params(HwachaNScalarRegs)
  val local_sfpu = params(HwachaLocalScalarFPU)

  val nseq = 8

  val nDCCOpQ = 2

  val ndtlb = params(HwachaNDTLB)
  val nptlb = params(HwachaNPTLB)
  val confvru = false
  val confprec = false

  // pipeline latencies
  val int_stages = 1
  val imul_stages = 3
  val fma_stages = 3
  val fconv_stages = 3
  val fcmp_stages = 1

  val second_fma_pipe = true
  val second_fconv_pipe = false

  val delay_seq_exp = 2

  val ptr_incr_max =
    nbanks-1 +
    List(int_stages+2, imul_stages+3, fma_stages+4, fconv_stages+2).reduce(scala.math.max(_,_)) +
    delay_seq_exp +
    2 // buffer
  val ptr_incr_sz = log2Up(ptr_incr_max)

  val shift_buf_read = 3
  val shift_buf_write =
    List(imul_stages+3, fma_stages+4, fconv_stages+2).reduce(scala.math.max(_,_)) + 1


  val confvcmdq = new {
    val ncmd = 16
    val nimm = 16
    val nrd = 16
    val ncnt = nbanks
  }

  require(confvcmdq.ncmd >= nbanks)
  require(confvcmdq.nimm >= nbanks)
  require(confvcmdq.nrd >= nbanks)

  val nbrq = 2
  val nbwq = 2

  val confvmu = new {
    val ncmdq = 2
    val naddrq = 2

    val nvvaq = 16
    val nvpaq = 16
    val nvsdq = 16
    val nvldq = 16
    val nvlmb = 16

    val nvvapfq = 8
    val nvpapfq = 8

    val sz_tag = log2Up(nvlmb)
    val sz_addr = math.max(params(PAddrBits), params(VAddrBits))
    val sz_data = 64
  }

  val nvsreq = 128
  val nvlreq = 128
  val nvsdq = nbrq * nbanks

  // D$ tag requirement for hwacha
  require(params(rocket.CoreDCacheReqTagBits) >= confvmu.sz_tag)

}

class Hwacha extends rocket.RoCC with UsesHwachaParameters
{
  import HwachaDecodeTable._
  import Commands._

  val icache = Module(new HwachaFrontend, {case CacheName => "HwI"})
  val dtlb = Module(new rocket.TLB, {case NTLBEntries => ndtlb})
  val ptlb = Module(new rocket.TLB, {case NTLBEntries => nptlb})

  val rocc = Module(new RoCCUnit)
  val scalar = Module(new ScalarUnit)
  val vxu = Module(new VXU)
  val vmu = Module(new VMU)
  val memif = Module(new VMUTileLink)

  // Connect RoccUnit to top level IO
  rocc.io.rocc.cmd <> io.cmd
  rocc.io.rocc.resp <> io.resp
  rocc.io.rocc.busy <> io.busy
  rocc.io.rocc.s <> io.s
  rocc.io.rocc.interrupt <> io.interrupt
  rocc.io.rocc.exception <> io.exception

  //Connect RoccUnit to ScalarUnit
  rocc.io.pending_memop := scalar.io.pending_memop
  rocc.io.pending_seq := vxu.io.pending_seq
  rocc.io.vf_active := scalar.io.vf_active
  rocc.io.cmdq <> scalar.io.cmdq

  //Connect ScalarUnit to Rocket's FPU
  if(local_sfpu){
    val sfpu = Module(new ScalarFPU)
    scalar.io.fpu <> sfpu.io
    io.fpu_req.valid := Bool(false)
  }else{
    scalar.io.fpu.req <> io.fpu_req
    scalar.io.fpu.resp <> io.fpu_resp
  }

  // Connect Scalar to I$
  icache.io.vxu <> scalar.io.imem

  vmu.io.scalar <> scalar.io.dmem
  vmu.io.op <> scalar.io.vmu

  //fake delayed vru_request port (delay vxu req's 2 cycles)
  val delay = 4
  val vru_req = ShiftRegister(scalar.io.imem.req,delay)
  icache.io.vru.req := vru_req
  icache.io.vru.active := ShiftRegister(scalar.io.imem.active,delay)
  icache.io.vru.resp.ready := Bool(true)

  // Connect supporting Hwacha memory modules to external ports
  io.imem <> icache.io.mem
  io.iptw <> icache.io.ptw
  io.dptw <> dtlb.io.ptw
  io.pptw <> ptlb.io.ptw

  vxu.io.cfg <> rocc.io.cfg

  vxu.io.issue <> scalar.io.vxu

  vmu.io.lane <> vxu.io.vmu

  vmu.io.pf.vaq.valid := Bool(false)

  vmu.io.xcpt.prop.vmu.stall := Bool(false)
  vmu.io.xcpt.prop.vmu.drain := Bool(false)
  vmu.io.xcpt.prop.top.stall := Bool(false)

  dtlb.io <> vmu.io.dtlb
  ptlb.io <> vmu.io.ptlb

  memif.io.vmu <> vmu.io.memif 
  io.dmem <> memif.io.dmem
  io.mem.req.valid := Bool(false)
}
