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
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.integer.ByteType
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.LongType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.scijava.command.Command
import org.scijava.display.DisplayService
import org.scijava.log.LogService
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
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


@Plugin(type = Command::class, headless = false, name = "Interpolate Labels")
class TestInterpolationSignedDistanceTransformPlugin: Command
{

	@Parameter(label="img1", description = "WAS MAN")
	var rai1: RandomAccessibleInterval<out IntegerType<*>>? = null

	@Parameter(label="img2", description = "WAS MAN")
	var rai2: RandomAccessibleInterval<out IntegerType<*>>? = null

	@Parameter(label="weight", min="0.0", max="1.0")
	var weight: Double = 0.5

	@Parameter(label="background")
	var background: Long = 0

	@Parameter
	var display: DisplayService? = null

	@Parameter
	var log: LogService? = null

	override fun run() {
		val label = getAnnotationLabel(this, "weight")
		println("LOL LABEL $label")
		val rai1 = this.rai1!!
		val rai2 = this.rai2!!
		val display = this.display!!
		val log = this.log!!
		val background = this.background.toLong()
		val weight = this.weight.toDouble()
		val ra1 = rai1.randomAccess()
		val ra2 = rai2.randomAccess()
		rai1.min(ra1)
		rai2.min(ra2)
		val i1 = ra1.get().copy()
		val i2 = ra2.get().copy()
		i1.setInteger(background)
		i2.setInteger(background)
		val union = Intervals.union(rai1, rai2)
		val filler = ArrayImgs.longs(*Intervals.dimensionsAsLongArray(union))
		if (i1 is LongType && i2 is LongType) {
			InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform<LongType, DoubleType>(
					rai1 as RandomAccessibleInterval<LongType>,
					rai2 as RandomAccessibleInterval<LongType>,
					ArrayImgFactory(DoubleType()),
					filler,
					weightForFiller = { weight },
					background = background
			)

			display.createDisplay("interpolated", filler)
		}
		else {
			log.warn("Do not understand types ${i1::class.java} and ${i2::class.java}")
		}
	}

private fun getAnnotationLabel(obj: Any, field: String) : String?
{
	val field = obj::class.java.getDeclaredField(field)
	val annotation = field.getAnnotation(Parameter::class.java)
	val label = annotation?.label
	println("field=`$field' annotation=`$annotation' -- label=`$label'")
	return label
}


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

	val ij = ImageJ()
	ij.ui().showUI()
	val lutName = "NCSA PalEdit/16_colors.lut"
	val lut = ij.lut().loadLUT(ij.lut().findLUTs().get(lutName)!!)!!
	val display1 = ij.display().createDisplay("img1", Converters.convert(img1 as RandomAccessibleInterval<UnsignedLongType>, { s, t -> t.set(s.get()) }, LongType()))
	val display2 = ij.display().createDisplay("img2", Converters.convert(img2 as RandomAccessibleInterval<UnsignedLongType>, { s, t -> t.set(s.get()) }, LongType()))
	ij.lut().applyLUT(lut, display1 as ImageDisplay)
	ij.lut().applyLUT(lut, display2 as ImageDisplay)
	ij.command().run(TestInterpolationSignedDistanceTransformPlugin::class.java, true)

}
