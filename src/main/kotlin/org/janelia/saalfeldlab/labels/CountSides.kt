package org.janelia.saalfeldlab.labels

import net.imagej.Dataset
import net.imagej.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.gauss3.Gauss3
import net.imglib2.algorithm.gradient.PartialDerivative
import net.imglib2.algorithm.hough.HoughTransforms
import net.imglib2.converter.Converters
import net.imglib2.converter.RealDoubleConverter
import net.imglib2.img.array.ArrayImgs
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import java.nio.file.Paths
import java.util.function.BiConsumer
import java.util.function.Predicate

private fun computeLength(position: LongArray): Double {
	var dist = 0.0

	for (d in position.indices) {
		val pos = position[d]

		dist += (pos * pos).toDouble()
	}

	return Math.sqrt(dist)
}

fun <T, U> voteLines(
		input: RandomAccessibleInterval<T>,
		votespace: RandomAccessibleInterval<U>,
		nTheta: Int,
		nRho: Int,
		filter: Predicate<T>,
		sum: BiConsumer<T, U>) {

	val dims = LongArray(input.numDimensions())
	input.dimensions(dims)

	val minRho = -computeLength(dims)
	val dRho = 2 * computeLength(dims) / nRho

	val minTheta = -Math.PI / 2
	val dTheta = Math.PI / nTheta

	val cTheta = DoubleArray(nTheta)
	val sTheta = DoubleArray(nTheta)

	// create cos(theta) LUT
	for (t in 0 until nTheta) {
		cTheta[t] = Math.cos(dTheta * t + minTheta)
	}

	// create sin`(theta) LUT
	for (t in 0 until nTheta) {
		sTheta[t] = Math.sin(dTheta * t + minTheta)
	}

	val imageCursor = Views.iterable(Views.zeroMin(input)).localizingCursor()
	val outputRA = votespace.randomAccess()

	while (imageCursor.hasNext()) {
		imageCursor.fwd()

		if (filter.test(imageCursor.get())) {
			val x = imageCursor.getDoublePosition(0)
			val y = imageCursor.getDoublePosition(1)

			for (t in 0 until nTheta) {
				val fRho = cTheta[t] * x + sTheta[t] * y
				val r = Math.round((fRho - minRho) / dRho)

				// place vote
				outputRA.setPosition(r, 0)
				outputRA.setPosition(t, 1)
				sum.accept(imageCursor.get(), outputRA.get())
			}
		}
	}
}

fun main(args: Array<String>) {
	val homeDir = System.getProperty("user.home")
	val path = Paths.get("${homeDir}", "polygon.png")


	val ij = ImageJ()

	val dataset = ij.io().open(path.toAbsolutePath().toString()) as Dataset
	val img = Converters.convert(dataset.imgPlus.img as RandomAccessibleInterval<UnsignedByteType>, RealDoubleConverter(), DoubleType())
	val dim = Intervals.dimensionsAsLongArray(img)
	val smooth = ArrayImgs.doubles(*dim)
	val dx = ArrayImgs.doubles(*dim)
	val dy = ArrayImgs.doubles(*dim)
	val gradientMagnitude = ArrayImgs.doubles(*dim)
	Gauss3.gauss(0.8, Views.extendBorder(img), smooth)
	PartialDerivative.gradientCentralDifference(Views.extendBorder(smooth), dx, 0)
	PartialDerivative.gradientCentralDifference(Views.extendBorder(smooth), dy, 1)
	LoopBuilder.setImages(dx, dy, gradientMagnitude).forEachPixel(LoopBuilder.TriConsumer { x, y, sum -> sum.set(Math.sqrt(x.realDouble * x.realDouble + y.realDouble * y.realDouble)) })

	val nTheta = 400//HoughTransforms.DEFAULT_THETA * 5
	val nRho = 150
	val voteSpaceSize = HoughTransforms.getVotespaceSize(nRho, nTheta)
	val voteSpace = ArrayImgs.unsignedInts(*voteSpaceSize)
	HoughTransforms.voteLines(gradientMagnitude, voteSpace, voteSpaceSize[1].toInt(), voteSpaceSize[0].toInt(), Predicate { it.realDouble > 35 })
	val peaks = HoughTransforms.pickLinePeaks(voteSpace, 75L)
	println("Got ${peaks.size} peaks: $peaks")

	ij.ui().showUI()
	ij.ui().show("polygon", dataset)
	ij.ui().show("smoothed", smooth)
	ij.ui().show("dx", dx)
	ij.ui().show("dy", dy)
	ij.ui().show("gradient magnitude", gradientMagnitude)
	ij.ui().show("vote space", voteSpace)

}
