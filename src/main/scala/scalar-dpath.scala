package hwacha

import Chisel._
import Node._
import Constants._
import HardFloatHelper._

class Write extends Bundle
{
  val rd  = Bits(width = 8)
  val imm = Bits(width = SZ_ADDR)
}

class ScalarDpath extends HwachaModule
{
  val io = new Bundle {
    val ctrl = new CtrlDpathIO().flip
    val fpu = new Bundle {
      val req = Decoupled(new FPInput())
      val resp = Decoupled(new FPResult()).flip
    }

    val vmu = new ScalarMemIO
    val seqop = new SequencerOpIO

    val imem = new FrontendIO

    val respq = new RESPQIO()
  }


  // execute definitions
  val ex_reg_pc = Reg(UInt())
  val ex_reg_inst = Reg(Bits())
  val ex_reg_kill = Reg(Bool())
  val ex_reg_srs_bypass = Vec.fill(3)(Reg(Bool()))
  val ex_reg_srs_lsb = Vec.fill(3)(Reg(Bits()))
  val ex_reg_srs_msb = Vec.fill(3)(Reg(Bits()))
  val ex_reg_ars = Vec.fill(2)(Reg(Bits()))

  val pending_mem_reg = Reg(UInt(width=log2Up(nsregs)))
  val pending_fpu = Reg(init=Bool(false))
  val pending_fpu_reg = Reg(UInt(width=log2Up(nsregs)))
  val pending_fpu_typ = Reg(init=Bits(width=SZ_PREC))//only used in dpath
  io.ctrl.pending_mem_reg := pending_mem_reg
  io.ctrl.pending_fpu_reg := pending_fpu_reg
  io.ctrl.pending_fpu := pending_fpu

  // writeback definitions
  val wb_reg_pc = Reg(UInt())
  val wb_reg_inst = Reg(Bits())
  val wb_reg_wdata = Reg(Bits())
  val wb_reg_kill = Reg(Bool())
  val wb_wdata = Bits()

  class SRegFile {
    private val rf = Mem(UInt(width = 64), nsregs-1)
    private val reads = collection.mutable.ArrayBuffer[(UInt,UInt)]()
    private var canRead = true
    def read(addr: UInt) = {
      require(canRead)
      reads += addr -> UInt()
      reads.last._2 := rf(~addr)
      reads.last._2
    }
    def write(addr: UInt, data: UInt) = {
      canRead = false
      when (addr != UInt(0)) {
        rf(~addr) := data
        for ((raddr, rdata) <- reads)
          when (addr === raddr) { rdata := data }
      }
    }
  }
  val srf = new SRegFile //doesn't have vs0
  val arf = Mem(UInt(width = 64), 32)

  io.respq.value.valid := Bool(false)
  io.respq.value.bits  := UInt(0)

  val id_inst = io.imem.resp.bits.data(0).toBits; require(params(rocket.FetchWidth) == 1)
  val id_pc   = io.imem.resp.bits.pc
  //register reads
  val id_sraddr = Vec(id_inst(31,24), id_inst(40,33), id_inst(48,41))
  val id_araddr = Vec(id_inst(28,24), id_inst(37,33))
  val id_sreads = id_sraddr.map(srf.read _ )
  val id_areads = id_araddr.map(arf(_))

  // immediate generation
  def imm(sel: Bits, inst: Bits) = {
    val sign = inst(63).toSInt
    val b30_3 = inst(62,35)
    val b2_0 = Mux(sel === IMM_I, inst(34,32), Bits(0))

    Cat(sign, b30_3, b2_0).toSInt
  }

  io.ctrl.inst := id_inst
  io.ctrl.ex_waddr := ex_reg_inst(23,16)
  io.ctrl.wb_waddr := wb_reg_inst(23,16)
  io.ctrl.ex_inst := ex_reg_inst
  io.ctrl.wb_inst := wb_reg_inst

  //execute stage
  ex_reg_kill := io.ctrl.killd
  when(!io.ctrl.killd)
  {
    ex_reg_pc := id_pc
    ex_reg_inst := id_inst
    ex_reg_srs_bypass := io.ctrl.bypass
    for (i <- 0 until id_sreads.size) {
      when (io.ctrl.ren(i)) {
        ex_reg_srs_lsb(i) := id_sreads(i)(SZ_BYP-1,0)
        when (!io.ctrl.bypass(i)) {
          ex_reg_srs_msb(i) := id_sreads(i) >> UInt(SZ_BYP)
        }
      }
      when (io.ctrl.bypass(i)) { ex_reg_srs_lsb(i) := io.ctrl.bypass_src(i) }
    }
    for( i <- 0 until id_areads.size){
      when(io.ctrl.ren(i)){
        ex_reg_ars(i) := id_areads(i)
      }
    }
  }

  val bypass = Vec.fill(NBYP)(Bits())
  bypass(BYP_0) := Bits(0)
  bypass(BYP_EX) := wb_reg_wdata

  val ex_srs = for (i <- 0 until id_sreads.size)
    yield Mux(ex_reg_srs_bypass(i), bypass(ex_reg_srs_lsb(i)), Cat(ex_reg_srs_msb(i), ex_reg_srs_lsb(i)))

  val ex_imm = imm(io.ctrl.ex_ctrl.sel_imm, ex_reg_inst)
  val ex_op1 = MuxLookup(io.ctrl.ex_ctrl.sel_alu1, SInt(0), Seq(
    A1_RS1 -> ex_srs(0).toSInt,
    A1_PC -> ex_reg_pc.toSInt))
  val ex_op2 = MuxLookup(io.ctrl.ex_ctrl.sel_alu2, SInt(0), Seq(
    A2_RS2 -> ex_srs(1).toSInt,
    A2_IMM -> ex_imm))

  val alu = Module(new rocket.ALU)
  alu.io.dw := io.ctrl.ex_ctrl.alu_dw
  alu.io.fn := io.ctrl.ex_ctrl.alu_fn
  alu.io.in2 := ex_op2.toUInt
  alu.io.in1 := ex_op1

  //fpu ex
  //io.fpu.req.bits.rm := ex_reg_inst(52,50)
  io.fpu.req.bits.rm := Bits(0)
  io.fpu.req.bits.typ := ex_reg_inst(54,54)
  io.fpu.req.bits.in1 := 
     Mux(io.fpu.req.bits.typ === UInt(0), 
     Cat(SInt(-1,32),recode_sp(ex_srs(0))), recode_dp(ex_srs(0)))
  io.fpu.req.bits.in2 := 
     Mux(io.fpu.req.bits.typ === UInt(0), 
     Cat(SInt(-1,32),recode_sp(ex_srs(1))), recode_dp(ex_srs(1)))
  io.fpu.req.bits.in3 := 
     Mux(io.fpu.req.bits.typ === UInt(0), 
     Cat(SInt(-1,32),recode_sp(ex_srs(2))), recode_dp(ex_srs(2)))

  when(io.ctrl.fire_fpu) { 
    pending_fpu := Bool(true)
    pending_fpu_typ := io.fpu.req.bits.typ
    pending_fpu_reg := io.ctrl.ex_waddr
  }
  when(io.fpu.resp.fire()){
    pending_fpu := Bool(false)
  }

  //fpu resp
  val unrec_s = ieee_sp(io.fpu.resp.bits.data)
  val unrec_d = ieee_dp(io.fpu.resp.bits.data)
  val unrec_fpu_resp = Mux(pending_fpu_typ === UInt(0),
               Cat(Fill(32,unrec_s(31)),unrec_s), unrec_d)

  //give fixed priority to mem -> fpu -> alu
  io.fpu.resp.ready := Bool(true)
  when(io.vmu.loadData.valid) { io.fpu.resp.ready := Bool(false) }

  //Memory requests - COLIN FIXME: check for criticla path (need reg?)
  io.vmu.op.bits.addr := alu.io.out
  io.vmu.op.bits.data := ex_srs(1)

  when(io.vmu.op.fire()) {
    pending_mem_reg := io.ctrl.ex_waddr
  }

  //Writeback stage
  when(!ex_reg_kill) {
    wb_reg_pc := ex_reg_pc
    wb_reg_inst := ex_reg_inst
    wb_reg_wdata := alu.io.out
  }

  assert(!(io.ctrl.wb_wen && io.ctrl.swrite.valid), "Cannot write vmss and scalar dest")
  assert(!(io.ctrl.wb_wen && io.ctrl.awrite.valid), "Cannot write vmsa and scalar dest")

  val wb_waddr = Mux(io.vmu.loadData.valid, pending_mem_reg,
                 Mux(io.fpu.resp.valid, pending_fpu_reg,
                 wb_reg_inst(23,16)))
  wb_wdata := Mux(io.vmu.loadData.valid, io.vmu.loadData.bits,
              Mux(io.fpu.resp.valid, unrec_fpu_resp,
              wb_reg_wdata))
  when(io.ctrl.wb_wen) { srf.write(wb_waddr, wb_wdata) }

  when(io.ctrl.swrite.valid) { srf.write(io.ctrl.swrite.bits.rd, io.ctrl.swrite.bits.imm) }

  when(io.ctrl.awrite.valid) { arf(io.ctrl.awrite.bits.rd) := io.ctrl.awrite.bits.imm }

  //hookup result to rocc resp
  when(io.ctrl.swrite.valid) { 
    io.respq.value.valid := Bool(true)
    io.respq.value.bits := wb_reg_wdata
  }
  when(io.ctrl.awrite.valid) { 
    io.respq.value.valid := Bool(true)
    io.respq.value.bits := wb_reg_wdata
  }

  // fake VU hookup: start from register to avoid critical path issues
  io.seqop.valid := !io.ctrl.killd
  io.seqop.bits.inst := Cat(id_inst(31,0), id_inst(63,32))

  when(io.ctrl.swrite.valid) {
    printf("H: SW[r%d=%x][%d]\n",
         io.ctrl.swrite.bits.rd, io.ctrl.swrite.bits.imm, io.ctrl.swrite.valid)
  }
  when(io.ctrl.awrite.valid) {
    printf("H: AW[r%d=%x][%d]\n",
         io.ctrl.awrite.bits.rd, io.ctrl.awrite.bits.imm, io.ctrl.awrite.valid)
  }
  when(io.ctrl.wb_vf_active) {
    printf("H: [%x] pc=[%x] SW[r%d=%x][%d] SR[r%d=%x] SR[r%d=%x] inst=[%x] DASM(%x)\n",
         io.ctrl.retire, wb_reg_pc, 
         Mux(io.ctrl.wb_wen, wb_waddr, UInt(0)), wb_wdata, io.ctrl.wb_wen,
         wb_reg_inst(31,24), Mux(io.ctrl.wb_ctrl.vri === RA,
                                 Reg(next=Reg(next=ex_reg_ars(0))),
                                 Reg(next=Reg(next=ex_srs(0)))),
         wb_reg_inst(40,33), Mux(io.ctrl.wb_ctrl.vsi === RA,
                                 Reg(next=Reg(next=ex_reg_ars(1))),
                                 Reg(next=Reg(next=ex_srs(1)))),
         wb_reg_inst, wb_reg_inst)
  }
}
