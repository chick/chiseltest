// See LICENSE for license details.

package chisel3.tester

import scala.collection.mutable
import scala.util.DynamicVariable

import org.scalatest._
import org.scalatest.exceptions.TestFailedException

import firrtl.ExecutionOptionsManager
import chisel3._
import chisel3.experimental.MultiIOModule

trait ChiselScalatestTester extends Assertions with TestSuiteMixin with TestEnvInterface { this: TestSuite =>
  // Provide test fixture data as part of 'global' context during test runs
  protected var scalaTestContext = new DynamicVariable[Option[NoArgTest]](None)

  abstract override def withFixture(test: NoArgTest): Outcome = {
    require(scalaTestContext.value == None)
    scalaTestContext.withValue(Some(test)) {
      super.withFixture(test)
    }
  }

  protected val batchedFailures = mutable.ArrayBuffer[TestFailedException]()

  // Stack trace data to help generate more informative (and localizable) failure messages
  protected var topFileName: Option[String] = None  // best guess at the testdriver top filename

  override def testerFail(msg: String): Unit = {
    batchedFailures += new TestFailedException(s"$msg", 4)
  }

  protected def getExpectDetailedTrace(trace: Seq[StackTraceElement], inFile: String): String = {
    val fullTrace = Context().backend.getParentTraceElements ++ trace

    // In the threading case, this needs to be overridden to trace through parent threads
    val lineNumbers = fullTrace.collect {
      case ste if ste.getFileName == inFile => ste.getLineNumber
    }.mkString(", ")
    if (lineNumbers.isEmpty()) {
      s" (no lines in $inFile)"
    } else {
      s" (lines in $inFile: $lineNumbers)"
    }
  }

  override def testerExpect(expected: Any, actual: Any, signal: String, msg: Option[String]): Unit = {
    if (expected != actual) {
      val appendMsg = msg match {
        case Some(msg) => s": $msg"
        case _ => ""
      }

      val trace = new Throwable
      val expectStackDepth = trace.getStackTrace.indexWhere(ste =>
        ste.getClassName == "chisel3.tester.package$testableData" && ste.getMethodName == "expect")
      require(expectStackDepth != -1,
        s"Failed to find expect in stack trace:\r\n${trace.getStackTrace.mkString("\r\n")}")

      val trimmedTrace = trace.getStackTrace.drop(expectStackDepth + 2)
      val detailedTrace = topFileName.map(getExpectDetailedTrace(trimmedTrace.toSeq, _)).getOrElse("")

      batchedFailures += new TestFailedException(
          s"$signal=$actual did not equal expected=$expected$appendMsg$detailedTrace",
          expectStackDepth + 1)
    }
  }

  override def checkpoint(): Unit = {
    // TODO: report multiple exceptions simultaneously
    for (failure <- batchedFailures) {
      throw failure
    }
  }

  private def runTest[T <: MultiIOModule](tester: BackendInstance[T])(testFn: T => Unit) {
    // Try and get the user's top-level test filename
    val internalFiles = Set("ChiselScalatestTester.scala", "BackendInterface.scala")
    val topFileNameGuess = (new Throwable).getStackTrace.apply(3).getFileName()
    if (internalFiles.contains(topFileNameGuess)) {
      println("Unable to guess top-level testdriver filename from stack trace")
      topFileName = None
    } else {
      topFileName = Some(topFileNameGuess)
    }

    batchedFailures.clear()

    Context.run(tester, this, testFn)
  }

  def getTestOptions(): TesterOptions = {
    val test = scalaTestContext.value.get
    TesterOptions(test.name, test.configMap.contains("writeVcd"))
  }

  // This should be the only user-called function
  def test[T <: MultiIOModule](dutGen: => T)(testFn: T => Unit) {
    runTest(Context.createDefaultTester(() => dutGen, getTestOptions(), None))(testFn)
  }

  def test[T <: MultiIOModule](dutGen: => T, execOptions: ExecutionOptionsManager)(testFn: T => Unit) {
    runTest(Context.createDefaultTester(() => dutGen, getTestOptions(), Some(execOptions)))(testFn)
  }
}
