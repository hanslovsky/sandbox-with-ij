package org.janelia.saalfeldlab.labels

import ij.ImageJ
import ij.io.FileSaver
import net.imglib2.RandomAccessible
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.neighborhood.HyperSphereNeighborhood
import net.imglib2.algorithm.neighborhood.RectangleShape
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.Type
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.view.Views
import java.util.stream.LongStream

private val dims = longArrayOf(130, 120, 115)

private val SPAN = 24
private val BOX_POS = longArrayOf(63, 58, 78)

private val RADIUS = 35L
private val CENTER = LongStream.of(*BOX_POS).map {it + 5}.toArray()

private class Box(val span: Int, vararg val pos: Long) {

	fun <T: Type<T>> fillInto(ra: RandomAccessible<T>, t: T) {
		val access = RectangleShape(span, false).neighborhoodsRandomAccessible(ra).randomAccess()
		access.setPosition(pos)
		access.get().forEach {it.set(t)}
	}
}

private class Sphere(val radius: Long, vararg val center: Long) {

	fun <T: Type<T>> fillInto(ra: RandomAccessible<T>, t: T) {
		HyperSphereNeighborhood
				.factory<T>()
				.create(center, radius, ra.randomAccess())
				.forEach {it.set(t)}
	}
}

fun main(args: Array<String>) {
	val data1 = ArrayImgs.unsignedLongs(*dims) as RandomAccessibleInterval<UnsignedLongType>
	val data2 = ArrayImgs.unsignedLongs(*dims) as RandomAccessibleInterval<UnsignedLongType>
	Sphere(RADIUS, *CENTER).fillInto(Views.extendZero(data1), UnsignedLongType(255))
	Box(SPAN, *BOX_POS).fillInto(Views.extendZero(data2), UnsignedLongType(255))
	val fillers = InterpolateBetweenSections.makeFillers(10, *dims)

	InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform(
			data1,
			data2,
			ArrayImgFactory(DoubleType()),
			*fillers
	)

	val timeSeries = arrayOf(data1) + fillers + arrayOf(data2)

	ImageJ()
	ImageJFunctions.show(Views.stack(*timeSeries), "blub")
	val pattern = "/data/hanslovskyp/sphere-box/%02d.tif"

	for ((t, volume) in timeSeries.withIndex()) {
		print("saving image $t")
		val path = pattern.format(t)
		val imp = ImageJFunctions.wrap(volume, "t=%d".format(t)).duplicate()
		FileSaver(imp).saveAsTiff(path)
	}


}
