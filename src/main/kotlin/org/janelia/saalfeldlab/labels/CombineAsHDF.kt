package org.janelia.saalfeldlab.labels

import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgFactory
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.janelia.saalfeldlab.labels.downsample.WinnerTakesAll
import org.janelia.saalfeldlab.n5.*
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.stream.LongStream

private val USER_HOME = System.getProperty("user.home")

private val DM11_HOME = "/groups/saalfeld/home/${System.getProperty("user.name")}"

class CombineAsHDF {

	class InOut(
			val datasetIn: String,
			val datasetOut: String = datasetIn,
			val revertInputArrayAttributes: Boolean = true) {

		override fun toString(): String {
			return ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
					.append("datasetIn", datasetIn)
					.append("datasetOut", datasetOut)
					.append("revert", revertInputArrayAttributes)
					.toString()
		}

	}

	companion object {

		private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

		private val COMPRESSION = GzipCompression()

		private fun saveRaw(
				n5in: N5Reader,
				n5out: N5Writer,
				dataset: InOut,
				dataType: DataType,
				blockSize: IntArray,
				es: ExecutorService) {

			fun <T> save() where T: NativeType<T>  {
				N5Utils.save(N5Utils.open<T>(n5in, dataset.datasetIn), n5out, dataset.datasetOut, blockSize, COMPRESSION, es)
			}

			when(dataType) {
				DataType.UINT8 -> save<UnsignedByteType>()
				DataType.UINT16 -> save<UnsignedShortType>()
				DataType.UINT32 -> save<UnsignedIntType>()
				DataType.UINT64 -> save<UnsignedLongType>()
				DataType.INT8 -> save<ByteType>()
				DataType.INT16 -> save<ShortType>()
				DataType.INT32 -> save<IntType>()
				DataType.INT64 -> save<LongType>()
				DataType.FLOAT32 -> save<FloatType>()
				DataType.FLOAT64 -> save<DoubleType>()
			}
		}

		private fun runForContainer(
				rawContainer: N5Reader,
				labelContainer: N5Reader,
				outputContainer: N5Writer,
				rawDataset: InOut,
				vararg labelDatasets: InOut,
				es: ExecutorService,
				blockSize: IntArray = intArrayOf(64, 64, 64)

		) {
			val scaleFactor = 3
			val voxelSizeFactor = scaleFactor.toDouble() * 3.0

			val rawResolution = getResolution(rawContainer, rawDataset).map { it * voxelSizeFactor }.toDoubleArray()
			val rawOffset = getOffset(rawContainer, rawDataset).map { it * voxelSizeFactor }.toDoubleArray()

			LOG.info("raw resolution: {}", rawResolution)
			LOG.info("raw offset:     {}", rawOffset)

			val rawAttributes = rawContainer.getDatasetAttributes(rawDataset.datasetIn)
			outputContainer.createDataset(rawDataset.datasetOut, rawAttributes.dimensions, blockSize, rawAttributes.dataType, GzipCompression())

			LOG.info("Copying raw dataset {} with dimensions {} into {}/{}", rawDataset.datasetIn, rawAttributes.dimensions, outputContainer, rawDataset.datasetOut)
			val sw = StopWatch.createAndStart()
			saveRaw(rawContainer, outputContainer, rawDataset, rawAttributes.dataType, blockSize, es)
			sw.stop()
			LOG.info("Copied raw dataset {} into {}/{} in {} seconds", rawDataset.datasetIn, outputContainer, rawDataset.datasetOut, sw.seconds())

			outputContainer.setAttribute(rawDataset.datasetOut, "resolution", rawResolution)
			outputContainer.setAttribute(rawDataset.datasetOut, "offset", rawOffset)

			for (labelDataset in labelDatasets) {

				val dataType = labelContainer.getDatasetAttributes(labelDataset.datasetIn).dataType
				when(dataType) {
					DataType.UINT8 -> processLabelDataset<UnsignedByteType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.UINT16 -> processLabelDataset<UnsignedShortType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.UINT32 -> processLabelDataset<UnsignedIntType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.UINT64 -> processLabelDataset<UnsignedLongType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.INT8 -> processLabelDataset<ByteType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.INT16 -> processLabelDataset<ShortType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.INT32 -> processLabelDataset<IntType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					DataType.INT64 -> processLabelDataset<LongType>(labelContainer, outputContainer, labelDataset, scaleFactor, voxelSizeFactor, es, blockSize)
					else -> throw UnsupportedOperationException("Invalid dataType passed for integer label task: $dataType")
				}

			}
		}

		private fun getOffset(n5: N5Reader, dataset: InOut): DoubleArray {
			return getDoubleArrayAttribute(n5, dataset, "offset", doubleArrayOf(0.0, 0.0, 0.0))
		}

		private fun getResolution(n5: N5Reader, dataset: InOut): DoubleArray {
			return getDoubleArrayAttribute(n5, dataset, "resolution", doubleArrayOf(1.0, 1.0, 1.0))
		}

		private fun getDoubleArrayAttribute(n5: N5Reader, dataset: InOut, attribute: String, fallback: DoubleArray): DoubleArray {
			try {
				return n5
						.getAttribute(dataset.datasetIn, attribute, DoubleArray::class.java)
						?.let { if (dataset.revertInputArrayAttributes) it.reversedArray() else it }
						?: fallback
			} catch (e: ClassCastException) {
				return n5
						.getAttribute(dataset.datasetIn, attribute, LongArray::class.java)
						?.let { if (dataset.revertInputArrayAttributes) it.reversedArray() else it }
						?.map { it.toDouble() }
						?.toDoubleArray()
						?: fallback
			}
		}

		private fun <T> processLabelDataset(
				n5in: N5Reader,
				n5out: N5Writer,
				dataset: InOut,
				scaleFactor: Int,
				voxelSizeFactor: Double,
				es: ExecutorService,
				blockSize: IntArray = intArrayOf(64, 64, 64)
		) where T: IntegerType<T>, T: NativeType<T> {

			val labelAttributes = n5in.getDatasetAttributes(dataset.datasetIn)
			val downScaledDim = longArrayOf(
					Math.ceil(labelAttributes.dimensions[0] / scaleFactor.toDouble()).toLong(),
					Math.ceil(labelAttributes.dimensions[1] / scaleFactor.toDouble()).toLong())

			val labelData = N5Utils.open<T>(n5in, dataset.datasetIn)
			val extension = Util.getTypeFromInterval(labelData).createVariable()
			extension.setInteger(Label.INVALID)

			LOG.info("Downsampling dataset {} with dimensions {}", dataset.datasetIn, labelAttributes.dimensions)
			val sw = StopWatch.createAndStart()
			val downsampledSectionFutures = LongStream
					.range(0, labelData.dimension(2))
					.mapToObj { Views.hyperSlice(labelData, 2, it) }
					.map { Views.extendValue(it, extension) }
					.map {
						val input = it;
						val future = es.submit(Callable {
							val output = ArrayImgFactory(extension.createVariable()).create(*downScaledDim)
							WinnerTakesAll.downsample(input, output, scaleFactor, scaleFactor)
							output as RandomAccessibleInterval<T>
						})
						future
					}
					.collect(Collectors.toList())
			val downsampledSections = downsampledSectionFutures
					.stream()
					.map { it.get() as RandomAccessibleInterval<T> }
					.collect(Collectors.toList())
			val downsampled = Views.stack(downsampledSections)
			sw.stop()
			LOG.info("Finished downsampling in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			val downsampledAttributes = DatasetAttributes(
					Intervals.dimensionsAsLongArray(downsampled),
					blockSize,
					labelAttributes.dataType,
					GzipCompression())
			val downsampledDataset = "${dataset.datasetOut}-downsampled"
			n5out.createDataset(downsampledDataset, downsampledAttributes)

			val attributes = DatasetAttributes(
					Intervals.dimensionsAsLongArray(labelData),
					blockSize,
					labelAttributes.dataType,
					GzipCompression())
			n5out.createDataset(dataset.datasetOut, attributes)


			val labelResolution = getResolution(n5in, dataset).map { it * voxelSizeFactor }.toDoubleArray()
			val labelOffset = getOffset(n5in, dataset).map { it * voxelSizeFactor }.toDoubleArray()

			val downsampledResolution = labelResolution.clone()
			downsampledResolution[1] = downsampledResolution[1] * scaleFactor
			downsampledResolution[2] = downsampledResolution[2] * scaleFactor

			val downSampledOffset = labelOffset.clone()
			// TODO how to set correct offset? Is this the right thing to do?
			downSampledOffset[1] = downSampledOffset[1] + downsampledResolution[1] / scaleFactor.toDouble()
			downSampledOffset[2] = downSampledOffset[2] + downsampledResolution[2] / scaleFactor.toDouble()

			sw.start()
			sw.seconds()
			N5Utils.save(downsampled, n5out, downsampledDataset, downsampledAttributes.blockSize, downsampledAttributes.compression, es)
			sw.stop()
			LOG.info("Finished saving downsampled data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			sw.start()
			N5Utils.save(labelData, n5out, dataset.datasetOut, attributes.blockSize, attributes.compression, es)
			sw.stop()
			LOG.info("Finished copying label data in ${TimeUnit.NANOSECONDS.toSeconds(sw.nanoTime())}s")

			n5out.setAttribute(dataset.datasetOut, "resolution", labelResolution)
			n5out.setAttribute(dataset.datasetOut, "offset", labelOffset)
			n5out.setAttribute(downsampledDataset, "resolution", downsampledResolution)
			n5out.setAttribute(downsampledDataset, "offset", downSampledOffset)

		}

		fun run(args: Array<String>) {

			val identifiers = arrayOf("0", "1", "2", "A", "B", "C")
			val datasets = arrayOf(
					"volumes/labels/glia",
					"volumes/labels/glia_noneurons",
					"volumes/labels/mask",
					"volumes/labels/neuron_ids",
					"volumes/labels/neuron_ids_noglia")
					.map { InOut(datasetIn = it, revertInputArrayAttributes = true) }
					.toTypedArray()
			val es = Executors.newFixedThreadPool(47)
			val globalStopWatch = StopWatch.createAndStart()
			for (identifier in identifiers) {
				val labelContainer = "$DM11_HOME/data/from-arlo/interpolated/sample_$identifier-interpolated-labels-2-additional-sections.n5"
				val rawContainer = "$DM11_HOME/data/from-arlo/sample_$identifier.hdf"
				val outputContainer = "$DM11_HOME/data/from-arlo/interpolated-combined/sample_$identifier.hdf"
				LOG.info("Downsampling and combining labels {} from {} with raw {} from {} into {}", datasets, labelContainer, "volumes/raw", rawContainer, outputContainer)
				val sw = StopWatch.createAndStart()
				runForContainer(
						N5HDF5Reader(rawContainer),
						N5FSReader(labelContainer),
						N5HDF5Writer(outputContainer),
						InOut(datasetIn = "volumes/raw", revertInputArrayAttributes = false),
						*datasets,
						es = es
				)
				sw.stop()
				LOG.info("Finished combining with raw dataset and downsampling label datasets {} for sample {} in {} seconds", datasets, identifier, sw.seconds())
			}
			es.shutdown()
			globalStopWatch.stop()
			LOG.info("Finished combining with raw dataset and downsampling label datasets {} for samples {} in {} seconds", datasets, identifiers, globalStopWatch.seconds())

		}
	}
}

fun main(args: Array<String>) {
	CombineAsHDF.run(args)
}
