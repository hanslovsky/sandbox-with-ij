package org.janelia.saalfeldlab.n5

import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.util.Grids
import net.imglib2.transform.integer.MixedTransform
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import net.imglib2.view.MixedTransformView
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.lang.invoke.MethodHandles
import java.util.concurrent.Callable

class DumpN5<T : NativeType<T>>(
		val reader: N5Reader,
		val dataset: String,
		val transposeAxes: Boolean)  {

	companion object {
	    val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
	}

	fun dump() {
		val data = if (transposeAxes) transpose(N5Utils.open<T>(reader, dataset)) else N5Utils.open<T>(reader, dataset)
		val access = data.randomAccess()
		data.min(access)
		val min = Intervals.minAsLongArray(data)
		val max = Intervals.maxAsLongArray(data)
		val blockSize = IntArray(min.size, {1})

		val fillerStrings = Array(min.size, {if (it == 0) " " else "\n".repeat(it)})

		Grids.forEachOffset(
				min,
				max,
				blockSize,
				{pos, dim -> access.setPosition(pos, dim)},
				access::getLongPosition,
				{step, dim -> access.move(step, dim); print(fillerStrings[dim])},
				{print(access.get())})

	}

	private fun <U> transpose(rai: RandomAccessibleInterval<U>): RandomAccessibleInterval<U> {
		val n = rai.numDimensions()
		val component = IntArray(n, {n - 1 - it})
		val t = MixedTransform(n, n)
		t.setComponentMapping(component)
		return Views.interval(
				MixedTransformView(rai, t),
				Intervals.minAsLongArray(rai).reversedArray(),
				Intervals.maxAsLongArray(rai).reversedArray())
	}

}


@CommandLine.Command(name = "dump-n5")
private class Args : Callable<Unit> {

	@CommandLine.Parameters(index="0", arity = "1", paramLabel = "CONTAINER", description = arrayOf("Path to N5 FS container"))
	var containerPath: String? = null

	@CommandLine.Parameters(index = "1", arity = "1", paramLabel = "DATASET", description = arrayOf("Dataset inside CONTAINER"))
	var dataset: String? = null

	@CommandLine.Option(names = arrayOf("--transpose-axes", "-t"))
	var transposeAxes = false

	@CommandLine.Option(names = arrayOf("--help", "-h"), usageHelp = true)
	var helpRequested = false

	var dataType: DataType? = null

	var n5: N5Reader? = null

	var parsedSuccessFully = true

	override fun call() {
		try {
			this.n5 = N5FSReader(containerPath)
			require(n5!!.datasetExists(dataset), { "Dataset $dataset does not exist in $containerPath" })
			this.dataType = this.n5?.getDatasetAttributes(this.dataset)?.dataType
		} catch(e: IllegalArgumentException) {
			DumpN5.LOG.error("Illegal argument: {}", e.message)
			parsedSuccessFully = false
		}

	}

}

fun main(argv : Array<String>) {

	val args = Args()
	CommandLine.call(args, *argv)

	if (args.helpRequested)
		return

	if (!args.parsedSuccessFully)
		System.exit(1)

	val container = args.n5!!
	val dataset = args.dataset!!

	when (args.dataType!!) {
		DataType.UINT64 -> DumpN5<UnsignedLongType>(container, dataset, args.transposeAxes)
		DataType.UINT32 -> DumpN5<UnsignedIntType>(container, dataset, args.transposeAxes)
		DataType.UINT16 -> DumpN5<UnsignedShortType>(container, dataset, args.transposeAxes)
		DataType.UINT8 -> DumpN5<UnsignedByteType>(container, dataset, args.transposeAxes)
		DataType.INT64 -> DumpN5<LongType>(container, dataset, args.transposeAxes)
		DataType.INT32 -> DumpN5<IntType>(container, dataset, args.transposeAxes)
		DataType.INT16 -> DumpN5<ShortType>(container, dataset, args.transposeAxes)
		DataType.INT8 -> DumpN5<ByteType>(container, dataset, args.transposeAxes)
		DataType.FLOAT32 -> DumpN5<FloatType>(container, dataset, args.transposeAxes)
		DataType.FLOAT64 -> DumpN5<DoubleType>(container, dataset, args.transposeAxes)
	}.dump()
}
