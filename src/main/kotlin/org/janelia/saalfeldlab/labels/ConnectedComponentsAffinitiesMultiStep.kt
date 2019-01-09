package org.janelia.saalfeldlab.labels

import gnu.trove.map.hash.TLongIntHashMap
import ij.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.labeling.affinities.ConnectedComponents
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.logic.BitType
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.LongType
import net.imglib2.view.Views
import java.util.function.LongUnaryOperator
import kotlin.random.Random

fun main(args: Array<String>) {

	val affinities = ArrayImgs.doubles(20, 30, 40, 5)
	Views.hyperSlice(affinities, 3, 0L).forEach { it.setOne() }
	Views.hyperSlice(affinities, 3, 2L).forEach { it.setOne() }
	Views.hyperSlice(affinities, 3, 4L).forEach { it.setOne() }
	val mask = ArrayImgs.bits(20, 30, 40)
	mask.forEach { it.set(true) }
	val labels = ArrayImgs.ints(20, 30, 40)
	val unionFindMask = ArrayImgs.bits(20, 30, 40)

	val steps = arrayOf(longArrayOf(-1, 0, 0), longArrayOf(0, -1, 0), longArrayOf(0, -3, 0), longArrayOf(0, 0, -1), longArrayOf(0, 0, -4))

	var initialId = 100L
	ConnectedComponents.fromSymmetricAffinities(Views.extendValue(mask, BitType(false)), Views.collapseReal(affinities), labels, Views.extendZero(unionFindMask), 0.5, *steps, indexToId = LongUnaryOperator{it+initialId})
	val colors = TLongIntHashMap()
	val rng = Random(100L)
	val converter: Converter<IntType, ARGBType> = Converter { s, t -> if (!colors.contains(s.integerLong)) colors.put(s.integerLong, rng.nextInt()); t.set(colors.get(s.integerLong)) }
	val colored = Converters.convert(labels as RandomAccessibleInterval<IntType>, converter, ARGBType())

	ImageJ()
	ImageJFunctions.show(colored)
	ImageJFunctions.show(labels)

}
