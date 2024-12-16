object Elaborate extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  // circt.stage.ChiselStage.emitSystemVerilogFile(new gcd.GCD(), args, firtoolOptions)
  circt.stage.ChiselStage.emitSystemVerilogFile(new alu.Alu(), args, firtoolOptions)
  circt.stage.ChiselStage.emitSystemVerilogFile(new pc.ProgramCounter(8, 8), args, firtoolOptions)
  circt.stage.ChiselStage.emitSystemVerilogFile(new lsu.MemLoadStoreUnit(), args, firtoolOptions)
}
