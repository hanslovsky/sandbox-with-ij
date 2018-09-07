package net.imglib2.view

import gnu.trove.set.hash.TLongHashSet
import net.imglib2.FinalInterval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgs
import net.imglib2.transform.integer.BoundingBox
import net.imglib2.transform.integer.MixedTransform
import net.imglib2.transform.integer.permutation.PermutationTransform
import net.imglib2.util.Intervals
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.*


class PermutationTransformTest {

	companion object {

		val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())!!

		fun <T> permute(rai: RandomAccessibleInterval<T>, indicesLookupFromSourceSpace: IntArray): RandomAccessibleInterval<T> {
			val tf = PermuteCoordinateAxesTransform(*indicesLookupFromSourceSpace)
			val view = TransformView(rai, tf)
			val min = LongArray(rai.numDimensions())
			val max = LongArray(rai.numDimensions())
			tf.applyInverse(min, Intervals.minAsLongArray(rai))
			tf.applyInverse(max, Intervals.maxAsLongArray(rai))
			return Views.interval(view, min, max)
		}
	}
}

fun main(args: Array<String>) {

	val log = PermutationTransformTest.LOG;
	val dims = longArrayOf(1, 2, 3)
	val rng = Random(100L)
	val img = ArrayImgs.longs(*dims)
	img.forEach { it.integer = rng.nextInt(1024) }

	val permutation = intArrayOf(2, 0, 1)
	val permuted = PermutationTransformTest.permute(img, permutation)

	log.info("data:             {}", img.update(null).currentStorageArray)
	log.info("permutation:      {}", permutation)
	log.info("img min max:      {} {}", Intervals.minAsLongArray(img), Intervals.maxAsLongArray(img))
	log.info("permuted min max: {} {}", Intervals.minAsLongArray(permuted), Intervals.maxAsLongArray(permuted))

	val expectedValues = TLongHashSet(img.update(null).currentStorageArray)
	val actualValues = TLongHashSet()
	Views.flatIterable(permuted).forEach { actualValues.add(it.integerLong) }

	val expectedArray = expectedValues.toArray()
	Arrays.sort(expectedArray)

	val actualArray = actualValues.toArray()
	Arrays.sort(actualArray)

	log.info("values expected={}", expectedArray)
	log.info("values actual  ={}", actualArray)

}
