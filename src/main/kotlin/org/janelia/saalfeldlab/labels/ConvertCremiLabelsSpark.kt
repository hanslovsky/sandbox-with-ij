package org.janelia.saalfeldlab.labels

import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.view.Views
import org.apache.spark.SparkConf
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.api.java.function.Function2
import org.janelia.saalfeldlab.n5.DataType
import org.janelia.saalfeldlab.n5.DatasetAttributes
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import picocli.CommandLine
import scala.Tuple2
import java.lang.invoke.MethodHandles
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

private val USER_HOME = System.getProperty("user.home")

class ConvertCremiLabelsSpark
{
	companion object {
	    val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
	}

	class Counter()
	{
		private val count = AtomicInteger()

		private val total = AtomicInteger()

		fun incrementTotal()
		{
			total.incrementAndGet()
		}

		fun incrementCount(logger: (Int, Int) -> Unit)
		{
			logger(count.incrementAndGet(), total.get())
		}
	}

	class CmdlineArgs {
		@CommandLine.Parameters(index= "0", arity = "1", paramLabel = "INPUT_CONTAINER", description = arrayOf("HDF5"))
		var inputContainer: String? = null

		@CommandLine.Parameters(index = "1", arity = "1", paramLabel = "OUTPUT_CONTAINER", description = arrayOf("N5"))
		var outputContainer: String? = null

		@CommandLine.Option(names = arrayOf("--input-dataset", "-i"), paramLabel = "INPUT_DATASET", defaultValue = "volumes/labels/neuron_ids")
		var inputDataset: String = "volumes/labels/neuron_ids"

		@CommandLine.Option(names = arrayOf("--output-dataset", "-o"), paramLabel = "OUTPUT_DATASET", defaultValue = "\${INPUT_DATASET}")
		var outputDataset: String? = null

		@CommandLine.Option(names = arrayOf("--num-fillers", "-n"), paramLabel = "NUM_FILLERS", defaultValue = "2", showDefaultValue = CommandLine.Help.Visibility.ON_DEMAND)
		var numFillers: Int = 2

		@CommandLine.Option(names = arrayOf("--block-size"), paramLabel = "BLOCK_SIZE", defaultValue = "64", split = ",")
		var blockSize: IntArray = intArrayOf(64, 64, 64)

		@CommandLine.Option(names = arrayOf("--z-min"), defaultValue = "${Long.MIN_VALUE}")
		var zMin: Long = Long.MIN_VALUE

		@CommandLine.Option(names = arrayOf("--z-max"), defaultValue = "${Long.MAX_VALUE}")
		var zMax: Long = Long.MAX_VALUE
	}

	class ListAppender<T> : Function2<MutableList<T>, T, MutableList<T>> {
		override fun call(v1: MutableList<T>?, v2: T): MutableList<T> {
			v1?.add(v2)
			return v1!!
		}
	}

	class ListCombiner<T> : Function2<MutableList<T>, MutableList<T>, MutableList<T>> {
		override fun call(v1: MutableList<T>?, v2: MutableList<T>?): MutableList<T> {
			return (v1!! + v2!!).toMutableList()
		}
	}
}

fun main(mainArgs: Array<String>) {

	val args = ConvertCremiLabelsSpark.CmdlineArgs()
	CommandLine.populateCommand(args, *mainArgs)

	val inputContainer = args.inputContainer!!
	val outputContainer = args.outputContainer!!
	val inputDataset = args.inputDataset
	val outputDataset = args.outputDataset?: inputDataset
	val numFillers = max(args.numFillers.toLong(), 0L)
	val reader = N5HDF5Reader(inputContainer, 1250, 1250, 1)
	val blockSize: IntArray = if (args.blockSize.size == 3) args.blockSize else IntArray(3, {args.blockSize[0]})
	val blockSizeZ = blockSize[2]
	val img = N5Utils.open<UnsignedLongType>(reader, inputDataset)
	val imgDim = Intervals.dimensionsAsLongArray(img)
	println("imgDim=${imgDim.toList()}")

	val zMin = max(args.zMax, img.min(2))
	val zMax = min(args.zMin, img.max(2))

	val stopWatch = StopWatch.createStopped()
	val conf = SparkConf().setAppName("Upsample cremi labels")

	val targetDims = longArrayOf(img.dimension(0), img.dimension(1), (zMin - zMax) * (1 + numFillers) + 1)
	N5FSWriter(outputContainer).createDataset(outputDataset, targetDims, blockSize, DataType.UINT64, GzipCompression())

	JavaSparkContext(conf).use {
		stopWatch.start()
		it
				.parallelize((zMin until zMax).toList())
				.mapToPair { val im = N5Utils.open<UnsignedLongType>(reader, inputDataset); Tuple2(it, Tuple2(Views.hyperSlice(im, 2, it), Views.hyperSlice(im, 2, it+1))) }
				.mapToPair {
					val fillers = InterpolateBetweenSections.makeFillers(numFillers, imgDim[0], imgDim[1])
					InterpolateBetweenSections.interpolateBetweenSectionsWithSignedDistanceTransform(it._2()._1(), it._2()._2(), ArrayImgFactory(DoubleType()), *fillers)
					Tuple2(it._1(), listOf(it._2()._1()) + fillers + (if (it._1() == zMax - 1) listOf(it._2()._2()) else listOf()))
				}
				.map { it._2().mapIndexed { index, rai -> Tuple2(index + numFillers * (it._1() - zMin), rai) }}
				.flatMapToPair { it.iterator() }
				.mapToPair {Tuple2(it._1() / blockSizeZ, it)}
				.aggregateByKey(mutableListOf<Tuple2<Long, RandomAccessibleInterval<UnsignedLongType>>>(), { l, t -> l?.add(t); l!! }, { l1, l2 -> (l1!! + l2!!).toMutableList() } )
				.mapToPair {Tuple2(it._1() * blockSizeZ, it._2()) }
				.mapValues { it.sortedBy { it._1() } }
				.mapValues { Views.stack(it.map { it._2() }) }
				.map { Views.translate(it._2(), 0, 0, it._1())}
				.foreach { N5Utils.save(it as RandomAccessibleInterval<UnsignedLongType>, N5FSWriter(outputContainer), outputDataset, blockSize, GzipCompression()) }
		stopWatch.stop()
	}

	println("Upsampled ${zMax - zMin + 1} sectpions with an additional ${numFillers} fillers between each pair of original sections in ${TimeUnit.SECONDS.convert(stopWatch.nanoTime(), TimeUnit.NANOSECONDS)} seconds")

}
