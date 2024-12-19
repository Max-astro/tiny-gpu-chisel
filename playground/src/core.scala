package core

import chisel3._
import chisel3.util._
import statecode.CoreState

import fetcher.Fetcher
import decoder.Decoder
import scheduler.Scheduler
import registers.RegisterFiles
import alu.Alu
import lsu.MemLoadStoreUnit
import pc.ProgramCounter

// TODO: Change each submodules' IO Bundle,
//       and then connect them in a more Chisel way

// COMPUTE CORE
// > Handles processing 1 block at a time
// > The core also has it's own scheduler to manage control flow
// > Each core contains 1 fetcher & decoder, and register files, ALUs, LSUs, PC for each thread
class Core(
  DataMemAddrBits:    Int = 8,
  DataMemDataBits:    Int = 8,
  ProgramMemAddrBits: Int = 8,
  ProgramMemDataBits: Int = 16,
  ThreadsPerBlock:    Int = 4)
    extends Module {
  val io = IO(new Bundle {
    // Kernel Execution
    val start = Input(Bool())
    val done  = Output(Bool())

    // Block Metadata
    val block_id     = Input(UInt(8.W))
    val thread_count = Input(UInt(log2Ceil(ThreadsPerBlock).W))

    // Program Memory
    val program_mem_read_valid   = Output(Bool())
    val program_mem_read_address = Output(UInt(ProgramMemAddrBits.W))
    val program_mem_read_ready   = Input(Bool())
    val program_mem_read_data    = Input(UInt(ProgramMemDataBits.W))

    // Data Memory
    val data_mem_read_valid    = Output(Vec(ThreadsPerBlock, Bool()))
    val data_mem_read_address  = Output(Vec(ThreadsPerBlock, UInt(DataMemAddrBits.W)))
    val data_mem_read_ready    = Input(UInt(ThreadsPerBlock.W))
    val data_mem_read_data     = Input(Vec(ThreadsPerBlock, UInt(DataMemDataBits.W)))
    val data_mem_write_valid   = Output(Vec(ThreadsPerBlock, Bool()))
    val data_mem_write_address = Output(Vec(ThreadsPerBlock, UInt(DataMemAddrBits.W)))
    val data_mem_write_data    = Output(Vec(ThreadsPerBlock, UInt(DataMemDataBits.W)))
    val data_mem_write_ready   = Input(UInt(ThreadsPerBlock.W))
  })

  val fetcher   = Module(new Fetcher(ProgramMemAddrBits, ProgramMemDataBits))
  val decoder   = Module(new Decoder())
  val scheduler = Module(new Scheduler(ThreadsPerBlock))

  // Fetcher inputs connections (4/4)
  fetcher.io.core_state     := scheduler.io.core_state
  fetcher.io.current_pc     := scheduler.io.current_pc
  fetcher.io.mem_read_ready := io.program_mem_read_ready
  fetcher.io.mem_read_data  := io.program_mem_read_data

  // Decoder inputs connections (2/2)
  decoder.io.core_state  := scheduler.io.core_state
  decoder.io.instruction := fetcher.io.instruction

  // Scheduler inputs connections (5/7)
  scheduler.io.start                    := io.start
  scheduler.io.decoded_mem_read_enable  := decoder.io.decoded_mem_read_enable
  scheduler.io.decoded_mem_write_enable := decoder.io.decoded_mem_write_enable
  scheduler.io.decoded_ret              := decoder.io.decoded_ret
  scheduler.io.fetcher_state            := fetcher.io.fetcher_state

  val compute_units = Seq
    .tabulate(ThreadsPerBlock)(i => {
      val alu     = Module(new Alu())
      val lsu     = Module(new MemLoadStoreUnit())
      val regfile = Module(new RegisterFiles(ThreadsPerBlock, i, DataMemDataBits))
      val pc      = Module(new ProgramCounter(DataMemDataBits, DataMemAddrBits))

      val enable = (i.U < io.thread_count)

      // alu inputs connections (6/6)
      alu.io.enable                     := enable
      alu.io.core_state                 := scheduler.io.core_state
      alu.io.decoded_alu_arithmetic_mux := decoder.io.decoded_alu_arithmetic_mux
      alu.io.decoded_alu_output_mux     := decoder.io.decoded_alu_output_mux
      alu.io.rs                         := regfile.io.rs(i)
      alu.io.rt                         := regfile.io.rt(i)

      // lsu inputs connections (9/9)
      lsu.io.enable                   := enable
      lsu.io.core_state               := scheduler.io.core_state
      lsu.io.decoded_mem_read_enable  := decoder.io.decoded_mem_read_enable
      lsu.io.decoded_mem_write_enable := decoder.io.decoded_mem_write_enable
      lsu.io.rs                       := regfile.io.rs(i)
      lsu.io.rt                       := regfile.io.rt(i)
      lsu.io.mem_read_ready           := io.data_mem_read_ready(i)
      lsu.io.mem_read_data            := io.data_mem_read_data(i)
      lsu.io.mem_write_ready          := io.data_mem_write_ready(i)

      // regfile inputs connections (11/11)
      regfile.io.enable                   := enable
      regfile.io.block_id                 := io.block_id
      regfile.io.core_state               := scheduler.io.core_state
      regfile.io.decoded_rd_address       := decoder.io.decoded_rd_address
      regfile.io.decoded_rs_address       := decoder.io.decoded_rs_address
      regfile.io.decoded_rt_address       := decoder.io.decoded_rt_address
      regfile.io.decoded_reg_write_enable := decoder.io.decoded_reg_write_enable
      regfile.io.decoded_reg_input_mux    := decoder.io.decoded_reg_input_mux
      regfile.io.decoded_immediate        := decoder.io.decoded_immediate
      regfile.io.alu_out                  := alu.io.alu_out
      regfile.io.lsu_out                  := lsu.io.lsu_out

      // pc inputs connections (8/8)
      pc.io.enable                   := enable
      pc.io.core_state               := scheduler.io.core_state
      pc.io.decoded_nzp              := decoder.io.decoded_nzp
      pc.io.decoded_immediate        := decoder.io.decoded_immediate
      pc.io.decoded_nzp_write_enable := decoder.io.decoded_nzp_write_enable
      pc.io.decoded_pc_mux           := decoder.io.decoded_pc_mux
      pc.io.alu_out                  := alu.io.alu_out
      pc.io.current_pc               := scheduler.io.current_pc

      // Connect to scheduler input (7/7)
      scheduler.io.lsu_state(i) := lsu.io.lsu_state
      scheduler.io.next_pc(i)   := pc.io.next_pc

      // Connect to core module outputs (5/8)
      io.data_mem_read_valid(i)    := lsu.io.mem_read_valid
      io.data_mem_read_address(i)  := lsu.io.mem_read_address
      io.data_mem_write_valid(i)   := lsu.io.mem_write_valid
      io.data_mem_write_address(i) := lsu.io.mem_write_address
      io.data_mem_write_data(i)    := lsu.io.mem_write_data

      (alu, lsu, regfile, pc)
    })

  // Connect to module outputs (8/8)
  io.done                     := scheduler.io.done
  io.program_mem_read_valid   := fetcher.io.mem_read_valid
  io.program_mem_read_address := fetcher.io.mem_read_address
}