package org.janelia.saalfeldlab.labels

import bdv.util.BdvFunctions
import bdv.util.BdvOptions
import gnu.trove.map.hash.TLongIntHashMap
import net.imagej.ImageJ
import net.imagej.display.ImageDisplay
import net.imglib2.RandomAccessibleInterval
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.integer.ByteType
import net.imglib2.type.numeric.integer.LongType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.view.Views
import org.janelia.saalfeldlab.labels.downsample.WinnerTakesAll

class WinnerTakesAllDownsamplingExample {
	companion object {
		fun downsample() {

			val labelData = byteArrayOf(
					1, 1, 1, 2, 2, 2, 2,
					1, 1, 2, 2, 2, 2, 2,
					1, 2, 3, 3, 3, 3, 3,
					4, 3, 3, 5, 5, 5, 5
			)

			val lut = TLongIntHashMap(
					longArrayOf(1, 2, 3, 4, 5),
					intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFF00.toInt())
			)

			val labels = ArrayImgs.bytes(labelData, 7, 4) as RandomAccessibleInterval<ByteType>
			val downSampled = ArrayImgs.bytes(4, 3) as RandomAccessibleInterval<ByteType>
			val factors = intArrayOf(3, 3)
			WinnerTakesAll.downsample(Views.extendValue(labels, ByteType(Label.INVALID.toByte())), downSampled, *factors)


			val bdv = BdvFunctions.show(Converters.convert(labels, {s,t -> t.set(lut.get(s.integerLong))}, ARGBType()), "labels", BdvOptions.options().is2D)
			BdvFunctions.show(Converters.convert(downSampled, {s,t -> t.set(lut.get(s.integerLong))}, ARGBType()), "downsampled", BdvOptions.options().is2D)

		}
	}
}

fun main(args: Array<String>) {
	WinnerTakesAllDownsamplingExample.downsample()
}
