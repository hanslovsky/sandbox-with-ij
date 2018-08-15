package org.janelia.saalfeldlab.labels

import net.imagej.ImageJ
import net.imglib2.algorithm.labeling.ConnectedComponentAnalysis
import net.imglib2.algorithm.neighborhood.DiamondShape
import net.imglib2.algorithm.util.unionfind.LongHashMapUnionFind
import net.imglib2.algorithm.util.unionfind.UnionFind
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.util.Intervals
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
	ij.ui().show("left cc", l11)
	ij.ui().show("right cc", l12)

	// connected component that is fully contained in both overlaps has same id in each overlap
	ij.ui().show("left cc same id", l21)
	ij.ui().show("right cc same id", l22)

}
