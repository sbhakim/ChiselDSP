/** Custom DSP tester -- shows values in Ints, Doubles instead of only hex.
  * Handles Chisel data types and ChiselDSP data types.
  */

package ChiselDSP
import Chisel.{Complex => _, _}
import java.lang.Double.{longBitsToDouble, doubleToLongBits}
import java.lang.Float.{intBitsToFloat, floatToIntBits}

object DSPTester {
  /** Expect tolerance
    * fixTolBits --> # of bits you can be off by
    * floTol --> decimal amount you can be off by
    */
  private[ChiselDSP] var fixTolBits: Int = 1
  private[ChiselDSP] var floTolDec: Double = 0.000000000000001
  def setTol(fixedTol: Int = fixTolBits, floTol: Double = floTolDec): Unit = {
    fixTolBits = fixedTol
    floTolDec = floTol
  }

  /** To keep track of failed test cases */
  private[ChiselDSP] var failedTests = Array.empty[String]
  /** Should a Verilog TB be generated? */
  private[ChiselDSP] var verilogTester = false
}

// TODO: Move XDC, Makefrag out of tester, 't' make private? (user can't change in their
// top TB), VCD? Switch to Handlebars?

// TODO: Poke vec of complex

class DSPTester[+T <: ModuleOverride](val c: T, verilogTester:Boolean = DSPTester.verilogTester,
                                      var traceOn: Boolean = !DSPTester.verilogTester,
                                      var hexOn: Boolean = true, var quitOnError: Boolean = false, var base: Int = 16)
  extends Tester(c, false, base){

  // Simulation time
  // var t = 0

  /** Helper function for retrieving IO name for Verilog TB creation */
  def getIOName(n: Node): String = {
    n.name.replace(c.name+"__","")
  }

  /** Specifying whether an input/output node is signed for Verilog TB */
  def isSigned(n: Node): Tuple2[Boolean,String] = {
    n match {
      case _: SInt | _ : Flo | _: Dbl | _: DSPDbl | _: Fixed | _: DSPFixed => (true," signed ")
      case _ => (false,"")
    }
  }

  // TODO: Switch to Scala writer, Makefrag + constraints should be in make vlsi (not exclusive to debug)
  val tb = new java.io.BufferedWriter(new java.io.FileWriter("build/debug/tb.v"))
  val xdc = new java.io.BufferedWriter(new java.io.FileWriter("build/debug/constraints.xdc"))
  val mk = new java.io.BufferedWriter(new java.io.FileWriter("build/debug/Makefrag"))

  val resets = c.getResets.values.toList

  // Only save "universal Verilog TB" if asked to save it
  if (!verilogTester){
    tb.close()
    xdc.close()
    mk.close()
    new java.io.File("build/debug/tb.v").delete()
    new java.io.File("build/debug/constraints.xdc").delete()
    new java.io.File("build/debug/Makefrag").delete()
  }
  else {

    // Get signals
    val ins = for ((n, io) <- c.wires if io.dir == INPUT && io.isTopLevelIO) yield io
    val outs = for ((n, io) <- c.wires if io.dir == OUTPUT && io.isTopLevelIO) yield io
    val mainClk = Driver.implicitClock
    val clocks = Driver.clocks

    // Verilog testbench requires that the clock period actually be multiple of 2; if not, find the more
    // conservative period that is a multiple of 2
    val evenPeriodx100ps = { if (Clock.getPeriodx100ps % 2 == 0) Clock.getPeriodx100ps else Clock.getPeriodx100ps-1 }

    val clkPeriodns = evenPeriodx100ps/10.0
    // Reset device time (needs reset! otherwise post-synthesis might be incorrect!)
    val clksTo120ns = math.round(120/clkPeriodns).intValue

    // Write constraints file for FPGA
    clocks foreach (clk => xdc write "create_clock -name %s -period %f %s\n".format(clk.name,clkPeriodns,clk.name))
    xdc.close()

    // Makefrag info for ASIC
    mk write "VLSITOP=%s\n".format(c.name)
    mk write "clock_period=%f".format(clkPeriodns)
    mk.close()

    // Setup TB
    // Note: Reset, input signals change and outputs are checked on falling edge
    // Cycle count changes with the clock rising edge
    tb write "`timescale 100ps / 100ps\n"
    tb write "`define CLK_PERIOD %d\n".format(evenPeriodx100ps)
    tb write "`define CLK_DELTA %d\n".format(evenPeriodx100ps/2)
    tb write "`define RESET_TIME (%d*`CLK_PERIOD + 3*`CLK_PERIOD/2 + `CLK_DELTA)\n".format(clksTo120ns)
    tb write "`define expect(nodeName, nodeVal, expVal, cycle) if (nodeVal !== expVal) begin " +
      "\\\n  $display(\"\\t ASSERTION ON %s FAILED @ CYCLE = %d, 0x%h != EXPECTED 0x%h\", " +
      "\\\n  nodeName,cycle,nodeVal,expVal); $stop; end\n\n"
    tb write "module ModuleTB;\n\n"
    tb write "  integer cycle = 0;\n"

    // Setup clocks + resets
    clocks foreach (clk => tb write "  reg %s = 0;\n".format(clk.name))
    resets foreach (rst => tb write "  reg %s = 1;\n".format(rst.name))

    if (resets.isEmpty) Error("Design must use RegInit (or explicitly use reset) to pass Verilog TB")

    // TODO: Support multiple clock domains! + multiple resets?
    if (clocks.size > 1) Error("Only 1 clock supported currently :( ")
    clocks foreach (clk => tb write "  always #(`CLK_PERIOD/2) %s = ~%s;\n".format(clk.name, clk.name))
    tb write "\n  initial begin\n"
    tb write "    #(`RESET_TIME - `CLK_DELTA)\n"
    tb write "    forever #`CLK_PERIOD cycle = cycle + 1;\n"
    tb write "  end\n\n"

    // Initialize inputs/outputs + setup DUT
    tb write "  // Module INPUTS\n"
    ins   foreach (node => tb write "  reg%s[%d:0] %s = 0;\n".format(isSigned(node)._2,node.getWidth-1, getIOName(node)))
    tb write "  // Module OUTPUTS\n"
    outs  foreach (node => tb write "  wire%s[%d:0] %s;\n".format(isSigned(node)._2,node.getWidth-1, getIOName(node)))
    tb write "\n  // DUT Instantiation\n"
    // TODO: Figure out why moduleName is "" when not explicitly set... This is ok for now b/c top level shouldn't have
    // any duplicates ?
    // moduleName = module entity name, name = instance name
    // tb write "  %s %s(\n".format(c.moduleName, c.name)
    tb write "  %s %s(\n".format(c.name, c.name)
    c.getClocks foreach (clk => tb write "    .%s(%s),\n".format(clk.name, clk.name))
    resets   foreach (rst => tb write "    .%s(%s),\n".format(rst.name, rst.name))
    tb write ((ins ++ outs) map (node => "    .%s(%s)".format(getIOName(node), getIOName(node))) mkString ",\n")
    tb write "\n  );\n\n"

    // Start dumping test vectors
    tb write "  initial begin\n"
    resets foreach (rst => tb write "\n    #`RESET_TIME %s = 0;\n".format(rst.name))

  }
  // Tester starts with reset (but don't count cycles during reset)
  super.reset(5)

  // TODO: Support alternative bases (besides hex)
  // TODO: Error if method argument = false (i.e. comparing two peeked results)

  /** Show/hide tester console outputs */
  def show(){traceOn = true}
  def hide(){traceOn = false}

  /** Differentiate treatment of signed, unsigned types -- Added ChiselDSP types. */
  override def signed_fix(dtype: Bits, rv: BigInt): BigInt = {
    val w = rv.bitLength.max(dtype.needWidth())
    val signrv = if(rv >= (BigInt(1) << w - 1)) (rv - (BigInt(1) << w)) else rv
    dtype match {
      /* Any "signed" node */
      case _: SInt | _ : Flo | _: Dbl | _: DSPDbl | _: Fixed | _: DSPFixed => signrv
      /* anything else (i.e., UInt) */
      case _ => rv
    }
  }
  
  /** Peek ChiselDSP types */
  def peek(data: DSPBool): Boolean = peek(data,traceOn,true)._2 > 0
  def peek(data: DSPUInt): BigInt = peek(data,traceOn,true)._2
  def peek(data: DSPQnm[_]): Double = peek(data,traceOn,true)._1
  
  /** Peek Chisel types */
  def peek(data: Bool): Boolean = peek(data,traceOn,true)._2 > 0
  def peek(data: UInt): BigInt = peek(data,traceOn,true)._2
  def peek(data: SInt): BigInt = peek(data,traceOn,true)._2
  override def peek(data: Dbl): Double = peek(data,traceOn,true)._1
  override def peek(data: Flo): Float = peek(data,traceOn,true)._1.floatValue
  def peek(data: Fixed): Double = peek(data,traceOn,true)._1
  override def peek(data: Bits): BigInt = peek(data,traceOn,true)._2

  /** Peek aggregate elements */
  override def peek(data: Aggregate): Array[BigInt] = peek(data,traceOn)
  def peek(data: Aggregate, disp: Boolean): Array[BigInt] = data.flatten map (x => peek(x._2,disp,true)._2)

  /** Peek complex*/
  def peek(data: ComplexBundle): ScalaComplex = peek(data,traceOn,true)._1
  private def peek(data: ComplexBundle, disp:Boolean, pk:Boolean): Tuple3[ScalaComplex,Array[BigInt],String] = {
    val res = data.flatten map (x => peek(x._2,false,pk))
    val names = data.flatten map (x => dumpName(x._2))
    val isLit = data.flatten.map(x => x._2.isLit).toList.reduce(_&&_)
    val realName = names.head.replace("_real","")
    val imagName = names.last.replace("_imag","")
    val name = if (isLit) "*Complex Lit*" else if (realName == imagName) realName else names.head + ", " + names.last
    val command = if (pk) "PEEK" else "POKE"
    val out = Complex(res.head._1,res.last._1)
    val outBits = Array(res.head._2,res.last._2)
    val msg = "  %s %s -> %s %s".format(command,name,out.toString,data.Q)
    if (disp) println(msg)
    (out,outBits,msg)
  }

  // TODO: peek vec of lits --> name = *Vec Lit*, peek Vec -- check that names before #'s all match, else print individually
  /** Convenient peek of a Vec of Bits */
  def peek[A <: Bits](data: Vec[A]): Array[BigInt] = peek(data,traceOn,true)._1
  private def peek[A <: Bits](data: Vec[A], disp:Boolean, pk:Boolean): Tuple2[Array[BigInt],String] = {
    val res = data.flatten.map(x => peek(x._2,false,pk)._2).reverse
    val names = data.flatten.map(x => dumpName(x._2)).reverse
    val name = names.head.replace("_0","")
    val command = if (pk) "PEEK" else "POKE"
    val msg = "  %s %s -> [%s]".format(command,name,res.mkString("\t"))
    if (disp) println(msg)
    (res,msg)
  }
  // TODO: Peek BaseN should reverse the order (0 is least significant digit, but should be on the right)

  /** Cluster some peek-specific processing */
  private def peekInit(data: Bits, peek: Boolean): Tuple2[BigInt,String] = {
    val res = super.peek(data)
    val ioName = getIOName(data)
    val isDbl = data match {
      case _: DSPDbl | _: Dbl | _: Flo => true
      case _ => false
    }

    if (verilogTester && data.isTopLevelIO && peek && !isDbl){
      val id = if (res >= 0) res.bitLength.toString + "\'d" else ""
      tb write "    `expect(\"%s\",%s,%s%d,cycle)\n".format(ioName,ioName,id,res)
    }
    val resBits = if (data.isLit || (data.dir == INPUT && data.isTopLevelIO)) signed_fix(data, res) else res
    val command = if (peek) "PEEK" else "POKE"
    val name = if (data.isLit) "*Lit*" else dumpName(data)
    val infoStart = "  %s %s -> ".format(command,name)
    (resBits, infoStart)
  }

  /** More general handling of peek for all possible data types */
  private def peek (data: Bits, disp: Boolean, peek: Boolean) : Tuple3[Double,BigInt,String] = {
    val (resBits, infoStart) = peekInit(data,peek)
    val s = if (resBits < 0) "-" else ""
    val hexString = if (hexOn) "(" + s + "0x%x) ".format(resBits.abs) else ""
    val (info,outDbl) =  data match {
      case f0: Flo => {
        val resFlo = intBitsToFloat(resBits.toInt)
        ("%f".format(resFlo), resFlo.toDouble)
      }
      case _: Dbl | _: DSPDbl => { 
        val resDbl = longBitsToDouble(resBits.longValue)
        val ext = data match {
          case d2: DSPDbl => " (%s)".format(d2.infoString)
          case _ => ""
        }
        ("%f".format(resDbl) + ext,resDbl)
      }
      case _: UInt | _: DSPUInt | _: SInt => {                                      // Double value unused
        val ext = data match {
          case u2: DSPUInt => "%s".format(u2.infoString)
          case _ => ""
        }
        ("%d %s".format(resBits,hexString) + ext,0.0)
      }
      case _: Fixed | _: DSPFixed => {                                              // Double value unused
        val (resDbl,ext) = data match {
          case f2: DSPFixed => (DSPFixed.toDouble(resBits.longValue, f2.getFracWidth) ," %s".format(f2.infoString))
          case f1: Fixed => (DSPFixed.toDouble(resBits.longValue, f1.getFractionalWidth),"")
        }
        ("%f %s".format(resDbl,hexString) + ext,resDbl)
      }
      case _ => ("0x%x (%d bit(s))".format(resBits,data.getWidth), 0.0)             // Double value unused
    }
    val msg = "%s".format(infoStart) + info
    if (disp) println(msg)
    (outDbl,resBits,msg)
  }

  /** Poke ChiselDSP types */
  def poke(node: DSPBool, x: Boolean) : Boolean = {
    val pokeRet = poke(node,int(x),traceOn)
    (pokeRet > 0)
  }
  def poke(node: DSPUInt, x: BigInt): BigInt = poke(node, x, traceOn)
  def poke(node: DSPQnm[_], x: Double): Double = poke(node, x, traceOn)
  def poke(node: Fixed, x: Double): Double = poke(node, x, traceOn)
  def poke(node: DSPFixed, x: Int): BigInt = poke(node, BigInt(x))
  def poke(node: DSPFixed, x: BigInt): BigInt = {
    if (node.getFracWidth != 0) Error("Poking DSPFixed (fractional width non-zero) with integer value")
    poke(node, x, traceOn)
  }
  
  /** Poke Chisel types */
  override def poke(node: Bits, x: Boolean) : Unit = poke(node, int(x), traceOn)
  override def poke(node: Bits, x: Int) : Unit = poke(node, BigInt(x), traceOn)
  override def poke(node: Dbl, x: Double) : Unit = poke(node, x, traceOn)
  override def poke(node: Flo, x: Float) : Unit = poke(node, x.doubleValue, traceOn)
  override def poke(node: Bits, x: Long) : Unit = poke(node, BigInt(x), traceOn)
  override def poke(node: Bits, x: BigInt) : Unit = poke(node, x, traceOn)

  /** Poke Complex */
  def poke(node: ComplexBundle, x: ScalaComplex): ScalaComplex = poke(node,x,traceOn)
  private def poke(node: ComplexBundle, x: ScalaComplex, disp:Boolean): ScalaComplex = {
    node match {
      case a: Complex[_] => {
        poke (a.real, x.real, false)
        poke (a.imag, x.imag, false)
        peek (node, disp, false)._1
      }
    }
  }

  /** Poke aggregate of Ints (for floating point values, it's the bit representation) */
  def poke(data: Aggregate, x: Array[Int]): Unit = poke(data, x.map(BigInt(_)))
  override def poke(data: Aggregate, x: Array[BigInt]): Unit = {
    data.flatten.zipWithIndex.foreach {case (elem, i) => poke(elem._2,x(i),traceOn)}
  }

  /** More general poke handling */
  private def poke(node:Bits, x: Double, disp:Boolean): Double = {
    node match {
      case d1: Dbl => super.poke(d1,x)
      case d2: DSPDbl => super.poke(node, BigInt(doubleToLongBits(x)))
      case f0: Flo => super.poke(f0,x.floatValue)
      case f1: Fixed => pokeTB(node, DSPFixed.toFixed(x,f1.getFractionalWidth))
      case f2: DSPFixed => pokeTB(node, DSPFixed.toFixed(x,f2.getFracWidth))
      case _ => Error("Poke value not valid for node type")
    }
    peek(node,disp,false)._1
  }
  /** x is the bit representation of the poked value */
  private def poke(node:Bits, x: BigInt, disp:Boolean): BigInt = {
    pokeTB(node,x)
    peek(node,disp,false)._2
  }

  /** Poke with VerilogTB */
  private def pokeTB(node:Bits, x: BigInt): Unit = {
    val unsignedBW = x.bitLength
    val neededWidth = if (isSigned(node)._1) unsignedBW + 1 else unsignedBW
    if (neededWidth > node.getWidth) Error("Poke value is not in the range of the input port")
    val ioName = getIOName(node)
    if (verilogTester && node.isTopLevelIO && node.dir == INPUT){
      val id = if (x >= 0) x.bitLength.toString + "\'d" else ""
      tb write "    %s = %s%d;\n".format(ioName,id,x)
    }
    super.poke(node,x)
  }

  /** Peek at the value of some memory at an index
    * @param data Memory to inspect
    * @param off Offset in memory to look at */
  override def peekAt[T <: Bits](data: Mem[T], off: Int): BigInt = {
    peekAt(data.asInstanceOf[Node],off)
  }
  def peekAt(data : Node, off: Int): BigInt = {
    data match {
      case _: Mem[_] =>
      case _ => Error("Must peekAt memory")
    }
    val value = peekNode(data, Some(off))
    if (traceOn) println(s"  PEEK ${dumpName(data)}[${off}] -> 0x${value.toString(16)}")
    value
  }
  /** Set the value of some memory
    * @param data The memory to write to
    * @param value The BigInt representing the bits to set
    * @param off The offset representing the index to write to memory
    */
  override def pokeAt[T <: Bits](data: Mem[T], value: BigInt, off: Int): Unit = {
    super.pokeAt(data,value,off)
    if (traceOn) println(s"  POKE ${dumpName(data)}[${off}] <- 0x${value.toString(16)}")
  }
  
  /** Step through tester n steps */
  def step() : Unit = step(1)
  override def step(n: Int) {
    val newT = t + n
    if (traceOn) println(s"STEP ${n}x -> ${newT}")
    super.step(n)
    if (verilogTester) tb write "    #(%d*`CLK_PERIOD) ".format(n)
    //t = t + n
  }
  
  /** Hold reset for n cycles */
  override def reset(n: Int = 1) {
    val newT = t + n
    if (traceOn) println(s"RESET ${n}x -> ${newT}")
    super.reset(n)
    incTime(n)
    // t = t + n
    //t += n
    if (verilogTester)
      resets foreach (rst => tb write "    %s = 1; #(%d*`CLK_PERIOD) %s = 0;\n".format(rst.name,n,rst.name))
  }

  /** Compare peek result with desired value in bit representation */
  override def expect (data: Bits, expected: BigInt, msg: => String): Boolean = expect(data,expected,"",msg)
  override def expect (data: Bits, expected: Int, msg: => String): Boolean = expect(data,BigInt(expected),"",msg)
  override def expect (data: Bits, expected: Long, msg: => String): Boolean = expect(data,BigInt(expected),"",msg)
  def expect(data: Bits, expected:Boolean): Boolean = expect(data,int(expected))
  override def expect(data: Bits, expected:Int): Boolean = expect(data,BigInt(expected))
  override def expect (data: Bits, expected: Long): Boolean = expect(data,BigInt(expected))
  override def expect(data: Bits, expected:BigInt): Boolean = expect(data,expected,"","")
  def expect(data: Bits, expected:BigInt, test: String, error:String): Boolean = {
    val (x,value,consolePeek) = peek(data,traceOn,true)
    if (value != expected) {
      if(!traceOn) println(consolePeek)
      handleError(expected.toString,test,error)
    }
    else true
  }

  /** Expects for aggregates */
  def expect(data: Aggregate, expected: Array[Int]): Boolean = expect(data,expected.map(BigInt(_)))
  override def expect(data: Aggregate, expected: Array[BigInt]):Boolean = expect(data,expected,"","")
  def expect(data: Aggregate, expected: Array[BigInt], test: String, error: String): Boolean = {
    var out = true
    data.flatten.zipWithIndex.foreach { case (elem, i) => {
      if(!expect(elem._2, expected(i), test, error)) out = false
    } }
    out
  }

  /** Expects for Vecs of Bits (bit representation) */
  def expect[A <: Bits](data: Vec[A], expected: Array[Int]): Boolean = expect(data,expected.map(BigInt(_)))
  def expect[A <: Bits](data: Vec[A], expected: Array[BigInt]):Boolean = expect(data,expected,"","")
  def expect[A <: Bits](data: Vec[A], expected: Array[BigInt], test: String, error: String): Boolean = {
    val (res,consolePeek) = peek(data,traceOn,true)
    val good = res.sameElements(expected)
    if (!good) {
      if (!traceOn) println(consolePeek)
      val expStr = "[" + expected.mkString("\t") + "]"
      handleError(expStr,test,error)
    }
    good
  }

  /** Test Dbl, Flo, DSPDbl, DSPFixed, Fixed outputs */
  override def expect (data: Flo, expected: Float, msg: => String): Boolean = expect(data,expected.toDouble, "",msg)
  override def expect (data: Dbl, expected: Double, msg: => String): Boolean = expect(data,expected, "",msg)
  override def expect(data: Flo, expected: Float) : Boolean = expect(data,expected.toDouble)
  override def expect(data: Dbl, expected: Double): Boolean = expect(data,expected,"","")
  def expect(data: Bits, expected: Double): Boolean = expect(data,expected,"","")
  def expect(data: Bits, expected: Double, test:String, error: String): Boolean = {
    val (dblVal,bitVal,consolePeek) = peek(data,traceOn,true)
    val (good,tolerance) = checkDecimal(data,expected,dblVal,bitVal)
    if (!good) {
      if (!traceOn) println(consolePeek)
      handleError(expected.toString,test,error + (if (!error.isEmpty) " " else "") + s"Tolerance = ${tolerance}")
    }
    good
  }

  /** Check values with tolerance */
  def checkDecimal(data: Bits, expected: Double, dblVal: Double, bitVal: BigInt): Tuple2[Boolean,Double] = {
    val fixTolBits = math.abs(DSPTester.fixTolBits)
    val fixTolInt = DSPUInt.toMax(fixTolBits)
    val floTolDec = math.abs(DSPTester.floTolDec)
    // Error checking does a bad job of handling really small numbers,
    // so let's just force the really small numbers to 0
    val expected0 = if (math.abs(expected) < floTolDec/100) 0.0 else expected
    val dblVal0 = if (math.abs(dblVal) < floTolDec/100) 0.0 else dblVal
    val expectedBits = data match {
      case d1: Dbl => BigInt(doubleToLongBits(expected0))
      case d2: DSPDbl => BigInt(doubleToLongBits(expected0))
      case f0: Flo => BigInt(floatToIntBits(expected0.floatValue))
      case f1: Fixed => DSPFixed.toFixed(expected0,f1.getFractionalWidth)
      case f2: DSPFixed => DSPFixed.toFixed(expected0,f2.getFracWidth)
      case _ => Error("Node type should be *Dbl, *Fixed, or Flo for expect"); BigInt(0)
    }

    // TODO: Should this be here? This will fail even if you do normal Dbl, Fixed (not gen)
    if (expectedBits.bitLength > data.getWidth-1) Error("Expected value is out of output node range")

    // Allow for some tolerance in error checking
    val (tolerance,tolDec) = data match {
      case f1: Fixed => (fixTolInt,DSPFixed.toDouble(fixTolInt,f1.getFractionalWidth))
      case f2: DSPFixed => (fixTolInt,DSPFixed.toDouble(fixTolInt,f2.getFracWidth))
      case _ => (BigInt(doubleToLongBits(floTolDec)),floTolDec)
    }
    val good = {
      if (dblVal0 != expected0) {
        val gotDiffDbl = math.abs(dblVal0-expected0)
        val gotDiffBits = (bitVal - expectedBits).abs
        val passDbl = gotDiffDbl <= tolDec
        val passBits = gotDiffBits <= tolerance
        passDbl & passBits
      }
      else true
    }
    (good,tolDec)
  }

  /** Compare Complex */
  def expect(data: ComplexBundle, expected: ScalaComplex): Boolean = expect(data,expected,"","")
  def expect[A <: DSPQnm[A]](data: ComplexBundle, expected: ScalaComplex, test:String, error: String): Boolean = {

    val (dblVal,bitVal,consolePeek) = peek(data,traceOn,true)
    val (goodR,toleranceR) = checkDecimal(data.asInstanceOf[Complex[A]].real,expected.real,dblVal.real,bitVal.head)
    val (goodI,toleranceI) = checkDecimal(data.asInstanceOf[Complex[A]].imag,expected.imag,dblVal.imag,bitVal.last)
    val good = goodR && goodI
    if (!good) {
      if (!traceOn) println(consolePeek)
      handleError(expected.toString,test,error + (if (!error.isEmpty) " " else "")
                  + s"Tolerance = [${toleranceR},${toleranceI}] LSBs")
    }
    good
  }

  /** Error handling */
  private def handleError(exp: String, test: String, error: String): Boolean = {
    println(Console.RED + "  >>>> Does not match " + exp + (if (!error.isEmpty) ", " else "") + error
            + ", Time = " + t + Console.RESET)
    fail
    if (!DSPTester.failedTests.contains(test) && test != "") DSPTester.failedTests = DSPTester.failedTests :+ test
    if (quitOnError) {println(Console.RED + "  Quitting on first error!");finish()}
    false
  }

  /** Complete the simulation and inspect all tests */
  override def finish() = {
    // Close out testbench
    if (verilogTester){
      tb write "\n    #`CLK_PERIOD $display(\"\\t **Ran through all test vectors**\"); $finish;\n"
      tb write "\n  end\n"
      tb write "endmodule"
      tb.close()
    }

    c match {
      case x: GenDSPModule[_] => Status("\n  Instance type: " + x.getType)
      case _ =>
    }

    if (DSPTester.failedTests.nonEmpty)
      println(Console.RED + Console.BOLD + "\n  Failed test cases: [" + DSPTester.failedTests.mkString(", ") + "]\n")
    else println("")

    super.finish
  }

}
