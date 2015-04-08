package hwacha

import Chisel._
import Constants._
import uncore.constants.MemoryOpConstants._

class BRQLookAheadIO extends HwachaBundle with LookAheadIO {
  val mask = Bits(OUTPUT, nbanks)
}

class VSU extends HwachaModule with LaneParameters with VMUParameters {
  val io = new Bundle {
    val op = Decoupled(new DCCMemOp).flip
    val xcpt = new XCPTIO().flip

    val pred = Decoupled(Bits(width = nPredSet)).flip
    val brqs = Vec.fill(nbanks)(new BRQIO).flip
    val la = new BRQLookAheadIO().flip

    val vsdq = new VSDQIO
  }

  private val lgbank = log2Up(nbanks)
  private val nTotalSlices = nSlices * nbanks
  private val sz_beat = math.max(1, nPredSet / nSlices) * lgbank

  val mt_fn = Reg(Bits(width = MT_SZ))
  val mt = DecodedMemType(mt_fn)
  val mtsel = Seq(mt.d, mt.w, mt.h, mt.b)

  val beat = Reg(UInt(width = sz_beat))
  val beat_mid = mt.b && !beat(0) // FIXME: parameterize
  val beat_cnt = Seq(SZ_D, SZ_W, SZ_H, SZ_B).map(sz =>
    // Maximum number of elements per beat
    math.min(tlDataBits / sz, nTotalSlices))

  val vlen = Reg(UInt(width = SZ_VLEN))
  val ecnt = Mux1H(mtsel, beat_cnt.map(i => UInt(i)))
  val vlen_next = vlen.zext - ecnt.zext
  val last = (vlen_next <= SInt(0))
  val next = Bool()

  io.op.ready := Bool(false)

  val s_idle :: s_busy :: Nil = Enum(UInt(), 2)
  val state = Reg(init = s_idle)

  switch (state) {
    is (s_idle) {
      io.op.ready := Bool(true)
      when (io.op.valid) {
        state := s_busy
        vlen := io.op.bits.vlen
        mt_fn := io.op.bits.fn.mt
      }
    }

    is (s_busy) {
      when (next) {
        vlen := vlen_next
        beat := beat + UInt(1)
        when (last) {
          state := s_idle
        }
      }
    }
  }

  //--------------------------------------------------------------------\\
  // predication / masking
  //--------------------------------------------------------------------\\

  val brqs_cnt = Seq(SZ_D, SZ_W, SZ_H, SZ_B).map { sz =>
    // Yields (n, m) where
    // n: number of active BRQs per VSDQ subblock
    // m: number of complete subblocks per BRQ entry
    val brqDataBits = sz * nSlices
    if (tlDataBits >= brqDataBits) {
      val n = math.min(nbanks, tlDataBits / brqDataBits)
      require(isPow2(n))
      (n, 1)
    } else {
      val m = brqDataBits / tlDataBits
      require(isPow2(m))
      (1, m)
    }
  }
  val brqs_mask = (0 until nbanks).map(i =>
    Mux1H(mtsel, brqs_cnt.map { case (n, m) =>
      if (n == nbanks) Bool(true) else {
        val lgn = log2Ceil(n)
        val lgm = log2Ceil(m)
        (beat(lgbank-lgn-1+lgm, lgm) === UInt(i >> lgn))
      }
    }))

  val pred_head = if (nPredSet > nTotalSlices) {
    require(nPredSet % nTotalSlices == 0)
    val pred_shift = Mux1H(mtsel, brqs_cnt.map { case (n, m) =>
      UInt((lgbank - log2Ceil(n)) + log2Ceil(m))
    })
    val pred_index = beat >> pred_shift
    Vec((0 until nPredSet by nTotalSlices).map(i =>
      io.pred.bits(i+nTotalSlices-1, i)))(pred_index)
  } else {
    require(nTotalSlices % nPredSet == 0)
    Cat(Seq.fill(nTotalSlices / nPredSet)(io.pred.bits))
  }

  val pred_split = (0 until nTotalSlices).map(pred_head(_))
  val pred_slice = Vec(pred_split.grouped(nSlices).map(x => x.reduce(_ || _)).toSeq)

  val pred_brqs = if (brqs_cnt.indexWhere(_._2 > 1) < 0)
      pred_slice // optimization for uniform (m == 1)
    else
      Mux1H(mtsel, brqs_cnt.map { case (n, m) =>
        if (m == 1) pred_slice else {
          val pred_blks = pred_split.grouped(nSlices / m).map(x => x.reduce(_ || _))
          val pred_ways = pred_blks.toSeq.zipWithIndex.groupBy(_._2 % m)
          val pred_group = (0 until m).map(i => Vec(pred_ways(i).map(_._1)))
          Vec(pred_group)(beat/*(log2Ceil(m)-1, 0)*/)
        }
      })

  //--------------------------------------------------------------------\\
  // bank read queues
  //--------------------------------------------------------------------\\

  private val sz_pending = log2Down(nbrq+1) + 1
  val pending = Reg(init = UInt(0, sz_pending))
  val pending_add = Mux(io.la.reserve, UInt(1), UInt(0))
  val pending_sub = Mux(io.vsdq.fire(), Mux(mt.b, UInt(2), UInt(1)), UInt(0))
  pending := pending + pending_add - pending_sub

  val brqs = io.brqs.zipWithIndex.map { case (enq, i) =>
    val brq = Module(new Queue(new BRQEntry, nbrq))
    val slacntr = Module(new LookAheadCounter(nbrq, nbrq))
    val en = io.la.mask(i)
    brq.io.enq <> enq
    slacntr.io.inc.cnt := UInt(1)
    slacntr.io.inc.update := brq.io.deq.fire()
    slacntr.io.dec.cnt := UInt(1)
    slacntr.io.dec.reserve := io.la.reserve && en
    (brq.io.deq, slacntr.io.dec.available && en)
  }
  io.la.available := brqs.map(_._2).reduce(_ && _)
  val brqs_deq = Vec(brqs.map(_._1))

  val brqs_en = pred_brqs.zip(brqs_mask).map { case (pred, mask) => pred && mask }
  val brqs_valid = brqs_deq.zip(brqs_en).map { case (brq, en) => !en || brq.valid }
  val vsdq_en = brqs_en.reduce(_ || _) && (!beat_mid || last)
  val vsdq_ready = !vsdq_en || io.vsdq.ready

  private def fire(exclude: Bool, include: Bool*) = {
    val rvs = brqs_valid ++ Seq(io.pred.valid, vsdq_ready)
    (rvs.filter(_ ne exclude) ++ include).reduce(_ && _)
  }
  next := fire(null)

  brqs_deq.zip(brqs_valid.zip(brqs_en)).foreach { case (brq, (valid, en)) =>
    brq.ready := fire(valid, en) // FIXME: Delay dequeuing for (m > 1)
  }

  val pred_dequeue = Mux1H(mtsel, beat_cnt.map { cnt =>
    val n = nPredSet / cnt
    if (n > 1) (beat(log2Ceil(n)-1, 0) === UInt(n-1)) else Bool(true)
  }) || last
  io.pred.ready := fire(io.pred.valid, pred_dequeue)

  io.vsdq.valid := fire(vsdq_ready, vsdq_en) ||
    (io.xcpt.prop.vmu.stall && mt.b && (pending === UInt(1)) && !beat_mid)

  //--------------------------------------------------------------------\\
  // permutation network
  //--------------------------------------------------------------------\\

  private def repack(sz_elt: Int, sz_reg: Int) = {
    require((sz_elt <= sz_reg) && (tlDataBits % sz_elt == 0))
    val elts = for (g <- (0 until SZ_DATA by sz_reg).grouped(nSlices);
      q <- brqs_deq; i <- g.iterator) yield q.bits.data(i+sz_elt-1, i)
    val sets = elts.grouped(tlDataBits / sz_elt).map(Vec(_)).toIterable
    if (sets.size > 1) Vec(sets)(beat) else sets.head
  }

/*
  private def select(sz_elt: Int) = {
    val xs = Seq(SZ_D, SZ_W, SZ_H, SZ_B).zip(prec).span(_._1 >= sz_elt)._1
    require(xs.size > 0)
    if (xs.size > 1)
      Mux1H(xs.map(_._2), xs.map(p => repack(sz_elt, p._1)))
    else repack(sz_elt, xs.head._1)
  }
*/

  val data_d = repack(SZ_D, SZ_D)
  val data_w = repack(SZ_W, SZ_D)
  val data_h = repack(SZ_H, SZ_D)
  val data_b = repack(SZ_B, SZ_D)

  val data_b_hold = Reg(Vec.fill(data_b.size)(Bits()))
  when (next && beat_mid) {
    data_b_hold := data_b
  }

  // Bypass hold register if final beat is odd
  val data_b_head = Mux(last && beat_mid, data_b, data_b_hold)
  io.vsdq.bits := Mux1H(mtsel,
    Seq(data_b_head ++ data_b, data_h, data_w, data_d).map(x => Cat(x.reverse)))
}

class VLUEntry extends HwachaBundle with LaneParameters {
  val eidx = UInt(width = SZ_VLEN - log2Up(nBatch))
  val data = Bits(width = SZ_DATA)
  val mask = Bits(width = nSlices)
}

class VLU extends HwachaModule with LaneParameters with VMUParameters {
  val io = new Bundle {
    val op = Decoupled(new DCCMemOp).flip

    val vldq = new VLDQIO().flip
    val bwqs = Vec.fill(nbanks)(new BWQIO)
    val la = new CounterLookAheadIO().flip
  }

  //--------------------------------------------------------------------\\
  // control
  //--------------------------------------------------------------------\\

  val vlen = Reg(UInt(width = SZ_VLEN))
  val eidx = Reg(UInt(width = SZ_VLEN))
  val eidx_next = eidx + io.la.cnt

  val mt_hold = Reg(Bits(width = MT_SZ))
  val mt = DecodedMemType(mt_hold)
  val mt_sel = Seq(mt.d, mt.w, mt.h, mt.b)

  io.op.ready := Bool(false)

  val s_idle :: s_busy :: Nil = Enum(UInt(), 2)
  val state = Reg(init = s_idle)

  switch (state) {
    is (s_idle) {
      io.op.ready := Bool(true)
      when (io.op.valid) {
        state := s_busy
        eidx := UInt(0)
        vlen := io.op.bits.vlen
        mt_hold := io.op.bits.fn.mt
      }
    }

    is (s_busy) {
      when (io.la.reserve) {
        eidx := eidx_next
        when (eidx_next === vlen) {
          state := s_idle
        }
      }
    }
  }


  val inter = Module(new VLUInterposer)
  inter.io.enq <> io.vldq
  inter.io.en := mt.b
  inter.io.init := io.op.fire()

  val vldq = inter.io.deq
  val meta = vldq.bits.meta

  //--------------------------------------------------------------------\\
  // permutation network
  //--------------------------------------------------------------------\\

  private val lgbanks = log2Up(nbanks)
  private val lgslices = log2Up(nSlices)
  private val lgbatch = log2Up(nBatch)

  val eidx_slice = meta.eidx(lgslices-1, 0)
  val eidx_batch = meta.eidx(lgbatch-1, 0)
  val eidx_bank = meta.eidx(lgbatch-1, lgslices)
  val eidx_reg = meta.eidx(SZ_VLEN-1, lgbatch)
  val eidx_reg_next = eidx_reg + UInt(1)

  require(nSlices == 2)

  val data_rotamt = eidx_batch - meta.eskip
  val mask_rotamt = eidx_batch

  private def rotate[T <: Data](gen: T, in: Iterable[T], sel: UInt) = {
    val rot = Module(new Rotator(gen, in.size, nBatch))
    val out = Vec.fill(nBatch)(gen.clone)
    rot.io.sel := sel
    rot.io.in := in
    out := rot.io.out
    out
  }

  val mt_signed = !mt.unsigned
  private def extend(in: Bits, sz: Int): Bits =
    if (sz < SZ_D) Cat(Fill(SZ_D-sz, in(sz-1) && mt_signed), in) else in

  private def rotate_data(sz: Int) = {
    val elts = (0 until tlDataBits by sz).map(i => vldq.bits.data(i+sz-1, i))
    val in = if (elts.size > nBatch) elts.take(nBatch) else elts
    val out = rotate(Bits(), in, data_rotamt)
    Vec(out.map(extend(_, sz)))
  }

  val data_d = rotate_data(SZ_D)
  val data_w = rotate_data(SZ_W)
  val data_h = rotate_data(SZ_H)
  val data_b = rotate_data(SZ_B)

  val data = Mux1H(mt_sel, Seq(data_d, data_w, data_h, data_b))

  //--------------------------------------------------------------------\\
  // masking / overflow
  //--------------------------------------------------------------------\\

  val tick = Reg(Bool())
  val tock = !tick

  val mask_root = EnableDecoder(meta.ecnt, nBatch)

  assert(!vldq.valid || (meta.ecnt <= UInt(nBatch)), "ecnt exceeds limit")
  val slice_unaligned = (eidx_slice != UInt(0)) && tick
  val slice_overflow = mask_root.last && slice_unaligned

  val tock_next = vldq.valid && slice_overflow && (state === s_busy)
  tick := !tock_next

  val mask_head = tick
  val mask_tail = !slice_unaligned
  val mask_beat = mask_root.init.map(_ && mask_head) :+ (mask_root.last && mask_tail)
  val mask = rotate(Bool(), mask_beat, eidx_batch)

  //--------------------------------------------------------------------\\
  // bank write queues
  //--------------------------------------------------------------------\\

  private def merge[T <: Data](in: Seq[T]): Seq[Bits] =
    in.grouped(nSlices).map(xs => Cat(xs.reverse)).toSeq

  val bwqs_data = merge(data)
  val bwqs_mask = merge(mask)
  val bwqs_en = bwqs_mask.map(_.orR)

  val wb_update = Vec.fill(nbanks)(Bits(width = nvlreq))

  val bwqs = io.bwqs.zipWithIndex.map { case (deq, i) =>
    val bwq = Module(new Queue(new VLUEntry, nbwq))

    val eidx_advance = (UInt(i) < eidx_bank) || tock
    bwq.io.enq.bits.eidx := Mux(eidx_advance, eidx_reg_next, eidx_reg)
    bwq.io.enq.bits.data := bwqs_data(i)
    bwq.io.enq.bits.mask := bwqs_mask(i)

    val wb_eidx = Cat(bwq.io.deq.bits.eidx, UInt(i, lgbanks), UInt(0, lgslices))
    val wb_shift = wb_eidx - eidx
    val wb_mask = bwq.io.deq.bits.mask & Fill(nSlices, bwq.io.deq.fire())
    wb_update(i) := wb_mask << wb_shift

    deq.valid := bwq.io.deq.valid
    bwq.io.deq.ready := deq.ready

    deq.bits.addr := UInt(0) // FIXME
    deq.bits.data := bwq.io.deq.bits.data
    deq.bits.mask := FillInterleaved(SZ_D, bwq.io.deq.bits.mask)
    bwq.io.enq
  }

  val bwqs_ready = bwqs.zip(bwqs_en).map { case (bwq, en) => !en || bwq.ready }

  private def fire(exclude: Bool, include: Bool*) = {
    val rvs = vldq.valid +: bwqs_ready
    (rvs.filter(_.ne(exclude)) ++ include).reduce(_ && _)
  }

  val vldq_en = !slice_overflow
  vldq.ready := fire(vldq.valid, vldq_en)
  bwqs.zip(bwqs_ready.zip(bwqs_en)).foreach { case (bwq, (ready, en)) =>
    bwq.valid := fire(ready, en)
  }

  //--------------------------------------------------------------------\\
  // writeback status
  //--------------------------------------------------------------------\\

  val wb_status = Reg(Bits(width = nvlreq))
  val wb_next = wb_status | wb_update.reduce(_ | _)
  val wb_retire_cnt = (io.la.cnt >> UInt(lgbatch)) &
    Fill(lookAheadMax - lgbatch, io.la.reserve)
  val wb_retire = Cat(wb_retire_cnt, UInt(0, lgbatch))

  wb_status := (wb_next >> wb_retire) & Fill(nvlreq, state != s_idle)

  private def priority(in: Iterable[(Bool, UInt)]): (Bool, UInt) = {
    if (in.size > 1) {
      val cnt = in.foldLeft(UInt(0)) { case (cnt0, (sel, cnt1)) =>
        Mux(sel, cnt1, cnt0)
      }
      val sel = in.map(_._1).reduce(_ || _)
      (sel, cnt)
    } else in.head
  }

  private def priority_tree(in: Iterable[(Bool, UInt)]): Iterable[(Bool, UInt)] = {
    val stage = in.grouped(2).map(xs => priority(xs)).toSeq
    if (stage.size > 1) priority_tree(stage) else stage
  }

  // Limited leading-ones count
  val wb_locnt_init = (0 until lookAheadMax).map(i => (wb_status(i), UInt(i+1)))
  val wb_locnt_tree = priority_tree(wb_locnt_init).head
  val wb_locnt = Mux(wb_locnt_tree._1, wb_locnt_tree._2, UInt(0))
  io.la.available := (wb_locnt >= io.la.cnt)
}

class VLUInterposer extends VMUModule with LaneParameters {
  val io = new Bundle {
    val enq = new VLDQIO().flip
    val deq = new VLDQIO

    val init = Bool(INPUT)
    val en = Bool(INPUT)
  }

  val meta = io.enq.bits.meta

  val ecnt_head = SInt(nBatch) - meta.eskip.zext
  val ecnt_tail = meta.ecnt.zext - ecnt_head
  val has_head = (ecnt_head > SInt(0))
  val has_tail = (ecnt_tail > SInt(0))

  val shift = Bool()
  val dequeue = Bool()
  shift := Bool(false)
  dequeue := Bool(true)

  io.deq.valid := io.enq.valid
  io.enq.ready := io.deq.ready && dequeue

  val fire = io.deq.fire()
  io.deq.bits.meta := meta
  io.deq.bits.data := Mux(shift,
    io.enq.bits.data(tlDataBits-1, nBatch*8),
    io.enq.bits.data)

  val s1 :: s2 :: Nil = Enum(UInt(), 2)
  val state = Reg(UInt())

  when (io.init) {
    state := s1
  }

  when (io.en) {
    switch (state) {
      is (s1) {
        when (has_tail) {
          when (has_head) { /* two beats */
            io.deq.bits.meta.ecnt := ecnt_head
            dequeue := Bool(false)
            when (fire) {
              state := s2
            }
          } .otherwise { /* one beat, shifted */
            io.deq.bits.meta.eskip := meta.eskip - UInt(nBatch)
            shift := Bool(true)
          }
        }
      }

      is (s2) {
        io.deq.bits.meta.ecnt := ecnt_tail
        io.deq.bits.meta.eidx := meta.eidx + ecnt_head
        io.deq.bits.meta.eskip := UInt(0)
        shift := Bool(true)
        when (fire) {
          state := s1
        }
      }
    }
  }
}
