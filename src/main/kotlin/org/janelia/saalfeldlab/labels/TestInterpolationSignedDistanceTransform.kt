package org.janelia.saalfeldlab.labels

import gnu.trove.map.hash.TLongIntHashMap
import net.imagej.ImageJ
import net.imagej.display.ImageDisplay
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.morphology.distance.DistanceTransform
import net.imglib2.algorithm.neighborhood.HyperSphereShape
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.integer.LongType
import net.imglib2.type.numeric.real.DoubleType
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
	val img1 = ArrayImgs.unsignedLongs(*dim)
	val img2 = ArrayImgs.unsignedLongs(*dim)
	val numSteps = 100
	val interpolations = InterpolateBetweenSections.makeFillers(numSteps.toLong(), *dim)
	interpolations.forEach { Views.flatIterable(it).forEach { it.set(-1) } }

	val rng = Random(100)
	val radii = longArrayOf(
			10,
			5,
			100,
			25
	)
	val positions = arrayOf(
			longArrayOf(8, 7),
			longArrayOf(20, 14),
			longArrayOf(180, 70),
			longArrayOf(30, 50)
	)

	for (radiiAndPositions in longArrayOf(1, 2, 3, 4) zip (radii zip positions)) {
		val r1 = radiiAndPositions.second.first
		val r2 = (r1 * (1 + rng.nextGaussian() * 0.2)).roundToLong()
		val p = radiiAndPositions.second.second

		val sphere1 = HyperSphereShape(r1)
		val sphere2 = HyperSphereShape(r2)

		val access1 = sphere1.neighborhoodsRandomAccessible(Views.extendZero(img1)).randomAccess()
		val access2 = sphere2.neighborhoodsRandomAccessible(Views.extendZero(img2)).randomAccess()

		access1.setPosition(p)
		access2.setPosition(longArrayOf(p[0] + rng.nextInt(11) - 5, p[1] + rng.nextInt(11) - 5))

		val id = radiiAndPositions.first
		access1.get().forEach { it.set(id) }
		access2.get().forEach { it.set(id) }
	}

	InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform(
			img1,
			img2,
			ArrayImgFactory(DoubleType()),
			*interpolations,
			distanceType = DistanceTransform.DISTANCE_TYPE.EUCLIDIAN,
			background = 0
	)

	val ij = ImageJ()

	val stacked = Views.stack(*(listOf(img1) + interpolations + listOf(img2)).toTypedArray())
	ij.ui().showUI()
	val display = ij.display().createDisplay("stacked", Converters.convert(stacked, { s, t -> t.set(s.get()) }, LongType()))
	val lutName = "NCSA PalEdit/16_colors.lut"
	val lut = ij.lut().loadLUT(ij.lut().findLUTs().get(lutName)!!)!!
	ij.lut().applyLUT(lut, display as ImageDisplay)


}
