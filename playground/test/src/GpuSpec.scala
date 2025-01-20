package gpu

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import core.CoreModel
import controller.ControllerModel
import dispatch.DispatchModel

class GpuModel(
  DataMemAddrBits:       Int = 8,
  DataMemDataBits:       Int = 8,
  DataMemNumChannels:    Int = 4,
  ProgramMemAddrBits:    Int = 8,
  ProgramMemDataBits:    Int = 16,
  ProgramMemNumChannels: Int = 1,
  NumCores:              Int = 2,
  ThreadsPerBlock: Int = 4) {
  // Submodule models
  private var dispatchModel = new DispatchModel(NumCores, ThreadsPerBlock)

  // Memory controllers
  private val NumLSUs                 = NumCores * ThreadsPerBlock
  private var dataMemoryController    = new ControllerModel(
    DataMemAddrBits,
    DataMemDataBits,
    NumCores * ThreadsPerBlock,
    DataMemNumChannels
  )
  private var programMemoryController = new ControllerModel(
    ProgramMemAddrBits,
    ProgramMemDataBits,
    NumCores,
    ProgramMemNumChannels
  )

  // Core models
  private var coreModels = Array.fill(NumCores)(
    new CoreModel(
      DataMemAddrBits,
      DataMemDataBits,
      ProgramMemAddrBits,
      ProgramMemDataBits,
      ThreadsPerBlock
    )
  )

  // Last cycle datas
  // Device Control Register
  var drc_thread_count = 0

  // Data memory controller outputs
  var data_consumer_read_ready                                       = Array.fill(NumLSUs)(false)
  var data_consumer_read_data                                        = Array.fill(NumLSUs)(0)
  var data_consumer_write_ready                                      = Array.fill(NumLSUs)(false)
  def save_data_memory_controller(model: controller.ControllerModel) = {
    data_consumer_read_ready = model.consumer_read_ready
    data_consumer_read_data = model.consumer_read_data
    data_consumer_write_ready = model.consumer_write_ready
  }

  // Program memory controller without write outputs
  var program_consumer_read_ready                                       = Array.fill(NumCores)(false)
  var program_consumer_read_data                                        = Array.fill(NumCores)(0)
  def save_program_memory_controller(model: controller.ControllerModel) = {
    program_consumer_read_ready = model.consumer_read_ready
    program_consumer_read_data = model.consumer_read_data
  }

  // Dispatcher outputs
  var dispatcher_core_start                          = Array.fill(NumCores)(false)
  var dispatcher_core_block_id                       = Array.fill(NumCores)(0)
  var dispatcher_core_thread_count                   = Array.fill(NumCores)(ThreadsPerBlock)
  def save_dispatcher(model: dispatch.DispatchModel) = {
    dispatcher_core_start = model.core_start
    dispatcher_core_block_id = model.core_block_id
    dispatcher_core_thread_count = model.core_thread_count
  }

  // Cores outputs 8/8
  var cores_done                                 = Array.fill(NumCores)(false)
  var cores_program_mem_read_address_valid       = Array.fill(NumCores)(false)
  var cores_program_mem_read_address_bits        = Array.fill(NumCores)(0)
  var cores_data_mem_read_address_valid          = Array.fill(NumCores)(Array.fill(ThreadsPerBlock)(false))
  var cores_data_mem_read_address_bits           = Array.fill(NumCores)(Array.fill(ThreadsPerBlock)(0))
  var cores_data_mem_write_address_valid         = Array.fill(NumCores)(Array.fill(ThreadsPerBlock)(false))
  var cores_data_mem_write_address_bits          = Array.fill(NumCores)(Array.fill(ThreadsPerBlock)(0))
  var cores_data_mem_write_data_bits             = Array.fill(NumCores)(Array.fill(ThreadsPerBlock)(0))
  def save_core(idx: Int, model: core.CoreModel) = {
    cores_done(idx) = model.done
    cores_program_mem_read_address_valid(idx) = model.program_mem_read_address_valid
    cores_program_mem_read_address_bits(idx) = model.program_mem_read_address_bits
    cores_data_mem_read_address_valid(idx) = model.data_mem_read_address_valid
    cores_data_mem_read_address_bits(idx) = model.data_mem_read_address_bits
    cores_data_mem_write_address_valid(idx) = model.data_mem_write_address_valid
    cores_data_mem_write_address_bits(idx) = model.data_mem_write_address_bits
    cores_data_mem_write_data_bits(idx) = model.data_mem_write_data_bits
  }

  // GPU outputs 8/8
  var gpu_done                   = false
  var gpu_program_mem_read_valid = Array.fill(ProgramMemNumChannels)(false)
  var gpu_program_mem_read_bits  = Array.fill(ProgramMemNumChannels)(0)
  var gpu_data_mem_read_valid    = Array.fill(DataMemNumChannels)(false)
  var gpu_data_mem_read_bits     = Array.fill(DataMemNumChannels)(0)
  var gpu_data_mem_write_valid   = Array.fill(DataMemNumChannels)(false)
  var gpu_data_mem_write_address = Array.fill(DataMemNumChannels)(0)
  var gpu_data_mem_write_data    = Array.fill(DataMemNumChannels)(0)

  def update(
    // Control inputs
    start:                       Boolean,
    device_control_write_enable: Boolean,
    device_control_data:         Int,

    // Memory inputs
    program_mem_read_ready: Array[Boolean],
    program_mem_read_data:  Array[Int],
    data_mem_read_ready:    Array[Boolean],
    data_mem_read_data:     Array[Int],
    data_mem_write_ready:   Array[Boolean]
  ): Unit = {
    // Update dispatcher
    val core_done = coreModels.map(_.done)
    dispatchModel.update(
      start = start,
      thread_count = drc_thread_count,
      core_done = cores_done
    )

    // Update memory controllers
    dataMemoryController.update(
      consumer_read_valid = cores_data_mem_read_address_valid.flatten,
      consumer_read_addr = cores_data_mem_read_address_bits.flatten,
      consumer_write_valid = cores_data_mem_write_address_valid.flatten,
      consumer_write_addr = cores_data_mem_write_address_bits.flatten,
      consumer_write_data = cores_data_mem_write_data_bits.flatten,
      mem_read_ready = data_mem_read_ready,
      mem_read_data = data_mem_read_data,
      mem_write_ready = data_mem_write_ready
    )

    programMemoryController.update(
      consumer_read_valid = cores_program_mem_read_address_valid,
      consumer_read_addr = cores_program_mem_read_address_bits,
      consumer_write_valid = Array.fill(NumCores)(false),
      consumer_write_addr = Array.fill(NumCores)(0),
      consumer_write_data = Array.fill(NumCores)(0),
      mem_read_ready = program_mem_read_ready,
      mem_read_data = program_mem_read_data,
      mem_write_ready = Array.fill(DataMemNumChannels)(false)
    )

    // Update each core
    for (i <- 0 until NumCores) {
      // Update core model
      coreModels(i).update(
        start = dispatcher_core_start(i),
        block_id = dispatcher_core_block_id(i),
        thread_count = dispatcher_core_thread_count(i),
        program_mem_read_ready = program_consumer_read_ready(i),
        program_mem_read_data = program_consumer_read_data(i),
        data_mem_read_data = data_consumer_read_data.slice(i * ThreadsPerBlock, (i + 1) * ThreadsPerBlock),
        data_mem_read_ready = data_consumer_read_ready.slice(i * ThreadsPerBlock, (i + 1) * ThreadsPerBlock),
        data_mem_write_ready = data_consumer_write_ready.slice(i * ThreadsPerBlock, (i + 1) * ThreadsPerBlock)
      )
      save_core(i, coreModels(i))
    }

    // Update outputs
    gpu_done = dispatchModel.done
    gpu_program_mem_read_valid = coreModels.map(_.program_mem_read_address_valid)
    gpu_program_mem_read_bits = coreModels.map(_.program_mem_read_address_bits)
    gpu_data_mem_read_valid = dataMemoryController.mem_read_valid
    gpu_data_mem_read_bits = dataMemoryController.mem_read_address
    gpu_data_mem_write_valid = dataMemoryController.mem_write_valid
    gpu_data_mem_write_address = dataMemoryController.mem_write_address
    gpu_data_mem_write_data = dataMemoryController.mem_write_data

    // update internal states
    if (device_control_write_enable) {
      drc_thread_count = device_control_data
    }
    save_program_memory_controller(programMemoryController)
    save_data_memory_controller(dataMemoryController)
    save_dispatcher(dispatchModel)
  }
}

class GpuSpec extends AnyFreeSpec with Matchers {
  "A cycle based GPU tester" - {
    "should match model behavior with random inputs" in {
      val DataMemAddrBits       = 8
      val DataMemDataBits       = 8
      val DataMemNumChannels    = 4
      val ProgramMemAddrBits    = 8
      val ProgramMemDataBits    = 16
      val ProgramMemNumChannels = 1
      val NumCores              = 2
      val ThreadsPerBlock       = 4

      simulate(
        new Gpu(
          DataMemAddrBits,
          DataMemDataBits,
          DataMemNumChannels,
          ProgramMemAddrBits,
          ProgramMemDataBits,
          ProgramMemNumChannels,
          NumCores,
          ThreadsPerBlock
        )
      ) { dut =>
        // Reset the DUT
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        var cnt      = 0
        val rng      = new scala.util.Random(42) // 42 is the seed for reproducibility
        val gpuModel = new GpuModel(
          DataMemAddrBits,
          DataMemDataBits,
          DataMemNumChannels,
          ProgramMemAddrBits,
          ProgramMemDataBits,
          ProgramMemNumChannels,
          NumCores,
          ThreadsPerBlock
        )

        while (cnt < 10000) {
          // Generate random inputs
          val device_control_data = rng.nextInt(256)

          val program_mem_read_ready = Array.fill(ProgramMemNumChannels)(rng.nextBoolean())
          val program_mem_read_data  =
            Array.fill(ProgramMemNumChannels)(rng.nextInt(1 << ProgramMemDataBits))

          val data_mem_read_ready  = Array.fill(DataMemNumChannels)(rng.nextBoolean())
          val data_mem_read_data   = Array.fill(DataMemNumChannels)(rng.nextInt(1 << DataMemDataBits))
          val data_mem_write_ready = Array.fill(DataMemNumChannels)(rng.nextBoolean())

          // Apply inputs to DUT
          dut.io.start.poke(true.B)
          dut.io.device_control_write_enable.poke(true.B)
          dut.io.device_control_data.poke(device_control_data.U)

          for (i <- 0 until ProgramMemNumChannels) {
            dut.io.program_mem_read_sender(i).ready.poke(program_mem_read_ready(i).B)
            dut.io.program_mem_read_data(i).poke(program_mem_read_data(i).U)
          }

          for (i <- 0 until DataMemNumChannels) {
            dut.io.data_mem_read_sender(i).ready.poke(data_mem_read_ready(i).B)
            dut.io.data_mem_read_data(i).poke(data_mem_read_data(i).U)
            dut.io.data_mem_write_sender(i).ready.poke(data_mem_write_ready(i).B)
          }

          dut.clock.step()

          // Update model with same inputs
          gpuModel.update(
            start = true,
            device_control_write_enable = true,
            device_control_data = device_control_data,
            program_mem_read_ready = program_mem_read_ready,
            program_mem_read_data = program_mem_read_data,
            data_mem_read_ready = data_mem_read_ready,
            data_mem_read_data = data_mem_read_data,
            data_mem_write_ready = data_mem_write_ready
          )

          // Verify outputs
          dut.io.done.expect(gpuModel.gpu_done.B)

          // Verify program memory interface
          for (i <- 0 until ProgramMemNumChannels) {
            dut.io.program_mem_read_sender(i).valid.expect(gpuModel.gpu_program_mem_read_valid(i).B)
            dut.io.program_mem_read_sender(i).bits.expect(gpuModel.gpu_program_mem_read_bits(i).U)
          }

          // Verify data memory interface
          for (i <- 0 until DataMemNumChannels) {
            dut.io.data_mem_read_sender(i).valid.expect(gpuModel.gpu_data_mem_read_valid(i).B)
            dut.io.data_mem_read_sender(i).bits.expect(gpuModel.gpu_data_mem_read_bits(i).U)
            dut.io.data_mem_write_sender(i).valid.expect(gpuModel.gpu_data_mem_write_valid(i).B)
            dut.io.data_mem_write_sender(i).bits.address.expect(gpuModel.gpu_data_mem_write_address(i).U)
            dut.io.data_mem_write_sender(i).bits.data.expect(gpuModel.gpu_data_mem_write_data(i).U)
          }

          cnt += 1
        }
      }
    }
  }
}

class GpuBehaviorSpec extends AnyFreeSpec with Matchers {
  "A behavior based GPU tester" - {
    "The final memory state should match GpuVM's data memory" in {
      val DataMemAddrBits       = 8
      val DataMemDataBits       = 8
      val DataMemNumChannels    = 4
      val ProgramMemAddrBits    = 8
      val ProgramMemDataBits    = 16
      val ProgramMemNumChannels = 1
      val NumCores              = 2
      val ThreadsPerBlock       = 4

      val asmFile = sys.env.get("GPU_ASM")
      if (asmFile.isEmpty) {
        println("Usage: Set the environment variable GPU_ASM to the path of the asm file.")
        System.exit(1)
      }

      val lexer  = new Lexer()
      val parser = new AsmParser()
      val vm     = new GpuVM(NumCores, ThreadsPerBlock, 1 << DataMemAddrBits)

      val asm = scala.io.Source.fromFile(asmFile.get).getLines().mkString("\n")
      parser.parse(lexer.tokenize(asm))

      vm.init(parser.getDataArrays)
      vm.run(parser.getInstructions)

      val program_mem = MachineCodeEmitter.emit(asm)

      simulate(
        new Gpu(
          DataMemAddrBits,
          DataMemDataBits,
          DataMemNumChannels,
          ProgramMemAddrBits,
          ProgramMemDataBits,
          ProgramMemNumChannels,
          NumCores,
          ThreadsPerBlock
        )
      ) { dut =>
        // Reset the DUT
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.clock.step()

        // TODO: Use DPI to initialize the memory
      }
    }
  }
}
