package org.janelia.saalfeldlab.labels

import gnu.trove.map.hash.TLongIntHashMap
import ij.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.gauss3.Gauss3
import net.imglib2.algorithm.labeling.affinities.Watersheds
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.ImgFactory
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.cell.CellImgFactory
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.lang.invoke.MethodHandles
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.Predicate
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.random.Random

private val OFFSET_KEY = "offset"

private val RESOLUTION_KEY = "resolution"

private val SOURCE_CONTAINER_KEY = "sourceContainer"

private val SOURCE_DATASET_KEY = "sourceDataset"

private val THRESHOLD_KEY = "threshold"

private val MAX_ID_KEY = "maxId"

private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

private fun LongArray.invertValues(max: Long = 0): LongArray {
	return LongArray(this.size, {max-this[it]})
}

fun main(argv: Array<String>) {

	LOG.debug("Generating labels from affinities with these arguments {}", argv)

	val totalStopWatch = StopWatch.createAndStart()

	class Offset(vararg val offset: Long) {

	}

	@CommandLine.Command(name = "Connected-Components")
	class Args {

		@CommandLine.Parameters(arity = "?", paramLabel = "INPUT_CONTAINER", description = arrayOf("Path to N5 container with affinities dataset."))
		var inputContainer: String? = "/data/hanslovskyp/cremi/prediction-argparse4.n5" //null

		@CommandLine.Option(names = arrayOf("--affinity-dataset"), paramLabel = "AFFINITIES", description = arrayOf("Path of affinities dataset in INPUT_CONTAINER."))
		var affinities = "volumes/affinities/prediction"

		@CommandLine.Option(names = arrayOf("--cropped-dataset"), paramLabel = "AFFINITIES", description = arrayOf("Path of affinities dataset in INPUT_CONTAINER."))
		var cropped = "volumes/affinities/cropped-prediction"

		@CommandLine.Option(names = arrayOf("--offsets"), arity = "1..*", paramLabel = "OFFSETS", description = arrayOf("Structuring elements for affinities. Defaults to -1,0,0 0,-1,0 0,0,-1."))
		var offsets = arrayOf(Offset(-1, 0, 0), Offset(0, -1, 0), Offset(0, 0, -1)) //arrayOf("-1,0,0 0,-1,0 0,0,-1".split(" "))

	}




	val args = Args()
	val LOG = LoggerFactory.getLogger("Connected-Components")
	val cmdLine = CommandLine(args)
			.registerConverter(Offset::class.java, { Offset(*Stream.of(*it.split(",").toTypedArray()).mapToLong(String::toLong).toArray()) })
	cmdLine.parse(*argv)

	val inputContainer = args.inputContainer!!//"/home/hanslovskyp/local/tmp/batch_656001.hdf"
	val n5in = if (Files.isDirectory(Paths.get(inputContainer))) N5FSWriter(inputContainer) else N5HDF5Writer(inputContainer)
	val affinitiesDataset = args.affinities
	val steps = Array(args.offsets.size, {args.offsets[it].offset})

	val affinitiesAll = N5Utils.open<FloatType>(n5in, affinitiesDataset)

	val max = Intervals.maxAsLongArray(affinitiesAll)
	val min = max.map { it - 3 }.toLongArray()
	min[3] = 0
	val affinitiesNotCollapsed = Views.zeroMin(Views.interval(affinitiesAll, min, max))

	val affinitiesSmoothed = affinitiesNotCollapsed

	// TODO how to avoid looking outside interval?
	steps.forEachIndexed { index, step ->
		println("${Arrays.toString(step)}")
		val slice = Views.hyperSlice(affinitiesSmoothed, affinitiesSmoothed.numDimensions() - 1, index.toLong())
		step.forEachIndexed { index2, step2 ->
			if (step2 != 0L) {
				println("$index2 $step2 ${if (step2 > 0) slice.max(index2) + 1 - step2 else {slice.min(index2) - 1 - step2}}")
				Views
						.hyperSlice(slice, index2, if (step2 > 0) slice.max(index2) + 1 - step2 else {slice.min(index2) - 1 - step2})
						.forEach { it.setReal(Float.NaN) }
			}

		}
	}

	val affinities = Views.collapseReal(affinitiesSmoothed)


	val invertedSteps = Stream.of(*steps).map {it.invertValues()}.collect(Collectors.toList()).toTypedArray().reversedArray()
	val symmetricAffinitiesFactory = ArrayImgFactory(FloatType())

	fun <A: RealType<A>> constructAffinitiesWithCopy(
			affinities: RandomAccessibleInterval<A>,
			factory: ImgFactory<A>,
			vararg offsets: LongArray): RandomAccessibleInterval<A> {
		val dims = Intervals.dimensionsAsLongArray(affinities)
		dims[dims.size - 1] = dims[dims.size - 1] * 2
		val symmetricAffinities = factory.create(*dims)

		LoopBuilder
				.setImages(affinities, Views.interval(symmetricAffinities, Views.zeroMin(affinities)))
				.forEachPixel(BiConsumer { t, u ->  u.set(t)})

		val nanExtension = Util.getTypeFromInterval(affinities).createVariable()
		nanExtension.setReal(Double.NaN)

		val zeroMinAffinities = if (Views.isZeroMin(affinities)) affinities else Views.zeroMin(affinities)

		for (offsetIndex in 0 until offsets.size) {
			val targetIndex = offsets.size + offsets.size - 1 - offsetIndex.toLong()
			println("$offsetIndex $targetIndex")
			val targetSlice = Views.hyperSlice(symmetricAffinities, dims.size - 1, targetIndex)
			val sourceSlice = Views.interval(Views.translate(
					Views.extendValue(Views.hyperSlice(zeroMinAffinities, dims.size - 1, offsetIndex.toLong()), nanExtension),
					*offsets[offsetIndex]), targetSlice)
			LoopBuilder
					.setImages(sourceSlice, targetSlice)
					.forEachPixel(BiConsumer { t, u -> u.set(t) })
		}

		return if (Views.isZeroMin(affinities)) symmetricAffinities else Views.translate(symmetricAffinities, *Intervals.minAsLongArray(affinities))
	}

//	val symmetricAffinities = Watersheds.constructAffinities(affinitiesSmoothed, *steps, factory = symmetricAffinitiesFactory)
	val symmetricAffinities = constructAffinitiesWithCopy(affinitiesSmoothed, offsets = *steps, factory = symmetricAffinitiesFactory)

	val (parents, roots) = Watersheds.letItRain(
			Views.collapseReal(symmetricAffinities),
			isValid = Predicate {!it.realDouble.isNaN()},
			isBetter = BiPredicate { t, u -> t.realDouble > u.realDouble },
			worst = FloatType(Float.NEGATIVE_INFINITY),
			offsets = *(steps + invertedSteps))

//	val labels = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(Views.collapseReal(symmetricAffinities)))

//	val um = ArrayImgs.bits(*Intervals.dimensionsAsLongArray(Views.collapseReal(affinitiesSmoothed)))
//
//	val uf = IntArrayUnionFind(parents.size)
//	ConnectedComponents.unionFindFromSymmetricAffinities(
//			ConstantUtils.constantRandomAccessible(BitType(true), 3),
//			Views.collapseReal(affinitiesSmoothed),
//			Views.extendValue(um, BitType(false)),
//			uf,
//			0.9,
//			*steps,
//			toIndex = { IntervalIndexer.positionToIndex(it, um)}
//			)
//	parents.forEachIndexed { index, pointer -> uf.join(uf.findRoot(index), uf.findRoot(pointer)) }
//
//	val sizes = TIntIntHashMap()
//	for (index in 0 until parents.size)
//		sizes.put(uf.findRoot(index), sizes[uf.findRoot(index)] + 1)
//
//	val sizeThreshold = 10

//	labels.forEachIndexed { index, p -> val r = uf.findRoot(index); p.set(if (sizes[r] > sizeThreshold) r.toLong() else Label.INVALID) }
	val labels = ArrayImgs.unsignedLongs(parents, *Intervals.dimensionsAsLongArray(Views.collapseReal(symmetricAffinities)))

	val colors = TLongIntHashMap()
	val rng = Random(100L)
	val converter: Converter<UnsignedLongType, ARGBType> = Converter { s, t -> if (!colors.contains(s.integerLong)) colors.put(s.integerLong, rng.nextInt()); t.set(colors.get(s.integerLong)) }
	val colored = Converters.convert(labels as RandomAccessibleInterval<UnsignedLongType>, converter, ARGBType())

	ImageJ()
	ImageJFunctions.show(colored)
	ImageJFunctions.show(symmetricAffinities)

}
