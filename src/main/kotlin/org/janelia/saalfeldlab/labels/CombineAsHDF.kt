package org.janelia.saalfeldlab.labels

import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.util.StopWatch
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.N5FSReader
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.DoubleStream
import java.util.stream.StreamSupport

private val USER_HOME = System.getProperty("user.home")

public class CombineAsHDF
{
	companion object {
	    fun run(args: Array<String>)
		{
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

			val voxelSizeFactor = 3.0
			val groundTruthResolution = DoubleStream.of(40.0 / voxelSizeFactor, 4.0, 4.0).map { it * voxelSizeFactor }.toArray()
			val groundTruthOffset = DoubleStream.of(1520.0, 3644.0, 3644.0).map { it * voxelSizeFactor }.toArray()

			val rawResolution = DoubleStream.of(40.0, 4.0, 4.0).map { it * voxelSizeFactor }.toArray()
			val rawOffset = doubleArrayOf(0.0, 0.0, 0.0)

			val targetContainer = "/groups/saalfeld/home/hanslovskyp/sample_A_padded_20160501-2-additional-sections.h5"
			val targetN5 = N5HDF5Writer(targetContainer, 64, 64, 64)
			targetN5.createDataset(rawDataset, rawAttributes)
			targetN5.createDataset(groundTruthDataset, groundTruthAttributes)

			val es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 3)
			val sw = StopWatch()
			sw.start()
			N5Utils.save(raw, targetN5, rawDataset, rawAttributes.blockSize, rawAttributes.compression, es)
			sw.stop()
			println("Finished copying raw data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")
			sw.start()
			N5Utils.save(groundTruth, targetN5, groundTruthDataset, groundTruthAttributes.blockSize, groundTruthAttributes.compression, es)
			sw.stop()
			println("Finished copying label data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")
			es.shutdown()

			sw.start()
			val maxId = StreamSupport.stream(Views.flatIterable(groundTruth).spliterator(), false).mapToLong(UnsignedLongType::getIntegerLong).reduce { l1, l2 -> Math.max(l1, l2) }
			sw.stop()

			println("maxId=$maxId in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			targetN5.setAttribute(rawDataset, "resolution", rawResolution)
			targetN5.setAttribute(rawDataset, "offset", rawOffset)
			targetN5.setAttribute(groundTruthDataset, "resolution", groundTruthResolution)
			targetN5.setAttribute(groundTruthDataset, "offset", groundTruthOffset)
			targetN5.setAttribute(groundTruthDataset, "maxId", maxId.asLong)

		}
	}
}

fun main(args: Array<String>) {
	CombineAsHDF.run(args)
}
