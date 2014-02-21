package hwacha

import Chisel._
import Node._
import Constants._

class Fire extends Module
{
  val io = new Bundle {
    val tvec_valid = new io_vxu_issue_fire().asInput
    val tvec_ready = Bool(INPUT)
    val tvec_dhazard = new io_vxu_issue_reg().asInput
    val tvec_shazard = new io_vxu_issue_fu().asInput
    val tvec_bhazard = new io_vxu_issue_op().asInput
    val tvec_fn = new io_vxu_issue_fn().asInput
    val tvec_regid_imm = new io_vxu_issue_regid_imm().asInput

    val vt_valid = new io_vxu_issue_fire().asInput
    val vt_ready = Bool(INPUT)
    val vt_dhazard = new io_vxu_issue_reg().asInput
    val vt_shazard = new io_vxu_issue_fu().asInput
    val vt_bhazard = new io_vxu_issue_op().asInput
    val vt_fn = new io_vxu_issue_fn().asInput
    val vt_regid_imm = new io_vxu_issue_regid_imm().asInput

    val fire = new io_vxu_issue_fire().asOutput
    val fire_fn = new io_vxu_issue_fn().asOutput
    val fire_regid_imm = new io_vxu_issue_regid_imm().asOutput

    val issueop = new IssueOpIO
  }

  val fire_tvec_viu = ~io.tvec_regid_imm.vd_zero & io.tvec_valid.viu & io.tvec_ready
  val fire_tvec_vld = ~io.tvec_regid_imm.vd_zero & io.tvec_valid.vld & io.tvec_ready
  val fire_tvec_vst = ~io.tvec_regid_imm.vd_zero & io.tvec_valid.vst & io.tvec_ready
  val fire_tvec = fire_tvec_viu | fire_tvec_vld | fire_tvec_vst

  val fire_vt_viu = ~io.vt_regid_imm.vd_zero & io.vt_valid.viu & io.vt_ready
  val fire_vt_vau0 = ~io.vt_regid_imm.vd_zero & io.vt_valid.vau0 & io.vt_ready
  val fire_vt_vau1 = ~io.vt_regid_imm.vd_zero & io.vt_valid.vau1 & io.vt_ready
  val fire_vt_vau2 = ~io.vt_regid_imm.vd_zero & io.vt_valid.vau2 & io.vt_ready
  val fire_vt_amo = ~io.vt_regid_imm.vd_zero & io.vt_valid.amo & io.vt_ready
  val fire_vt_utld = ~io.vt_regid_imm.vd_zero & io.vt_valid.utld & io.vt_ready
  val fire_vt_utst = ~io.vt_regid_imm.vd_zero & io.vt_valid.utst & io.vt_ready

  val fire_vt = fire_vt_viu | fire_vt_vau0 | fire_vt_vau1 | fire_vt_vau2 | fire_vt_amo | fire_vt_utld | fire_vt_utst

  io.fire.viu := fire_tvec_viu | fire_vt_viu
  io.fire.vau0 := fire_vt_vau0
  io.fire.vau1 := fire_vt_vau1
  io.fire.vau2 := fire_vt_vau2
  io.fire.amo := fire_vt_amo
  io.fire.utld := fire_vt_utld
  io.fire.utst := fire_vt_utst
  io.fire.vld := fire_tvec_vld
  io.fire.vst := fire_tvec_vst

  val switch_tvec = io.tvec_valid.toBits.orR

  io.fire_fn.viu := Mux(switch_tvec, io.tvec_fn.viu, io.vt_fn.viu)
  io.fire_fn.vau0 := io.vt_fn.vau0
  io.fire_fn.vau1 := io.vt_fn.vau1
  io.fire_fn.vau2 := io.vt_fn.vau2

  io.fire_regid_imm.vlen := Mux(switch_tvec, io.tvec_regid_imm.vlen, io.vt_regid_imm.vlen)
  io.fire_regid_imm.utidx := Mux(switch_tvec, io.tvec_regid_imm.utidx, io.vt_regid_imm.utidx)
  io.fire_regid_imm.vs_zero := io.vt_regid_imm.vs_zero
  io.fire_regid_imm.vt_zero := Mux(switch_tvec, io.tvec_regid_imm.vt_zero, io.vt_regid_imm.vt_zero)
  io.fire_regid_imm.vr_zero := io.vt_regid_imm.vr_zero
  io.fire_regid_imm.vs_base := io.vt_regid_imm.vs_base
  io.fire_regid_imm.vt_base := io.vt_regid_imm.vt_base
  io.fire_regid_imm.vr_base := io.vt_regid_imm.vr_base
  io.fire_regid_imm.vd_base := Mux(switch_tvec, io.tvec_regid_imm.vd_base, io.vt_regid_imm.vd_base)
  io.fire_regid_imm.vs := io.vt_regid_imm.vs
  io.fire_regid_imm.vt := Mux(switch_tvec, io.tvec_regid_imm.vt, io.vt_regid_imm.vt)
  io.fire_regid_imm.vr := io.vt_regid_imm.vr
  io.fire_regid_imm.vd := Mux(switch_tvec, io.tvec_regid_imm.vd, io.vt_regid_imm.vd)
  io.fire_regid_imm.rtype := Mux(switch_tvec, io.tvec_regid_imm.rtype, io.vt_regid_imm.rtype)
  io.fire_regid_imm.imm := Mux(switch_tvec, io.tvec_regid_imm.imm, io.vt_regid_imm.imm)
  io.fire_regid_imm.imm2 := io.tvec_regid_imm.imm2
  io.fire_regid_imm.cnt_valid := Mux(switch_tvec, io.tvec_regid_imm.cnt_valid, io.vt_regid_imm.cnt_valid)
  io.fire_regid_imm.cnt := Mux(switch_tvec, io.tvec_regid_imm.cnt, io.vt_regid_imm.cnt)
  io.fire_regid_imm.aiw.imm1_rtag := Mux(switch_tvec, io.tvec_regid_imm.aiw.imm1_rtag, io.vt_regid_imm.aiw.imm1_rtag)
  io.fire_regid_imm.aiw.numCnt_rtag := Mux(switch_tvec, io.tvec_regid_imm.aiw.numCnt_rtag, io.vt_regid_imm.aiw.numCnt_rtag)
  io.fire_regid_imm.aiw.cnt_rtag := Mux(switch_tvec, io.tvec_regid_imm.aiw.cnt_rtag, io.vt_regid_imm.aiw.cnt_rtag)
  io.fire_regid_imm.aiw.pc_next := io.vt_regid_imm.aiw.pc_next
  io.fire_regid_imm.aiw.update_imm1 := io.tvec_regid_imm.aiw.update_imm1

  io.issueop.valid := fire_tvec | fire_vt
  io.issueop.bits.active.viu := fire_tvec_viu | fire_vt_viu
  io.issueop.bits.active.vau0 := fire_vt_vau0
  io.issueop.bits.active.vau1 := fire_vt_vau1
  io.issueop.bits.active.vau2 := fire_vt_vau2
  io.issueop.bits.active.amo := fire_vt_amo
  io.issueop.bits.active.utld := fire_vt_utld
  io.issueop.bits.active.utst := fire_vt_utst
  io.issueop.bits.active.vld := fire_tvec_vld
  io.issueop.bits.active.vst := fire_tvec_vst

  io.issueop.bits.fn.viu := Mux(switch_tvec, io.tvec_fn.viu, io.vt_fn.viu)
  io.issueop.bits.fn.vau0 := io.vt_fn.vau0
  io.issueop.bits.fn.vau1 := io.vt_fn.vau1
  io.issueop.bits.fn.vau2 := io.vt_fn.vau2
  io.issueop.bits.fn.vmu := Mux(switch_tvec, io.tvec_fn.vmu, io.vt_fn.vmu)

  val rtype = Mux(switch_tvec, io.tvec_regid_imm.rtype, io.vt_regid_imm.rtype)

  io.issueop.bits.vlen := Mux(switch_tvec, io.tvec_regid_imm.vlen, io.vt_regid_imm.vlen)
  io.issueop.bits.utidx := Mux(switch_tvec, io.tvec_regid_imm.utidx, io.vt_regid_imm.utidx)
  io.issueop.bits.reg.vs.zero := io.vt_regid_imm.vs_zero
  io.issueop.bits.reg.vt.zero := Mux(switch_tvec, io.tvec_regid_imm.vt_zero, io.vt_regid_imm.vt_zero)
  io.issueop.bits.reg.vr.zero := io.vt_regid_imm.vr_zero
  io.issueop.bits.reg.vd.zero := Bool(false)
  io.issueop.bits.reg.vs.float := rtype(3)
  io.issueop.bits.reg.vt.float := rtype(2)
  io.issueop.bits.reg.vr.float := rtype(1)
  io.issueop.bits.reg.vd.float := rtype(0)
  io.issueop.bits.reg.vs.id := io.vt_regid_imm.vs
  io.issueop.bits.reg.vt.id := Mux(switch_tvec, io.tvec_regid_imm.vt, io.vt_regid_imm.vt)
  io.issueop.bits.reg.vr.id := io.vt_regid_imm.vr
  io.issueop.bits.reg.vd.id := Mux(switch_tvec, io.tvec_regid_imm.vd, io.vt_regid_imm.vd)
  io.issueop.bits.imm.imm := Mux(switch_tvec, io.tvec_regid_imm.imm, io.vt_regid_imm.imm)
  io.issueop.bits.imm.stride := io.tvec_regid_imm.imm2
  io.issueop.bits.aiw.active.imm1 := io.tvec_regid_imm.aiw.update_imm1
  io.issueop.bits.aiw.active.cnt := Bool(true)
  io.issueop.bits.aiw.imm1.rtag := Mux(switch_tvec, io.tvec_regid_imm.aiw.imm1_rtag, io.vt_regid_imm.aiw.imm1_rtag)
  io.issueop.bits.aiw.imm1.pc_next := io.vt_regid_imm.aiw.pc_next
  io.issueop.bits.aiw.cnt.rtag := Mux(switch_tvec, io.tvec_regid_imm.aiw.cnt_rtag, io.vt_regid_imm.aiw.cnt_rtag)
  io.issueop.bits.aiw.cnt.utidx := Mux(switch_tvec, io.tvec_regid_imm.cnt, io.vt_regid_imm.cnt)
  io.issueop.bits.aiw.numcnt.rtag := Mux(switch_tvec, io.tvec_regid_imm.aiw.numCnt_rtag, io.vt_regid_imm.aiw.numCnt_rtag)
}
