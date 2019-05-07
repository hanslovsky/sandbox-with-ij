import ij.ImageJ
import ij.io.FileSaver
import net.imglib2.FinalInterval
import net.imglib2.RandomAccessible
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.morphology.distance.DistanceTransform
import net.imglib2.converter.Converters
import net.imglib2.converter.RealARGBConverter
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.interpolation.InterpolatorFactory
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory
import net.imglib2.loops.LoopBuilder
import net.imglib2.position.FunctionRandomAccessible
import net.imglib2.realtransform.RealViews
import net.imglib2.realtransform.Scale2D
import net.imglib2.realtransform.Translation
import net.imglib2.realtransform.Translation2D
import net.imglib2.type.logic.BitType
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaYCbCr
import org.janelia.saalfeldlab.paintera.composition.Composite
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.BiConsumer
import java.util.function.Predicate
import java.util.function.Supplier

data class Object(val min: Long, val max: Long) {
	val com: Double = 0.5 * (min + max)

	private val range = min..max

	fun isContained(value: Long) = value in range
}

fun <T> signedDistanceTransform(
		data: RandomAccessible<T>,
		test: Predicate<T>,
		size: Long
): RandomAccessibleInterval<DoubleType> {
	val dt1 = ArrayImgs.doubles(size)
	val dt2 = ArrayImgs.doubles(size)
	DistanceTransform.binaryTransform(Converters.convert(data, {s,t -> t.set(test.test(s))}, BitType()), dt1, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN)
	DistanceTransform.binaryTransform(Converters.convert(data, {s,t -> t.set(!test.test(s))}, BitType()), dt2, DistanceTransform.DISTANCE_TYPE.EUCLIDIAN)
	return Views.interval(Converters.convert(Views.pair(dt1, dt2), {s, t -> t.setReal(Math.sqrt(s.b.realDouble) - Math.sqrt(s.a.realDouble))}, DoubleType()), dt1)
}

fun interpolateOriginal(
		obj1: Object,
		obj2: Object,
		size: Long,
		sections: Long,
		interpolation: InterpolatorFactory<DoubleType, RandomAccessible<DoubleType>>
): RandomAccessibleInterval<DoubleType> {
	val mask1 = FunctionRandomAccessible<BitType>(1, BiConsumer { l, b ->  b.set(obj1.isContained(l.getLongPosition(0)))}, Supplier {BitType()})
	val mask2 = FunctionRandomAccessible<BitType>(1, BiConsumer { l, b ->  b.set(obj2.isContained(l.getLongPosition(0)))}, Supplier {BitType()})
	val sdt1 = signedDistanceTransform(mask1, Predicate {it.get()}, size)
	val sdt2 = signedDistanceTransform(mask2, Predicate {it.get()}, size)
	return interpolate(sdt1, sdt2, sections, interpolation)
}

fun interpolateCentered(
		obj1: Object,
		obj2: Object,
		size: Long,
		sections: Long,
		interpolation: InterpolatorFactory<DoubleType, RandomAccessible<DoubleType>>
): RandomAccessibleInterval<DoubleType> {
	val mask1 = FunctionRandomAccessible<BitType>(1, BiConsumer { l, b ->  b.set(obj1.isContained(l.getLongPosition(0)))}, Supplier {BitType()})
	val mask2 = FunctionRandomAccessible<BitType>(1, BiConsumer { l, b ->  b.set(obj2.isContained(l.getLongPosition(0)))}, Supplier {BitType()})
	val comDiff = obj2.com - obj1.com
	val interpolated1 = Views.interpolate(mask1, NearestNeighborInterpolatorFactory<BitType>())
	val translated1 = Views.raster(RealViews.transformReal(interpolated1, Translation(comDiff)))

	val sdt1 = signedDistanceTransform(translated1, Predicate {it.get()}, size)
	val sdt2 = signedDistanceTransform(mask2, Predicate {it.get()}, size)
	return interpolate(sdt1, sdt2, sections, interpolation)
}

fun <T: RealType<T>> interpolate(
	rai1: RandomAccessibleInterval<T>,
	rai2: RandomAccessibleInterval<T>,
	sections: Long,
	interpolation: InterpolatorFactory<T, RandomAccessible<T>>
): RandomAccessibleInterval<T> {
	val rais       = Views.interpolate(Views.extendBorder(Views.stack(rai1, rai2)), interpolation)
	val translated = RealViews.transform(rais, Translation2D(0.0, 0.0))
	val scaled     = RealViews.transform(translated, Scale2D(1.0, (sections - 1).toDouble()))
	return Views.interval(Views.raster(scaled), FinalInterval(rai1.dimension(0), sections))
}

fun <T: RealType<T>> unshear(
		rai: RandomAccessibleInterval<T>,
		comDiff: Double,
		interpolation: InterpolatorFactory<T, RandomAccessible<T>> = NLinearInterpolatorFactory<T>()): RandomAccessibleInterval<T> {
	val slices = (rai.min(1)..rai.max(1)).map { Views.hyperSlice(rai, 1, it) }
	val t = Util.getTypeFromInterval(rai)
	t.setReal(-100.0)
	val mapped = slices
			.mapIndexed { index, iv ->
				val interpolated = Views.interpolate(Views.extendValue(iv, t.copy()), interpolation)
				val translation = -(1.0 - index * 1.0 / slices.size) * comDiff
				Views.interval(Views.raster(RealViews.transform(interpolated, Translation(translation))), iv)
	}

	return Views.stack(mapped)

}
fun <T: RealType<T>> compose(
		interpolated: RandomAccessibleInterval<T>,
		threshold: Predicate<T> = Predicate { it.realDouble >= 0.0 },
		min: Double = -79.0,
		max: Double = +33.0,
		composite: Composite<ARGBType, ARGBType> = ARGBCompositeAlphaYCbCr(),
		alpha: Int = 0x6f000000,
		color: Int = 0x00850047): RandomAccessibleInterval<ARGBType> {
	val c = color or (alpha and 0xff000000.toInt())
	val composed = ArrayImgs.argbs(*Intervals.dimensionsAsLongArray(interpolated))
	LoopBuilder
			.setImages(composed, Converters.convert(interpolated, RealARGBConverter(min, max), ARGBType()))
			.forEachPixel(BiConsumer { t, s -> t.set(s)})
	LoopBuilder
			.setImages(composed, Converters.convert(interpolated, {s, t -> t.set(if (threshold.test(s)) c else 0)}, ARGBType()))
			.forEachPixel(BiConsumer {s, t -> composite.compose(s, t)})
	return composed
}


fun makeTransformImage(targetDirectory: String? = null, showInImageJ: Boolean = false) {
	val sections = 11L
	val size = 150L
	val obj1 = Object(min = 10L, max=55L)
	val obj2 = Object(min = 50L, max=130L)
	val comDiff = obj2.com - obj1.com
	require(comDiff > 0.0)

	val originalnn = interpolateOriginal(obj1, obj2, size, sections, NearestNeighborInterpolatorFactory())
	val original = interpolateOriginal(obj1, obj2, size, sections, NLinearInterpolatorFactory())
	val centerednn = interpolateCentered(obj1, obj2, size, sections, NearestNeighborInterpolatorFactory())
	val centered = interpolateCentered(obj1, obj2, size, sections, NLinearInterpolatorFactory())
	val unsheared = unshear(centered, comDiff, NLinearInterpolatorFactory())

	val composedOriginalNN = compose(originalnn)
	val composedOriginal = compose(original)
	val composedCenteredNN = compose(centerednn)
	val composedCentered = compose(centered)
	val composedUnsheared = compose(unsheared)

	if (showInImageJ) {
		ImageJ()
		ImageJFunctions.show(originalnn, "originalnn")
		ImageJFunctions.show(original, "original")
		ImageJFunctions.show(centerednn, "centerednn")
		ImageJFunctions.show(centered, "centered")
		ImageJFunctions.show(unsheared, "unsheared")
		ImageJFunctions.show(composedOriginalNN, "originalnn")
		ImageJFunctions.show(composedOriginal, "original")
		ImageJFunctions.show(composedCenteredNN, "centerednn")
		ImageJFunctions.show(composedCentered, "centered")
		ImageJFunctions.show(composedUnsheared, "unsheared")
	}

	targetDirectory?.let {
		Files.createDirectories(Paths.get(it))
		// println(Paths.get(it))
		FileSaver(ImageJFunctions.wrap(originalnn, "originalnn")).saveAsPng(Paths.get(it, "original-nn.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(original, "original")).saveAsPng(Paths.get(it, "original.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(centerednn, "centerednn")).saveAsPng(Paths.get(it, "centered-nn.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(centered, "centered")).saveAsPng(Paths.get(it, "centered.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(unsheared, "unsheared")).saveAsPng(Paths.get(it, "unsheared.png").toAbsolutePath().toString())

		FileSaver(ImageJFunctions.wrap(composedOriginalNN, "composed-originalnn")).saveAsPng(Paths.get(it, "composed-original-nn.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(composedOriginal, "composed-original")).saveAsPng(Paths.get(it, "composed-original.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(composedCenteredNN, "composed-centerednn")).saveAsPng(Paths.get(it, "composed-centered-nn.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(composedCentered, "composed-centered")).saveAsPng(Paths.get(it, "composed-centered.png").toAbsolutePath().toString())
		FileSaver(ImageJFunctions.wrap(composedUnsheared, "composed-unsheared")).saveAsPng(Paths.get(it, "composed-unsheared.png").toAbsolutePath().toString())
		null
	}


}

fun main(args: Array<String>) {
	makeTransformImage(
			targetDirectory = "/home/hanslovskyp/.local/tmp/loldir",
			showInImageJ = true)
}
