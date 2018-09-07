package net.imglib2.view

import gnu.trove.set.hash.TLongHashSet
import net.imglib2.FinalInterval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgs
import net.imglib2.transform.integer.BoundingBox
import net.imglib2.transform.integer.MixedTransform
import net.imglib2.util.Intervals
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.*


class MixedTransformComponentMappingComparison {

	companion object {

		val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())!!

		fun <T> permute(
				rai: RandomAccessibleInterval<T>,
				indicesLookupFromSourceSpace: IntArray
				): RandomAccessibleInterval<T> {

			val tf = MixedTransform(rai.numDimensions(), rai.numDimensions())
			tf.setComponentMapping(indicesLookupFromSourceSpace)
			val view = MixedTransformView(rai, tf)
			val min = LongArray(rai.numDimensions())
			val max = LongArray(rai.numDimensions())
			Arrays.setAll(min) { d -> rai.min(indicesLookupFromSourceSpace[d]) }
			Arrays.setAll(max) { d -> rai.max(indicesLookupFromSourceSpace[d]) }
//			(0 until rai.numDimensions()).forEach {
//				min[indicesLookupFromSourceSpace[it]] = rai.min(it)
//				max[indicesLookupFromSourceSpace[it]] = rai.max(it)
//			}
			val bb = tf.transform(BoundingBox(Intervals.minAsLongArray(rai), Intervals.maxAsLongArray(rai)))
			bb.orderMinMax()
			return Views.interval(view, FinalInterval(bb.corner1, bb.corner2))
//			return Views.interval(view, FinalInterval(min, max))
		}
	}
}

fun main(args: Array<String>) {

	val LOG = MixedTransformComponentMappingTest.LOG;
	val dims = longArrayOf(1, 2, 3)
	val rng = Random(100L)
	val img = ArrayImgs.longs(*dims)
	img.forEach {it.setInteger(rng.nextInt(1024) - 512)}

	val permutation = intArrayOf(2, 0, 1)
	val permuted = MixedTransformComponentMappingComparison.permute(img, permutation)

	LOG.info("data:             {}", img.update(null).currentStorageArray)
	LOG.info("permutation:      {}", permutation)
	LOG.info("img min max:      {} {}", Intervals.minAsLongArray(img), Intervals.maxAsLongArray(img))
	LOG.info("permuted min max: {} {}", Intervals.minAsLongArray(permuted), Intervals.maxAsLongArray(permuted))

	val imgAccess = img.randomAccess()
	val permutedCursor = Views.iterable(permuted).cursor()

	while(permutedCursor.hasNext())
	{
		val actual = permutedCursor.next()
		imgAccess.setPosition(permutedCursor.getLongPosition(0), permutation[0])
		imgAccess.setPosition(permutedCursor.getLongPosition(1), permutation[1])
		imgAccess.setPosition(permutedCursor.getLongPosition(2), permutation[2])

		imgAccess.setPosition(permutedCursor.getLongPosition(permutation[0]), 0)
		imgAccess.setPosition(permutedCursor.getLongPosition(permutation[1]), 1)
		imgAccess.setPosition(permutedCursor.getLongPosition(permutation[2]), 2)

		val expected = imgAccess.get()
		LOG.info("expected={} actual={}", expected, actual)
	}

	var actualSum = 0L
	Views.flatIterable(permuted).forEach { LOG.trace("permuted value={}", it); actualSum += it.integerLong }
	var expectedSum = 0L
	img.forEach { LOG.trace("original value={}", it); expectedSum += it.integerLong }
	LOG.info("sum expected={} actual={}", expectedSum, actualSum)

	val expectedValues = TLongHashSet(img.update(null).currentStorageArray)
	val actualValues = TLongHashSet()
	Views.flatIterable(permuted).forEach { actualValues.add(it.integerLong) }

	LOG.info("values expected={}", expectedValues)
	LOG.info("values actual  ={}", actualValues)

}
