package org.janelia.saalfeldlab.labels

import gnu.trove.map.hash.TLongIntHashMap
import ij.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.labeling.affinities.ConnectedComponents
import net.imglib2.algorithm.labeling.affinities.Watersheds
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.logic.BitType
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.ConstantUtils
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import picocli.CommandLine
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.random.Random

private val OFFSET_KEY = "offset"

private val RESOLUTION_KEY = "resolution"

private val SOURCE_CONTAINER_KEY = "sourceContainer"

private val SOURCE_DATASET_KEY = "sourceDataset"

private val THRESHOLD_KEY = "threshold"

private val MAX_ID_KEY = "maxId"

private fun LongArray.invertValues(max: Long = 0): LongArray {
	return LongArray(this.size, {max-this[it]})
}

fun main(argv: Array<String>) {

	class Offset(vararg val offset: Long) {

	}

	@CommandLine.Command(name = "Connected-Components")
	class Args {

		@CommandLine.Parameters(arity = "1", paramLabel = "INPUT_CONTAINER", description = arrayOf("Path to N5 container with affinities dataset."))
		var inputContainer: String? = null

		@CommandLine.Option(names = arrayOf("--output-container"), paramLabel = "OUTPUT_CONTAINER", description = arrayOf("Path to output container. Defaults to INPUT_CONTAINER."))
		var outputContainer: String? = null

		@CommandLine.Option(names = arrayOf("--affinity-dataset"), paramLabel = "AFFINITIES", description = arrayOf("Path of affinities dataset in INPUT_CONTAINER."))
		var affinities = "volumes/affinities/prediction"

		@CommandLine.Option(names = arrayOf("--connected-components-dataset"), paramLabel = "CONNECTED_COMPONENTS", description = arrayOf("Path to connected components in OUTPUT_CONTAINER"))
		var connectedComponents = "volumes/labels/connected_components"

		@CommandLine.Option(names = arrayOf("--watersheds-dataset"), paramLabel = "WATERSHEDS", description = arrayOf("Path to watersheds in OUTPUT_CONTAINER"))
		var watersheds = "volumes/labels/watersheds"

		@CommandLine.Option(names = kotlin.arrayOf("--watershed-seeds-mask-dataset"), paramLabel = "WATERSHED_SEEDS_MASK", description = arrayOf("Path to watershed seeds mask in OUTPUT_CONTAINER"))
		var watershedSeedsMask = "volumes/labels/watershed_seeds"

		@CommandLine.Option(names = kotlin.arrayOf("--watershed-seeds-dataset"), paramLabel = "WATERSHED_SEEDS", description = arrayOf("Path to watershed seeds in OUTPUT_CONTAINER"))
		var watershedSeeds = "lists/labels/watershed_seeds"

		@CommandLine.Option(names = arrayOf("--invert-affinities-axis"), paramLabel = "INVERT_AFFINITIES_AXIS", description = arrayOf("Invert axis that holds affinities. This is necessary if affinities were generated as [z,y,x]."))
		var invertAffinitiesAxis = false

		@CommandLine.Option(names = arrayOf("--threshold"), paramLabel = "THRESHOLD", description = arrayOf("Threshold for thresholding affinities. Defaults to 0.5."))
		var threshold = 0.5

		@CommandLine.Option(names = arrayOf("--offsets"), arity = "1..*", paramLabel = "OFFSETS", description = arrayOf("Structuring elements for affinities. Defaults to -1,0,0 0,-1,0 0,0,-1."))
		var offsets = arrayOf(Offset(-1, 0, 0), Offset(0, -1, 0), Offset(0, 0, -1)) //arrayOf("-1,0,0 0,-1,0 0,0,-1".split(" "))

		@CommandLine.Option(names = arrayOf("--block-size"), arity = "1..*", paramLabel = "BLOCK_SIZE", description = arrayOf("Block size of output."))
		var blockSize = intArrayOf(64, 64, 64)

	}

	val args = Args()
	val cmdLine = CommandLine(args)
			.registerConverter(Offset::class.java, { Offset(*Stream.of(*it.split(",").toTypedArray()).mapToLong(String::toLong).toArray()) })
	cmdLine.parse(*argv)

	val steps = Array(args.offsets.size, {args.offsets[it].offset})//longArrayOf(-1, -1, -1)
	val inputContainer = args.inputContainer!!//"/home/hanslovskyp/local/tmp/batch_656001.hdf"
	val outputContainer = args.outputContainer ?: inputContainer
	val n5in = N5HDF5Writer(inputContainer)
	val n5out = N5HDF5Writer(outputContainer)
	val affinitiesDataset = args.affinities
	val predictionDataset = args.connectedComponents
	val threshold = args.threshold

	val affinitiesNotCollapsed =
			if (args.invertAffinitiesAxis) Views.zeroMin(Views.invertAxis(N5Utils.open<FloatType>(n5in, affinitiesDataset), 3))
			else N5Utils.open<FloatType>(n5in, affinitiesDataset)

	// TODO how to avoid looking outside interval?
	steps.forEachIndexed { index, step ->
		val slice = Views.hyperSlice(affinitiesNotCollapsed, affinitiesNotCollapsed.numDimensions() - 1, index.toLong())
		val translatedSlice = Views.translate(slice, *step)
		val c = Views.iterable(translatedSlice).cursor()
		while (c.hasNext()) {
			c.fwd()
			if (!Intervals.contains(slice, c))
				c.get().setReal(Double.NaN)
		}

	}

	val affinities = Views.collapseReal(affinitiesNotCollapsed)
	val mask = ConstantUtils.constantRandomAccessibleInterval(BoolType(true), affinities.numDimensions(), affinities)
	val labels = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(affinities))
	labels.forEach { it.set(Label.INVALID) }
	val unionFindMask = ArrayImgs.bits(*Intervals.dimensionsAsLongArray(affinities))
	val maxId = ConnectedComponents.fromSymmetricAffinities(Views.extendValue(mask, BoolType(false)), affinities, labels, Views.extendZero(unionFindMask), threshold, *steps)

	N5Utils.save(labels, n5out, predictionDataset, args.blockSize, GzipCompression())

	n5in.getAttribute(affinitiesDataset, OFFSET_KEY, LongArray::class.java)?.let { n5out.setAttribute(predictionDataset, OFFSET_KEY, it) }
	n5in.getAttribute(affinitiesDataset, RESOLUTION_KEY, LongArray::class.java)?.let { n5out.setAttribute(predictionDataset, RESOLUTION_KEY, it) }
	n5out.setAttribute(predictionDataset, SOURCE_CONTAINER_KEY, inputContainer)
	n5out.setAttribute(predictionDataset, SOURCE_DATASET_KEY, affinitiesDataset)
	n5out.setAttribute(predictionDataset, THRESHOLD_KEY, threshold)
	n5out.setAttribute(predictionDataset, MAX_ID_KEY, maxId)


	val invertedSteps = Stream.of(*steps).map {it.invertValues()}.collect(Collectors.toList()).toTypedArray()
	val watershedSeedsMask = ArrayImgs.bits(*Intervals.dimensionsAsLongArray(unionFindMask))
	Watersheds.seedsFromMask(Views.extendValue(unionFindMask, BitType(true)), watershedSeedsMask, *(steps + invertedSteps))
	val seeds = Watersheds.collectSeeds(watershedSeedsMask)

	N5Utils.save(labels, n5out, args.watershedSeedsMask, args.blockSize, GzipCompression())
	n5in.getAttribute(affinitiesDataset, OFFSET_KEY, LongArray::class.java)?.let { n5out.setAttribute(args.watershedSeedsMask, OFFSET_KEY, it) }
	n5in.getAttribute(affinitiesDataset, RESOLUTION_KEY, LongArray::class.java)?.let { n5out.setAttribute(args.watershedSeedsMask, RESOLUTION_KEY, it) }

	val seedsDataset = ArrayImgs.unsignedLongs(affinities.numDimensions().toLong(), seeds.size.toLong())
	seeds.forEachIndexed { index, point ->
		val c = Views.flatIterable(Views.hyperSlice(seedsDataset, 1, index.toLong())).cursor()
		(0 until point.numDimensions()).forEach { c.next().set(point.getLongPosition(it)) }
	}

	N5Utils.save(seedsDataset, n5out, args.watershedSeeds, Intervals.dimensionsAsIntArray(seedsDataset), GzipCompression())
	n5in.getAttribute(affinitiesDataset, OFFSET_KEY, LongArray::class.java)?.let { n5out.setAttribute(args.watershedSeeds, OFFSET_KEY, it) }
	n5in.getAttribute(affinitiesDataset, RESOLUTION_KEY, LongArray::class.java)?.let { n5out.setAttribute(args.watershedSeeds, RESOLUTION_KEY, it) }


	val symmetricAffinities = Watersheds.constructAffinities(affinitiesNotCollapsed, *steps, factory = ArrayImgFactory(FloatType()))
	Watersheds.seededFromAffinities(Views.collapseReal(symmetricAffinities), labels, seeds, *steps)

	N5Utils.save(labels, n5out, args.watersheds, args.blockSize, GzipCompression())
	n5in.getAttribute(affinitiesDataset, OFFSET_KEY, LongArray::class.java)?.let { n5out.setAttribute(args.watersheds, OFFSET_KEY, it) }
	n5in.getAttribute(affinitiesDataset, RESOLUTION_KEY, LongArray::class.java)?.let { n5out.setAttribute(args.watersheds, RESOLUTION_KEY, it) }
	n5out.setAttribute(args.watersheds, MAX_ID_KEY, maxId)


//	val colors = TLongIntHashMap()
//	val rng = Random(100L)
//	val converter: Converter<UnsignedLongType, ARGBType> = Converter { s, t -> if (!colors.contains(s.integerLong)) colors.put(s.integerLong, rng.nextInt()); t.set(colors.get(s.integerLong)) }
//	val colored = Converters.convert(labels as RandomAccessibleInterval<UnsignedLongType>, converter, ARGBType())
//
//	ImageJ()
//	ImageJFunctions.show(colored)
//	ImageJFunctions.show(watershedSeedsMask)

}
