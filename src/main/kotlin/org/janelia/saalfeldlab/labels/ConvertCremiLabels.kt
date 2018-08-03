package org.janelia.saalfeldlab.labels

import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.DataType
import org.janelia.saalfeldlab.n5.DatasetAttributes
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

private val USER_HOME = System.getProperty("user.home")

fun main(args: Array<String>) {

	val path = "$USER_HOME/Downloads/sample_A_padded_20160501.hdf"
	val dataset = "volumes/labels/neuron_ids"
	val numFillers = 9L
	val reader = N5HDF5Reader(path, 1250, 1250, 1)
	val img = N5Utils.open<UnsignedLongType>(reader, dataset)
	val imgDim = Intervals.dimensionsAsLongArray(img)
	println("imgDim=${imgDim.toList()}")

	val zMin = img.min(2)
	val zMax = img.max(2)

	val sections = mutableListOf<RandomAccessibleInterval<UnsignedLongType>>()
	val es = Executors.newFixedThreadPool(40)
	val futures = mutableListOf<Future<List<RandomAccessibleInterval<UnsignedLongType>>>>()

	for (i in zMin until 1)
	{
		println("Processing i=$i")
		futures.add( es.submit(Callable{
			val hs1 = Views.hyperSlice(img, 2, i)
			val hs2 = Views.hyperSlice(img, 2, i + 1)
			val fillers = InterpolateBetweenSections.makeFillers(numFillers, imgDim[0], imgDim[1])
			InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform(hs1, hs2, ArrayImgFactory(DoubleType()), *fillers)
			val localSections: List<RandomAccessibleInterval<UnsignedLongType>> = listOf(hs1) + fillers
			println("Processed i=$i")
			localSections
		} ))
	}
	for ( f in futures )
		sections.addAll(f.get())
	sections.add(Views.hyperSlice(img, 2, zMax))
	val stacked = Views.stack(sections)

	val blockSize = intArrayOf(64, 64, 64)
	val resolution = doubleArrayOf(4.0, 4.0, 40.0 / 10)
	val outputGroup = "$USER_HOME/local/tmp/sample_A_padded_20160501-interpolated-labels.n5"
	val writer = N5FSWriter(outputGroup)
	val attrs = DatasetAttributes(Intervals.dimensionsAsLongArray(stacked), blockSize, DataType.UINT64, GzipCompression())
	writer.createDataset(dataset, attrs)
	writer.setAttribute(dataset, "resolution", resolution)
	println("Writing data")
	N5Utils.save(stacked, writer, dataset, blockSize, GzipCompression(), es)

	es.shutdown()

}
