package riscvVector {
  import Chisel._
  import Node._

class vuVMU_Ctrl_ut_issueIO extends Bundle {
  val iscmdq_deq_bits		  = UFix(UT_ISCMD_SZ, 'input);
  val iscmdq_deq_val		  = Bool('input);
  val iscmdq_deq_rdy		  = Bool('output);

  val utaq_deq_bits		    = UFix(32, 'input);
  val utaq_deq_val		    = Bool('input);
  val utaq_deq_rdy		    = Bool('output);

  // interface to load request queue
  val lrq_enq_addr_bits		= UFix(30, 'output);
  val lrq_enq_tag_bits		= UFix(12, 'output);
  val lrq_enq_rdy		      = Bool('input);
  val lrq_enq_val		      = Bool('output);
  
  // interface to reorder queue
  val roq_deq_tag_bits		= UFix(8, 'input);
  val roq_deq_tag_val		  = Bool('input);
  val roq_deq_tag_rdy		  = Bool('output);
  val issue_busy		      = Bool('output);
}

class vuVMU_Ctrl_ut_issue extends Component {
  val io = new vuVMU_Ctrl_ut_issueIO();
  
  val VMU_Ctrl_Idle = UFix(0, 1);
  val VMU_Ctrl_Issue = UFix(1, 1);

  val addr_reg = Reg(resetVal = UFix(0, 32));
  val vlen_reg = Reg(resetVal = UFix(0, UTMCMD_VLEN_SZ)); 

  val vlen = io.iscmdq_deq_bits(UTMCMD_VLEN_SZ-1, 0);
  val addr = io.iscmdq_deq_bits(UTMIMM_SZ+UTMCMD_VLEN_SZ-1, UTMCMD_VLEN_SZ);
  val req_addr = addr_reg + io.utaq_deq_bits;

  val state = Reg(resetVal = VMU_Ctrl_Idle);

  io.issue_busy = (state === VMU_Ctrl_Issue) | io.iscmdq_deq_val;
  io.lrq_enq_addr_bits := req_addr(31, 2);
 
  switch(state) {
    is(VMU_Ctrl_Idle) {
      io.iscmdq_deq_rdy   <== Bool(true);
      io.lrq_enq_val      <== Bool(false); 
      io.roq_deq_tag_rdy  <== Bool(false);
      io.utaq_deq_rdy     <== Bool(false);
      when(io.iscmdq_deq_val) {
        state <== VMU_Ctrl_Issue;
        addr_reg <== addr;
        vlen_reg <== vlen;
      }
    }
    is(VMU_Ctrl_Issue) {
      io.iscmdq_deq_rdy   <== Bool(false);
      io.lrq_enq_val      <== io.roq_deq_tag_val  & io.utaq_deq_val;
      io.roq_deq_tag_rdy  <== io.lrq_enq_rdy      & io.utaq_deq_val;
      io.utaq_deq_rdy     <== io.roq_deq_tag_val  & io.lrq_enq_rdy;
      when(io.lrq_enq_rdy && iroq_deq_tag_val && io.utaq_deq_val) {
        when(vlen_reg === UFix(0, UTMCMD_VLEN_SZ)) {
          state <== VMU_Ctrl_Idle;
        }
        otherwise {
          vlen_reg <== vlen_reg - UFix(1, UTMCMD_VLEN_SZ);
        }
      }
    }
  }
}
