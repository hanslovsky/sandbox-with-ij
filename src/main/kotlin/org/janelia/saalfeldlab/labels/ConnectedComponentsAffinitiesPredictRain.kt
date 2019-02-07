package org.janelia.saalfeldlab.labels

import gnu.trove.map.hash.TLongIntHashMap
import ij.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.gauss3.Gauss3
import net.imglib2.algorithm.labeling.affinities.ConnectedComponents
import net.imglib2.algorithm.labeling.affinities.Watersheds
import net.imglib2.algorithm.morphology.watershed.AffinityWatershed2
import net.imglib2.algorithm.morphology.watershed.CompareBetter
import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.ImgFactory
import net.imglib2.img.array.ArrayImg
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.cell.CellImgFactory
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.logic.BitType
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.ConstantUtils
import net.imglib2.util.IntervalIndexer
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
import java.util.function.LongUnaryOperator
import java.util.function.Predicate
import java.util.function.ToLongFunction
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

		@CommandLine.Option(names = arrayOf("--output-container"), paramLabel = "OUTPUT_CONTAINER", description = arrayOf("Path to output container. Defaults to INPUT_CONTAINER."))
		var outputContainer: String? = null

		@CommandLine.Option(names = arrayOf("--affinity-dataset"), paramLabel = "AFFINITIES", description = arrayOf("Path of affinities dataset in INPUT_CONTAINER."))
		var affinities = "volumes/affinities/prediction"

		@CommandLine.Option(names = arrayOf("--rain-dataset"), paramLabel = "CONNECTED_COMPONENTS", description = arrayOf("Path to connected components in OUTPUT_CONTAINER"))
		var connectedComponents = "volumes/labels/rain"

		@CommandLine.Option(names = arrayOf("--invert-affinities-axis"), paramLabel = "INVERT_AFFINITIES_AXIS", description = arrayOf("Invert axis that holds affinities. This is necessary if affinities were generated as [z,y,x]."))
		var invertAffinitiesAxis = true

		@CommandLine.Option(names = arrayOf("--threshold"), paramLabel = "THRESHOLD", description = arrayOf("Threshold for thresholding affinities. Defaults to 0.5."))
		var threshold = 0.5

		@CommandLine.Option(names = arrayOf("--offsets"), arity = "1..*", paramLabel = "OFFSETS", description = arrayOf("Structuring elements for affinities. Defaults to -1,0,0 0,-1,0 0,0,-1."))
		var offsets = arrayOf(Offset(-1, 0, 0), Offset(0, -1, 0), Offset(0, 0, -1)) //arrayOf("-1,0,0 0,-1,0 0,0,-1".split(" "))

		@CommandLine.Option(names = arrayOf("--block-size"), arity = "1..*", paramLabel = "BLOCK_SIZE", description = arrayOf("Block size of output."))
		var blockSize = intArrayOf(64, 64, 64)

	}


	val args = Args()
	val LOG = LoggerFactory.getLogger("Connected-Components")
	val cmdLine = CommandLine(args)
			.registerConverter(Offset::class.java, { Offset(*Stream.of(*it.split(",").toTypedArray()).mapToLong(String::toLong).toArray()) })
	cmdLine.parse(*argv)

	val threadCount = AtomicInteger(0)
	val saveExecutors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1, {Thread(it, "save-executor-${threadCount.incrementAndGet()}")})

	val steps = Array(args.offsets.size, {args.offsets[it].offset})//longArrayOf(-1, -1, -1)
	val inputContainer = args.inputContainer!!//"/home/hanslovskyp/local/tmp/batch_656001.hdf"
	val outputContainer = args.outputContainer ?: inputContainer
	val n5in = if (Files.isDirectory(Paths.get(inputContainer))) N5FSWriter(inputContainer) else N5HDF5Writer(inputContainer)
	val n5out = if (Files.isDirectory(Paths.get(outputContainer))) N5FSWriter(outputContainer) else N5HDF5Writer(outputContainer)
	val affinitiesDataset = args.affinities
	val predictionDataset = args.connectedComponents
	val threshold = args.threshold

	val affinitiesNotCollapsed = run {
		val tmp = if (args.invertAffinitiesAxis)
			Views.zeroMin(Views.invertAxis(N5Utils.open<FloatType>(n5in, affinitiesDataset), 3)) else
			N5Utils.open<FloatType>(n5in, affinitiesDataset)
		tmp
	}

	val affinitiesSmoothed = affinitiesNotCollapsed

	// TODO how to avoid looking outside interval?
	steps.forEachIndexed { index, step ->
		val slice = Views.hyperSlice(affinitiesSmoothed, affinitiesSmoothed.numDimensions() - 1, index.toLong())
		step.forEachIndexed { index2, step2 ->
			if (step2 != 0L) {
//				println("$index2 $step2 ${if (step2 > 0) slice.max(index2) + 1 - step2 else {slice.min(index2) - 1 - step2}}")
				Views
						.hyperSlice(slice, index2, if (step2 > 0) slice.max(index2) + 1 - step2 else {slice.min(index2) - 1 - step2})
						.forEach { it.setReal(Float.NaN) }
			}

		}
	}

	val affinities = Views.collapseReal(affinitiesSmoothed)


	val invertedSteps = Stream.of(*steps).map {it.invertValues()}.collect(Collectors.toList()).toTypedArray().reversedArray()
	val symmetricAffinitiesFactory =
			if (Intervals.numElements(affinitiesSmoothed) * 2 <= Integer.MAX_VALUE)
				ArrayImgFactory(FloatType()) else
				CellImgFactory(FloatType(), *(args.blockSize + intArrayOf(affinities.dimension(3).toInt())))

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

	val symmetricAffinities = Watersheds.constructAffinities(affinitiesSmoothed, offsets=steps, order = IntArray(steps.size, {steps.size - 1 - it}), factory = symmetricAffinitiesFactory)
//	val symmetricAffinities = constructAffinitiesWithCopy(affinitiesSmoothed, offsets = *steps, factory = symmetricAffinitiesFactory)
//	println(Arrays.toString(Intervals.dimensionsAsLongArray(symmetricAffinities)))
//	System.exit(123)


	val labelsRAI = ArrayImgs.longs(*Intervals.dimensionsAsLongArray(Views.collapseReal(symmetricAffinities)));

	ImageJ()
	ImageJFunctions.show(symmetricAffinities)

//	val parents = if (a) {
//		AffinityWatershed2.letItRain(
//				Views.collapseReal(symmetricAffinities),
//				labelsRAI,
//				{ t, u -> !t.realDouble.run { isNaN() && this > 0 && this > u.realDouble } },
//				FloatType(Float.NEGATIVE_INFINITY),
//				es,
//				1,
//				{})
//		es.shutdown()
//		labelsRAI.update(null).currentStorageArray
//	}
//	else {
	val (parents, roots) = Watersheds.letItRainRealType(
			Views.collapseReal(symmetricAffinities),
			isValid = Predicate { !it.realDouble.isNaN() },
			isBetter = BiPredicate { t, u -> t.realDouble > u.realDouble },
			worst = FloatType(Float.NEGATIVE_INFINITY),
			offsets = *(steps + invertedSteps))
//	}
	val labels = ArrayImgs.unsignedLongs(parents.clone(), *Intervals.dimensionsAsLongArray(Views.collapseReal(symmetricAffinities)))

	val um = ArrayImgs.bits(*Intervals.dimensionsAsLongArray(Views.collapseReal(affinitiesSmoothed)))
//
	val uf = IntArrayUnionFind(roots.size)
	parents.forEachIndexed { index, l -> uf.join(uf.findRoot(l), uf.findRoot(parents[index])) }

//	val mask = Converters.convert(labels as RandomAccessibleInterval<UnsignedLongType>, { s, t -> t.set(s.integerLong > 0)}, BitType())
//	ConnectedComponents.unionFindFromSymmetricAffinities(
//			Views.extendValue(mask, BitType(false)),
//			Views.collapseReal(affinitiesSmoothed),
//			Views.extendValue(um, BitType(false)),
//			uf,
//			0.5,
//			*steps,
//			toIndex = ToLongFunction { parents[IntervalIndexer.positionToIndex(it, um).toInt()] }
//			)
//	labels.forEach { it.set( uf.findRoot(it.integerLong)) }
//
//	val colors = TLongIntHashMap()
//	val rng = Random(100L)
//	val converter: Converter<UnsignedLongType, ARGBType> = Converter { s, t -> if (!colors.contains(s.integerLong)) colors.put(s.integerLong, rng.nextInt()); t.set(colors.get(s.integerLong)) }
//	val colored = Converters.convert(labels as RandomAccessibleInterval<UnsignedLongType>, converter, ARGBType())
//	ImageJFunctions.show(colored)
////	ImageJFunctions.show(mask)

}
