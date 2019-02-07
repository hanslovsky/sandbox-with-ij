package org.janelia.saalfeldlab.n5

import net.imglib2.algorithm.util.Grids
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import org.janelia.saalfeldlab.n5.imglib2.N5Utils

class DumpN5<T : NativeType<T>>(val reader: N5Reader, val dataset: String)  {

	fun dump() {
		val data = N5Utils.open<T>(reader, dataset)
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

}

fun main(args : Array<String>) {

	require(args.size == 2, {"Need to arguments: container and dataset"})

	val containerPath = args[0] //"/data/hanslovskyp/lauritzen/02/workspace.n5"
	val container = N5FSReader(containerPath)

	val dataset = args[1] // "raw/segmentations/multires/mc_glia_global2_multires/fragment-segment-assignment"

	require(container.datasetExists(dataset), {"Dataset $dataset does not exist in $containerPath"})

	when(container.getDatasetAttributes(dataset)) {
		DataType.UINT64 -> DumpN5<UnsignedLongType>(container, dataset)
		DataType.UINT32 -> DumpN5<UnsignedIntType>(container, dataset)
		DataType.UINT16 -> DumpN5<UnsignedShortType>(container, dataset)
		DataType.UINT8 -> DumpN5<UnsignedByteType>(container, dataset)
		DataType.INT64 -> DumpN5<LongType>(container, dataset)
		DataType.INT32 -> DumpN5<IntType>(container, dataset)
		DataType.INT16 -> DumpN5<ShortType>(container, dataset)
		DataType.INT8 -> DumpN5<ByteType>(container, dataset)
		DataType.FLOAT32 -> DumpN5<FloatType>(container, dataset)
		DataType.FLOAT64 -> DumpN5<DoubleType>(container, dataset)
	}
	val dumper = DumpN5<UnsignedLongType>(container, dataset)
	dumper.dump()

}
