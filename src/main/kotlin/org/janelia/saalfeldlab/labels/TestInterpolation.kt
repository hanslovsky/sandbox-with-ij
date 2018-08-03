package org.janelia.saalfeldlab.labels

import gnu.trove.map.hash.TLongIntHashMap
import net.imagej.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.neighborhood.HyperSphereShape
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.converter.readwrite.ARGBChannelSamplerConverter
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.view.Views
import java.util.*
import kotlin.math.roundToLong

private fun <I : IntegerType<I>> withRandomColors(
		source: RandomAccessibleInterval<I>,
		rng: Random = Random()
): RandomAccessibleInterval<ARGBType> {
	val colorMap = TLongIntHashMap()

	val converter = Converter<I, ARGBType> { s, t ->
		val k = s.getIntegerLong()
		if (!colorMap.contains(k)) colorMap.put(k, rng.nextInt())
		t.set(colorMap.get(k))
	}

	return Converters.convert(source, converter, ARGBType())
}

fun main(args: Array<String>) {
	val dim = longArrayOf(200, 100)
	println("${dim.asList()}")
	val img1 = ArrayImgs.unsignedLongs(*dim)
	val img2 = ArrayImgs.unsignedLongs(*dim)
	val interpolated1 = ArrayImgs.unsignedLongs(*dim)
	val interpolated2 = ArrayImgs.unsignedLongs(*dim)
	interpolated1.forEach({ it.set(-1) })
	interpolated2.forEach({ it.set(-1) })

	val rng = Random(100)
	val radii = longArrayOf(10, 5, 100, 25)
	val positions = arrayOf(
			longArrayOf(8, 7),
			longArrayOf(20, 14),
			longArrayOf(180, 70),
			longArrayOf(30, 50)
	)

	for (radiiAndPositions in longArrayOf(1, 2, 3, 4) zip (radii zip positions)) {
		val r1 = radiiAndPositions.second.first
		val r2 = (r1 * (1 + rng.nextGaussian() * 0.4)).roundToLong()
		val p = radiiAndPositions.second.second

		val sphere1 = HyperSphereShape(r1)
		val sphere2 = HyperSphereShape(r2)

		val access1 = sphere1.neighborhoodsRandomAccessible(Views.extendZero(img1)).randomAccess()
		val access2 = sphere1.neighborhoodsRandomAccessible(Views.extendZero(img2)).randomAccess()

		access1.setPosition(p)
		access2.setPosition(longArrayOf(p[0] + rng.nextInt(10) - 2, p[1] + rng.nextInt(10) - 2))

		val id = radiiAndPositions.first
		access1.get().forEach({ it.set(id) })
		access2.get().forEach({ it.set(id) })
	}

	InterpolateBetweenSections.interpolateBetweenSections(
			img1,
			img2,
			ArrayImgFactory(UnsignedLongType()),
			interpolated1,
			interpolated2
	)

	val ij = ImageJ()

	val stacked = Views.stack(img1, interpolated1, interpolated2, img2)
	val colored = withRandomColors(stacked, rng = rng)
	val withChannels = Views.stack(
			Converters.convert(colored, ARGBChannelSamplerConverter(0)),
			Converters.convert(colored, ARGBChannelSamplerConverter(1)),
			Converters.convert(colored, ARGBChannelSamplerConverter(2))
	)
	ij.ui().showUI()
	ij.ui().show("colored", withChannels)


}
