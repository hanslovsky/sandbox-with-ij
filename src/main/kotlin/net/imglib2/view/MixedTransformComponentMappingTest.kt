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


class MixedTransformComponentMappingTest {

	companion object {

		val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())!!

		fun <T> permute(rai: RandomAccessibleInterval<T>, indicesLookupFromSourceSpace: IntArray): RandomAccessibleInterval<T> {
			val tf = MixedTransform(rai.numDimensions(), rai.numDimensions())
			tf.setComponentMapping(indicesLookupFromSourceSpace)
			val view = MixedTransformView(rai, tf)
			val bb = tf.transform(BoundingBox(Intervals.minAsLongArray(rai), Intervals.maxAsLongArray(rai)))
			bb.orderMinMax()
			val min = LongArray(rai.numDimensions())
			val max = LongArray(rai.numDimensions())
			Arrays.setAll(min) { rai.min(indicesLookupFromSourceSpace[it]) }
			Arrays.setAll(max) { rai.max(indicesLookupFromSourceSpace[it]) }
//			(0 until min.size).forEach { min[indicesLookupFromSourceSpace[it]] = rai.min(it) }
//			(0 until max.size).forEach { max[indicesLookupFromSourceSpace[it]] = rai.max(it) }
			return Views.interval(view, min, max)
		}
	}
}

fun main(args: Array<String>) {

	val log = MixedTransformComponentMappingTest.LOG;
	val dims = longArrayOf(4, 5, 6)
	val rng = Random(100L)
	val img = ArrayImgs.longs(*dims)
	img.forEach { it.integer = rng.nextInt(1024) }

	val permutation = intArrayOf(2, 0, 1)
	val permuted = MixedTransformComponentMappingTest.permute(img, permutation)

	log.info("data:             {}", img.update(null).currentStorageArray)
	log.info("permutation:      {}", permutation)
	log.info("img min max:      {} {}", Intervals.minAsLongArray(img), Intervals.maxAsLongArray(img))
	log.info("permuted min max: {} {}", Intervals.minAsLongArray(permuted), Intervals.maxAsLongArray(permuted))

	val expectedValues = TLongHashSet(img.update(null).currentStorageArray)
	val actualValues = TLongHashSet()
	Views.flatIterable(permuted).forEach { actualValues.add(it.integerLong) }

	log.info("values expected={}", expectedValues)
	log.info("values actual  ={}", actualValues)

}
