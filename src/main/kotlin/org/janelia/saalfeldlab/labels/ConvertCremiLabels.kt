package org.janelia.saalfeldlab.labels

import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.DataType
import org.janelia.saalfeldlab.n5.DatasetAttributes
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val USER_HOME = System.getProperty("user.home")

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

fun main(args: Array<String>) {

	val path = "$USER_HOME/Downloads/sample_A_padded_20160501.hdf"
	val dataset = "volumes/labels/neuron_ids"
	val numFillers = 2L
	val reader = N5HDF5Reader(path, 1250, 1250, 1)
	val img = N5Utils.open<UnsignedLongType>(reader, dataset)
	val imgDim = Intervals.dimensionsAsLongArray(img)
	println("imgDim=${imgDim.toList()}")

	val zMin = img.min(2)
	val zMax = img.max(2)

	val sections = mutableListOf<RandomAccessibleInterval<UnsignedLongType>>()
	val es = Executors.newFixedThreadPool(40)
	val futures = mutableListOf<Future<List<RandomAccessibleInterval<UnsignedLongType>>>>()

	val stopWatch = StopWatch.createStopped()



	val counter = Counter()
	stopWatch.start()
	for (i in zMin until zMax)
	{
		futures.add( es.submit(Callable{
			val sw = StopWatch.createAndStart()
			val hs1 = Views.hyperSlice(img, 2, i)
			val hs2 = Views.hyperSlice(img, 2, i + 1)
			val fillers = InterpolateBetweenSections.makeFillers(numFillers, imgDim[0], imgDim[1])
			InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform(hs1, hs2, ArrayImgFactory(DoubleType()), *fillers)
			val localSections: List<RandomAccessibleInterval<UnsignedLongType>> = listOf(hs1) + fillers
			sw.stop()
			counter.incrementCount { count, total -> ConvertCremiLabels.LOG.info("Finished interpolating {}/{} sections: {} in {} seconds", count, total, i, TimeUnit.SECONDS.convert(sw.nanoTime(), TimeUnit.NANOSECONDS)) }
			localSections
		} ))
		counter.incrementTotal()

	}
	for ( f in futures )
		sections.addAll(f.get())
	stopWatch.stop()

	println("Upsampled ${zMax - zMin + 1} sectpions with an additional ${numFillers} fillers between each pair of original sections in ${TimeUnit.SECONDS.convert(stopWatch.nanoTime(), TimeUnit.NANOSECONDS)} seconds")

	sections.add(Views.hyperSlice(img, 2, zMax))
	val stacked = Views.stack(sections)

	val blockSize = intArrayOf(64, 64, 64)
	val resolution = doubleArrayOf(4.0, 4.0, 40.0 / 10)
	val outputGroup = "$USER_HOME/local/tmp/sample_A_padded_20160501-interpolated-labels-2-additional-sections.n5"
	val writer = N5FSWriter(outputGroup)
	val attrs = DatasetAttributes(Intervals.dimensionsAsLongArray(stacked), blockSize, DataType.UINT64, GzipCompression())
	writer.createDataset(dataset, attrs)
	writer.setAttribute(dataset, "resolution", resolution)
	println("Writing data")
	stopWatch.start()
	N5Utils.save(stacked, writer, dataset, blockSize, GzipCompression(), es)

	es.shutdown()

	stopWatch.stop()

	println("Saved ${zMax - zMin + 1} sections with an additional ${numFillers} fillers between each pair of original sections in ${TimeUnit.SECONDS.convert(stopWatch.nanoTime(), TimeUnit.NANOSECONDS)} seconds")



}
