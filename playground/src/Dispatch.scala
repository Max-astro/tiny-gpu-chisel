package dispatch

import chisel3._
import chisel3.util._
import org.apache.commons.lang3.ThreadUtils.ThreadPredicate

class Dispatch(NumCores: Int = 2, ThreadsPerCore: Int = 4) extends Module {
  val ThreadCountWidth = log2Ceil(ThreadsPerCore) + 1
  val io               = IO(new Bundle {
    val start = Input(Bool())

    // Kernel Metadata
    val thread_count = Input(UInt(8.W))

    // Core States
    val core_done         = Input(Vec(NumCores, Bool()))
    val core_start        = Output(Vec(NumCores, Bool()))
    val core_reset        = Output(Vec(NumCores, Bool()))
    val core_block_id     = Output(Vec(NumCores, UInt(8.W)))
    val core_thread_count = Output(Vec(NumCores, UInt(ThreadCountWidth.W)))

    // Kernel Execution
    val done = Output(Bool())
  })

  printf(cf"Dispatch: start = ${io.start}, thread_count = ${io.thread_count}, core_done = ${io.core_done}\n")

  // Calculate the total number of blocks based on total threads & threads per block
  val total_blocks = (io.thread_count + ThreadsPerCore.U - 1.U) / ThreadsPerCore.U

  val done              = RegInit(false.B)
  val blocks_dispatched = RegInit(0.U(8.W))
  val blocks_done       = RegInit(0.U(8.W))
  val start_execution   = RegInit(false.B)
  val core_start        = RegInit(VecInit(Seq.fill(NumCores)(false.B)))
  val core_reset        = RegInit(VecInit(Seq.fill(NumCores)(true.B)))
  val core_block_id     = RegInit(VecInit(Seq.fill(NumCores)(0.U(8.W))))
  val core_thread_count = RegInit(VecInit(Seq.fill(NumCores)(ThreadsPerCore.U(ThreadCountWidth.W))))

  printf(
    cf"Dispatch outputs: core_start=${core_start}, core_reset=${core_reset}, core_block_id=${core_block_id}, core_thread_count=${core_thread_count}, done=${done}\n"
  )

  // printf(cf"--total_blocks = $total_blocks, thread_count = ${io.thread_count}, blocks_done = $blocks_done\n")

  // Keep track of how many blocks have been processed
  when(!reset.asBool && io.start) {
    when(!start_execution) {
      start_execution := true.B
      core_reset.foreach(_ := true.B)
    }

    // If the last block has finished processing, mark this kernel as done executing
    when(blocks_done === total_blocks) {
      done := true.B
    }

    for (i <- 0 until NumCores) {
      when(core_reset(i)) {
        core_reset(i) := false.B

        // If this core was just reset, check if there are more blocks to be dispatched
        when(blocks_dispatched < total_blocks) {
          // printf(cf"--i = $i, blocks_dispatched = $blocks_dispatched, total_blocks = $total_blocks\n")
          core_start(i)        := true.B
          core_block_id(i)     := blocks_dispatched
          core_thread_count(i) := Mux(
            blocks_dispatched === total_blocks - 1.U,
            io.thread_count - (blocks_dispatched * ThreadsPerCore.U),
            ThreadsPerCore.U
          )
          blocks_dispatched    := blocks_dispatched + 1.U
        }
      }

      // printf(cf"--core_start(i) = ${core_start(i)}, io.core_done(i) = ${io.core_done(i)}\n")
      when(core_start(i) && io.core_done(i)) {
        // If a core just finished executing it's current block, reset it
        core_reset(i) := true.B
        core_start(i) := false.B
        blocks_done   := blocks_done + 1.U
      }
    }
  }

  io.core_start        := core_start
  io.core_reset        := core_reset
  io.core_block_id     := core_block_id
  io.core_thread_count := core_thread_count
  io.done              := done
}
