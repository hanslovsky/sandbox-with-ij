package org.janelia.saalfeldlab.labels

import net.imagej.ImageJ
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.labeling.ConnectedComponentAnalysis
import net.imglib2.algorithm.neighborhood.DiamondShape
import net.imglib2.algorithm.util.unionfind.LongHashMapUnionFind
import net.imglib2.algorithm.util.unionfind.UnionFind
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.integer.ByteType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.util.Intervals
import net.imglib2.util.Pair
import net.imglib2.view.Views
import java.util.function.LongFunction
import java.util.function.LongUnaryOperator

fun main(vararg args: String)
{
	println("args ${args}")

	val mask = ArrayImgs.bits(50, 10)

	Views.interval(mask, longArrayOf(20, 4), longArrayOf(30, 6)).forEach { it.setOne() }
	Views.interval(mask, longArrayOf(1, 1), longArrayOf(3, 3)).forEach { it.setOne() }
	Views.interval(mask, longArrayOf(40, 5), longArrayOf(45, 9)).forEach { it.setOne() }

	val ij = ImageJ()
	ij.ui().showUI()
	ij.ui().show("mask", mask)

	val overlap1 = Views.interval(mask, longArrayOf(0, 0), longArrayOf(35, 7))
	val overlap2 = Views.interval(mask, longArrayOf(15, 3), Intervals.maxAsLongArray(mask))
	val m1 = Intervals.minAsLongArray(overlap1)
	val m2 = Intervals.minAsLongArray(overlap2)

	val l11 = ArrayImgs.unsignedInts(*Intervals.dimensionsAsLongArray(overlap1))
	val l12 = ArrayImgs.unsignedInts(*Intervals.dimensionsAsLongArray(overlap2))
	val l21 = ArrayImgs.unsignedInts(*Intervals.dimensionsAsLongArray(overlap1))
	val l22 = ArrayImgs.unsignedInts(*Intervals.dimensionsAsLongArray(overlap2))

	ConnectedComponentAnalysis.connectedComponents(Views.zeroMin(overlap1), l11)
	ConnectedComponentAnalysis.connectedComponents(Views.zeroMin(overlap2), l12)

	val s = DiamondShape(1)
	val uf = LongFunction<UnionFind> { LongHashMapUnionFind() }
	val id = ConnectedComponentAnalysis.IdForPixel.fromIntervalIndexerWithInterval<UnsignedIntType>(mask)

	ConnectedComponentAnalysis.connectedComponents(overlap1, Views.translate(l21, *m1), s, uf, id, LongUnaryOperator{ it + 1 })
	ConnectedComponentAnalysis.connectedComponents(overlap2, Views.translate(l22, *m2), s, uf, id, LongUnaryOperator{ it + 1 })

	val lutName = "NCSA PalEdit/16_colors.lut"
	val lut = ij.lut().loadLUT(ij.lut().findLUTs().get(lutName)!!)!!

	// connected component that is fully contained in both overlaps has different id in each overlap
//	ij.ui().show("left cc", l11)
//	ij.ui().show("right cc", l12)

	// connected component that is fully contained in both overlaps has same id in each overlap
//	ij.ui().show("left cc same id", l21)
//	ij.ui().show("right cc same id", l22)

	val merged1 = ArrayImgs.unsignedInts(*Intervals.dimensionsAsLongArray(mask))
	val merged2 = ArrayImgs.unsignedInts(*Intervals.dimensionsAsLongArray(mask))

	Views.interval(Views.pair(Views.translate(l11, *m1), merged1), Views.translate(l11, *m1)).forEach { it.b.set(it.a) }
	Views.interval(Views.pair(Views.translate(l12, *m2), merged1), Views.translate(l12, *m2)).forEach { it.b.set(it.a) }
	Views.interval(Views.pair(Views.translate(l21, *m1), merged2), Views.translate(l21, *m1)).forEach { it.b.set(it.a) }
	Views.interval(Views.pair(Views.translate(l22, *m2), merged2), Views.translate(l22, *m2)).forEach { it.b.set(it.a) }

//	ij.ui().show("merged1", merged1)
//	ij.ui().show("merged2", merged2)


	// highlight inconsistencies
	val zero = UnsignedIntType(0)
	val p1 = Views.interval(Views.pair(Views.extendZero(Views.translate(l11, *m1)), Views.extendZero(Views.translate(l12, *m2))), mask) as RandomAccessibleInterval<Pair<UnsignedIntType, UnsignedIntType>>
	val p2 = Views.interval(Views.pair(Views.extendZero(Views.translate(l21, *m1)), Views.extendZero(Views.translate(l22, *m2))), mask) as RandomAccessibleInterval<Pair<UnsignedIntType, UnsignedIntType>>
	val d1 = Converters.convert(p1, { s, t -> t.setInteger(if (s.a.valueEquals(zero) || s.b.valueEquals(zero)) 0 else if (s.a.valueEquals(s.b)) 0 else 1) }, ByteType())
	val d2 = Converters.convert(p2, { s, t -> t.setInteger(if (s.a.valueEquals(zero) || s.b.valueEquals(zero)) 0 else if (s.a.valueEquals(s.b)) 0 else 1) }, ByteType())
	ij.ui().show("inconsistent labels", d1)
	ij.ui().show("consistent labels", d2)





}
