package net.imglib2.algorithm.labeling

import ij.ImageJ
import ij.ImagePlus
import ij.process.ImageConverter
import net.imglib2.Point
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.neighborhood.DiamondShape
import net.imglib2.algorithm.util.Grids
import net.imglib2.converter.ChannelARGBConverter
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.cell.CellGrid
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import java.util.function.ToDoubleBiFunction
import java.util.stream.IntStream

fun main(args: Array<String>) {

	val relief = ArrayImgs.doubles(
			doubleArrayOf(
					1.0, 1.0, 1.0, 0.0, 1.0,
					1.0, 1.0, 0.0, 0.0, 1.0,
					1.0, 0.0, 0.0, 1.0, 1.0,
					1.0, 0.0, 1.0, 1.0, 1.0),
			*longArrayOf(5, 4))

	val labels = ArrayImgs.longs(*(Intervals.dimensionsAsLongArray(relief)))
	val seeds = listOf(Point(1, 1), Point(4, 3))
	val access = labels.randomAccess()

	seeds.forEachIndexed { index, point -> access.setPosition(point); access.get().setInteger(index + 1L) }

	Watersheds.seededRealType(
			Views.extendValue(relief, DoubleType(Double.NaN)),
			labels,
			seeds,
			distance = ToDoubleBiFunction { value, _ -> -value.realDouble },
			shape = DiamondShape(1))

	ImageJ()
	ImageJFunctions.show(relief, "Relief")
	ImageJFunctions.show(labels, "Labels")

	val imp = ImagePlus("https://upload.wikimedia.org/wikipedia/commons/thumb/d/d4/Valve_sobel_%283%29.PNG/300px-Valve_sobel_%283%29.PNG")
	ImageConverter(imp).convertToGray32()
	val realImage = ImageJFunctions.wrap<FloatType>(imp)
	val seedPoints = Grids
			.collectAllContainedIntervals(Intervals.dimensionsAsLongArray(realImage), intArrayOf(20, 20))
			.map { Intervals.minAsLongArray(it).zip(Intervals.maxAsLongArray(it)) }
			.map { it.map { (it.first + it.second) / 2 } }
			.map { Point(*it.toLongArray()) }
	val realLabels = ArrayImgs.ints(*Intervals.dimensionsAsLongArray(realImage))
	val realLabelsAccess = realLabels.randomAccess()

	seedPoints.forEachIndexed { index, point -> realLabelsAccess.setPosition(point); realLabelsAccess.get().setInteger(index + 1L) }
	Watersheds.seededRealType(
			Views.extendValue(realImage, FloatType(Float.NaN)),
			realLabels,
			seedPoints,
			distance = ToDoubleBiFunction { value, _ -> value.realDouble },
			shape = DiamondShape(1))

	ImageJFunctions.show(realImage, "Sobel")
	ImageJFunctions.show(realLabels, "Sobel Labels")




}
