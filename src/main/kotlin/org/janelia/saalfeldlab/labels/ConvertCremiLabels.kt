package org.janelia.saalfeldlab.labels

import net.imglib2.RandomAccessibleInterval
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.*
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

private val USER_HOME = System.getProperty("user.home")

private val DM11_HOME = "/groups/saalfeld/home/${System.getProperty("user.name")}"

class ConvertCremiLabels
{
	companion object {
	    val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
	}
}

private class Counter()
{
	private val count = AtomicInteger()

	private val total = AtomicInteger()

	fun incrementTotal()
	{
		total.incrementAndGet()
	}

	fun incrementCount(logger: (Int, Int) -> Unit)
	{
		logger(count.incrementAndGet(), total.get())
	}
}

fun <T> convert (
		containerIn: N5Reader,
		containerOut: N5Writer,
		datasetIn: String,
		datasetOut: String,
		numFillers: Long,
		es: ExecutorService): Unit
		where T: IntegerType<T>,
			  T: NativeType<T> {

	val img = N5Utils.open<T>(containerIn, datasetIn)
	val imgDim = Intervals.dimensionsAsLongArray(img)
	ConvertCremiLabels.LOG.info("Up-sampling dataset {} with dimensions {}", datasetIn, imgDim.toList())

	val zMin = img.min(2)
	val zMax = img.max(2)

	val sections = mutableListOf<RandomAccessibleInterval<UnsignedLongType>>()
	val futures = mutableListOf<Future<List<RandomAccessibleInterval<UnsignedLongType>>>>()

	val imgAsUnsignedLongTypes = Converters.convert(img, { src, tgt -> tgt.setInteger(src.integerLong)}, UnsignedLongType())

	val stopWatch = StopWatch.createStopped()


	val counter = Counter()
	stopWatch.start()
	for (i in zMin until zMax) {
		futures.add(es.submit(Callable {
			val sw = StopWatch.createAndStart()
			val hs1 = Views.hyperSlice(imgAsUnsignedLongTypes, 2, i)
			val hs2 = Views.hyperSlice(imgAsUnsignedLongTypes, 2, i + 1)
			val fillers = InterpolateBetweenSections.makeFillers(numFillers, imgDim[0], imgDim[1])
			InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform(hs1, hs2, ArrayImgFactory(DoubleType()), *fillers)
			val localSections: List<RandomAccessibleInterval<UnsignedLongType>> = listOf(hs1) + fillers
			sw.stop()
			counter.incrementCount { count, total -> ConvertCremiLabels.LOG.info("Finished interpolating {}/{} sections: {} in {} seconds", count, total, i, TimeUnit.SECONDS.convert(sw.nanoTime(), TimeUnit.NANOSECONDS)) }
			localSections
		}))
		counter.incrementTotal()

	}
	for (f in futures)
		sections.addAll(f.get())
	stopWatch.stop()

	ConvertCremiLabels.LOG.info(
			"Up-sampled {}: {} sections with an additional {} fillers between each pair of original sections in {} seconds",
			datasetIn,
			zMax - zMin + 1,
			numFillers,
			TimeUnit.SECONDS.convert(stopWatch.nanoTime(), TimeUnit.NANOSECONDS)
	)

	sections.add(Views.hyperSlice(imgAsUnsignedLongTypes, 2, zMax))
	val stacked = Views.stack(sections)

	val blockSize = intArrayOf(64, 64, 64)

	val resolution = containerIn.getAttribute(datasetIn, "resolution", DoubleArray::class.java) ?: doubleArrayOf(4.0, 4.0, 40.0)
	resolution[2] =  resolution[2] / (1 + numFillers)
	val offset = containerIn.getAttribute(datasetIn, "offset", DoubleArray::class.java) ?: doubleArrayOf(0.0, 0.0, 0.0)

	val attrs = DatasetAttributes(Intervals.dimensionsAsLongArray(stacked), blockSize, DataType.UINT64, GzipCompression())
	containerOut.createDataset(datasetOut, attrs)
	containerOut.setAttribute(datasetOut, "resolution", resolution)
	containerOut.setAttribute(datasetOut, "offset", offset)
	ConvertCremiLabels.LOG.info("Writing data for {}", datasetOut)
	stopWatch.start()
	N5Utils.save(stacked, containerOut, datasetOut, blockSize, GzipCompression(), es)

	stopWatch.stop()

	ConvertCremiLabels.LOG.info(
			"Saved {}: {} sections with an additional {} fillers between each pair of original sections in {} seconds",
			datasetOut,
			zMax - zMin + 1,
			numFillers,
			TimeUnit.SECONDS.convert(stopWatch.nanoTime(), TimeUnit.NANOSECONDS))

}

fun main(args: Array<String>) {

//	val path = "$USER_HOME/Downloads/sample_A_padded_20160501.hdf"
	val identifiers = arrayOf("A", "B", "C")//, "0", "1", "2")
	for (identifier in identifiers) {
		val path = "$DM11_HOME/data/from-arlo/sample_$identifier.h5"
		val datasets = arrayOf(
				"volumes/labels/glia",
				"volumes/labels/glia_noneurons",
				"volumes/labels/mask",
				"volumes/labels/neuron_ids",
				"volumes/labels/neuron_ids_noglia")
		val outputGroup = "$DM11_HOME/data/from-arlo/interpolated/sample_$identifier-interpolated-labels-2-additional-sections.n5"

		ConvertCremiLabels.LOG.info("Up-sampling datasets for container {}: {}", path, datasets)

		val sw = StopWatch.createAndStart()
		for (dataset in datasets) {

			val inputAttributes = N5HDF5Reader(path).getDatasetAttributes(dataset)
			val width = inputAttributes.dimensions[0]
			val height = inputAttributes.dimensions[1]
			val numFillers = 2L
			val reader = N5HDF5Reader(path, width.toInt(), height.toInt(), 1)
			val writer = N5FSWriter(outputGroup)

			val es = Executors.newFixedThreadPool(40)

			when (inputAttributes.dataType) {
				DataType.UINT64 -> convert<UnsignedLongType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.UINT32 -> convert<UnsignedIntType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.UINT16 -> convert<UnsignedShortType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.UINT8 -> convert<UnsignedByteType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.INT64 -> convert<LongType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.INT32 -> convert<IntType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.INT16 -> convert<ShortType>(reader, writer, dataset, dataset, numFillers, es)
				DataType.INT8 -> convert<ByteType>(reader, writer, dataset, dataset, numFillers, es)
				else -> ConvertCremiLabels.LOG.warn("Non-integer dataType {} not supported -- skipping {}", inputAttributes.dataType, dataset)
			}


			es.shutdown()

		}
		sw.stop()
		ConvertCremiLabels.LOG.info("Finished up-sampling datasets {} in container {} in {} seconds", datasets, path, sw.seconds())
	}


}
