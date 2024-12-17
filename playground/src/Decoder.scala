package decoder

import chisel3._
import chisel3.util._

import statecode.CoreState

object DecoderState extends ChiselEnum {
  val NOP   = Value("b0000".U)
  val BRnzp = Value("b0001".U)
  val CMP   = Value("b0010".U)
  val ADD   = Value("b0011".U)
  val SUB   = Value("b0100".U)
  val MUL   = Value("b0101".U)
  val DIV   = Value("b0110".U)
  val LDR   = Value("b0111".U)
  val STR   = Value("b1000".U)
  val CONST = Value("b1001".U)
  val RET   = Value("b1111".U)
}

class Decoder extends Module {
  val io = IO(new Bundle {
    val core_state  = Input(CoreState())
    val instruction = Input(UInt(16.W))

    // Instruction signals
    val decoded_rd_address = Output(UInt(4.W))
    val decoded_rs_address = Output(UInt(4.W))
    val decoded_rt_address = Output(UInt(4.W))
    val decoded_nzp        = Output(UInt(3.W))
    val decoded_immediate  = Output(UInt(8.W))

    // Control signals
    val decoded_reg_write_enable   = Output(Bool())    // Enable writing to a register
    val decoded_mem_read_enable    = Output(Bool())    // Enable reading from memory
    val decoded_mem_write_enable   = Output(Bool())    // Enable writing to memory
    val decoded_nzp_write_enable   = Output(Bool())    // Enable writing to NZP register
    val decoded_reg_input_mux      = Output(UInt(2.W)) // Select input to register
    val decoded_alu_arithmetic_mux = Output(UInt(2.W)) // Select arithmetic operation
    val decoded_alu_output_mux     = Output(Bool())    // Select operation in ALU
    val decoded_pc_mux             = Output(Bool())    // Select source of next PC

    // Return (finished executing thread)
    val decoded_ret = Output(Bool())
  })

  val decoded_rd_address         = RegInit(0.U(4.W))
  val decoded_rs_address         = RegInit(0.U(4.W))
  val decoded_rt_address         = RegInit(0.U(4.W))
  val decoded_nzp                = RegInit(0.U(3.W))
  val decoded_immediate          = RegInit(0.U(8.W))
  val decoded_reg_write_enable   = RegInit(false.B)
  val decoded_mem_read_enable    = RegInit(false.B)
  val decoded_mem_write_enable   = RegInit(false.B)
  val decoded_nzp_write_enable   = RegInit(false.B)
  val decoded_reg_input_mux      = RegInit(0.U(2.W))
  val decoded_alu_arithmetic_mux = RegInit(0.U(2.W))
  val decoded_alu_output_mux     = RegInit(false.B)
  val decoded_pc_mux             = RegInit(false.B)
  val decoded_ret                = RegInit(false.B)

  when(!reset.asBool) {
    when(io.core_state === CoreState.DECODE) {
      // Get instruction signals from instruction every time
      decoded_rd_address := io.instruction(11, 8)
      decoded_rs_address := io.instruction(7, 4)
      decoded_rt_address := io.instruction(3, 0)
      decoded_immediate  := io.instruction(7, 0)
      decoded_nzp        := io.instruction(11, 9)

      // Control signals reset on every decode and set conditionally by instruction
      decoded_reg_write_enable   := false.B
      decoded_mem_read_enable    := false.B
      decoded_mem_write_enable   := false.B
      decoded_nzp_write_enable   := false.B
      decoded_reg_input_mux      := 0.U(2.W)
      decoded_alu_arithmetic_mux := 0.U(2.W)
      decoded_alu_output_mux     := false.B
      decoded_pc_mux             := false.B
      decoded_ret                := false.B

      // Set the control signals for each instruction
      val (instruction_opcode, _) = DecoderState.safe(io.instruction(15, 12))
      switch(instruction_opcode) {
        is(DecoderState.NOP) {
          // no-op
        }
        is(DecoderState.BRnzp) {
          decoded_pc_mux := true.B
        }
        is(DecoderState.CMP) {
          decoded_alu_output_mux   := true.B
          decoded_nzp_write_enable := true.B
        }
        is(DecoderState.ADD) {
          decoded_reg_write_enable   := true.B
          decoded_reg_input_mux      := "b00".U
          decoded_alu_arithmetic_mux := "b00".U
        }
        is(DecoderState.SUB) {
          decoded_reg_write_enable   := true.B
          decoded_reg_input_mux      := "b00".U
          decoded_alu_arithmetic_mux := "b01".U
        }
        is(DecoderState.MUL) {
          decoded_reg_write_enable   := true.B
          decoded_reg_input_mux      := "b00".U
          decoded_alu_arithmetic_mux := "b10".U
        }
        is(DecoderState.DIV) {
          decoded_reg_write_enable   := true.B
          decoded_reg_input_mux      := "b00".U
          decoded_alu_arithmetic_mux := "b11".U
        }
        is(DecoderState.LDR) {
          decoded_reg_write_enable := true.B
          decoded_reg_input_mux    := "b01".U
          decoded_mem_read_enable  := true.B
        }
        is(DecoderState.STR) {
          decoded_mem_write_enable := true.B
        }
        is(DecoderState.CONST) {
          decoded_reg_write_enable := true.B
          decoded_reg_input_mux    := "b10".U
        }
        is(DecoderState.RET) {
          decoded_ret := true.B
        }
      }
    }
  }

  io.decoded_rd_address         := decoded_rd_address
  io.decoded_rs_address         := decoded_rs_address
  io.decoded_rt_address         := decoded_rt_address
  io.decoded_nzp                := decoded_nzp
  io.decoded_immediate          := decoded_immediate
  io.decoded_reg_write_enable   := decoded_reg_write_enable
  io.decoded_mem_read_enable    := decoded_mem_read_enable
  io.decoded_mem_write_enable   := decoded_mem_write_enable
  io.decoded_nzp_write_enable   := decoded_nzp_write_enable
  io.decoded_reg_input_mux      := decoded_reg_input_mux
  io.decoded_alu_arithmetic_mux := decoded_alu_arithmetic_mux
  io.decoded_alu_output_mux     := decoded_alu_output_mux
  io.decoded_pc_mux             := decoded_pc_mux
  io.decoded_ret                := decoded_ret
}
