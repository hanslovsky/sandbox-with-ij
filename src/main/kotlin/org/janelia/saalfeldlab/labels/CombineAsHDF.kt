package org.janelia.saalfeldlab.labels

import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.view.Views
import org.janelia.saalfeldlab.labels.downsample.WinnerTakesAll
import org.janelia.saalfeldlab.n5.DataType
import org.janelia.saalfeldlab.n5.DatasetAttributes
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSReader
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.invoke.MethodHandles
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.stream.DoubleStream
import java.util.stream.LongStream
import java.util.stream.StreamSupport

private val USER_HOME = System.getProperty("user.home")

class CombineAsHDF {
	companion object {

		private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

		fun run(args: Array<String>) {
			val groundTruthContainer = "$USER_HOME/local/tmp/sample_A_padded_20160501-interpolated-labels-2-additional-sections.n5"
			val groundTruthDataset = "volumes/labels/neuron_ids"
			val grundTruthN5 = N5FSReader(groundTruthContainer)
			val groundTruth = N5Utils.open<UnsignedLongType>(grundTruthN5, groundTruthDataset)
			val groundTruthAttributes = grundTruthN5.getDatasetAttributes(groundTruthDataset)

			val rawContainer = "/home/hanslovskyp/Downloads/sample_A_padded_20160501.hdf"
			val rawDataset = "volumes/raw"
			val rawN5 = N5HDF5Reader(rawContainer, 64, 64, 64)
			val raw = N5Utils.open<UnsignedByteType>(rawN5, rawDataset)
			val rawAttributes = rawN5.getDatasetAttributes(rawDataset)

			val scaleFactor = 3
			val voxelSizeFactor = scaleFactor.toDouble() * 3.0
			val groundTruthResolution = DoubleStream.of(40.0 / scaleFactor.toDouble(), 4.0, 4.0).map { it * voxelSizeFactor }.toArray()
			val groundTruthOffset = DoubleStream.of(1520.0, 3644.0, 3644.0).map { it * voxelSizeFactor }.toArray()
			groundTruthOffset[0] = groundTruthOffset[0] - groundTruthResolution[0] / scaleFactor.toDouble()

			val rawResolution = DoubleStream.of(40.0, 4.0, 4.0).map { it * voxelSizeFactor }.toArray()
			val rawOffset = doubleArrayOf(0.0, 0.0, 0.0)

			LOG.info("raw resolution: {}", rawResolution)
			LOG.info("raw offset:     {}", rawOffset)

			LOG.info("gt resolution:  {}", groundTruthResolution)
			LOG.info("gt offset:      {}", groundTruthOffset)

			val outputDir = "/groups/saalfeld/home/hanslovskyp/data/cremi/training"
			File(outputDir).mkdirs()
			val targetContainer = "$outputDir/sample_A_padded_20160501-2-additional-sections-fixed-offset.h5"
			LOG.info("target container: ${targetContainer}")
			val targetN5 = N5HDF5Writer(targetContainer, 64, 64, 64)
			targetN5.createDataset(rawDataset, rawAttributes)
			targetN5.createDataset(groundTruthDataset, groundTruthAttributes)

			val es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 3)
			val sw = StopWatch.createAndStart()
			val downScaledDim = longArrayOf(
					Math.ceil(groundTruth.dimension(0) / scaleFactor.toDouble()).toLong(),
					Math.ceil(groundTruth.dimension(1) / scaleFactor.toDouble()).toLong())

			sw.start()
			val downsampledSectionFutures = LongStream
					.range(0, groundTruth.dimension(2))
					.mapToObj { Views.hyperSlice(groundTruth, 2, it) }
					.map { Views.extendValue(it, UnsignedLongType(Label.INVALID)) }
					.map {
						val input = it;
						val future = es.submit(Callable {
							val output = ArrayImgs.unsignedLongs(*downScaledDim)
							WinnerTakesAll.downsample(input, output, scaleFactor, scaleFactor)
							output as RandomAccessibleInterval<UnsignedLongType>
						})
						future
					}
					.collect(Collectors.toList())
			val downsampledSections = downsampledSectionFutures
					.stream()
					.map { it.get() as RandomAccessibleInterval<UnsignedLongType> }
					.collect(Collectors.toList())
			val downsampled = Views.stack(downsampledSections)
			sw.stop()
			LOG.info("Finished downsampling in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			val downsampledAttributes = DatasetAttributes(
					Intervals.dimensionsAsLongArray(downsampled),
					intArrayOf(64, 64, 64),
					DataType.UINT64,
					GzipCompression())
			val downsampledDataset = "$groundTruthDataset-downsampled"
			targetN5.createDataset(downsampledDataset, downsampledAttributes)
			val downsampledResolution = groundTruthResolution.clone()
			downsampledResolution[1] = downsampledResolution[1] * scaleFactor
			downsampledResolution[2] = downsampledResolution[2] * scaleFactor
			val downSampledOffset = groundTruthOffset.clone()
			// TODO how to set correct offset? Is this the right thing to do?
			downSampledOffset[1] = downSampledOffset[1] + downsampledResolution[1] / scaleFactor.toDouble()
			downSampledOffset[2] = downSampledOffset[2] + downsampledResolution[2] / scaleFactor.toDouble()

			sw.start()
			N5Utils.save(downsampled, targetN5, downsampledDataset, downsampledAttributes.blockSize, downsampledAttributes.compression, es)
			sw.stop()
			LOG.info("Finished saving downsampled data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			sw.start()
			N5Utils.save(raw, targetN5, rawDataset, rawAttributes.blockSize, rawAttributes.compression, es)
			sw.stop()
			LOG.info("Finished copying raw data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")
			sw.start()
			N5Utils.save(groundTruth, targetN5, groundTruthDataset, groundTruthAttributes.blockSize, groundTruthAttributes.compression, es)
			sw.stop()
			LOG.info("Finished copying label data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")
			es.shutdown()

			sw.start()
			val maxId = StreamSupport
					.stream(Views.flatIterable(groundTruth).spliterator(), false)
					.mapToLong(UnsignedLongType::getIntegerLong).reduce { l1, l2 -> Math.max(l1, l2) }
			sw.stop()

			LOG.info("maxId=$maxId in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			targetN5.setAttribute(rawDataset, "resolution", rawResolution)
			targetN5.setAttribute(rawDataset, "offset", rawOffset)
			targetN5.setAttribute(groundTruthDataset, "resolution", groundTruthResolution)
			targetN5.setAttribute(groundTruthDataset, "offset", groundTruthOffset)
			targetN5.setAttribute(groundTruthDataset, "maxId", maxId.asLong)
			targetN5.setAttribute(downsampledDataset, "resolution", downsampledResolution)
			targetN5.setAttribute(downsampledDataset, "offset", downSampledOffset)
			targetN5.setAttribute(downsampledDataset, "maxId", maxId.asLong)

		}
	}
}

fun main(args: Array<String>) {
	CombineAsHDF.run(args)
}
