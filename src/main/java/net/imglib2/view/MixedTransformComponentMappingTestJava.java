package net.imglib2.view;

import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.LongArray;
import net.imglib2.transform.integer.BoundingBox;
import net.imglib2.transform.integer.MixedTransform;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Intervals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Random;

public class MixedTransformComponentMappingTestJava {

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static <T> RandomAccessibleInterval<T> permute(RandomAccessibleInterval<T> rai, int[] indicesLookupFromSourceSpace) {
		final MixedTransform tf = new MixedTransform(rai.numDimensions(), rai.numDimensions());
		tf.setComponentMapping(indicesLookupFromSourceSpace);
		final MixedTransformView<T>view = new MixedTransformView(rai, tf);
		final BoundingBox bb = tf.transform(new BoundingBox(Intervals.minAsLongArray(rai), Intervals.maxAsLongArray(rai)));
		bb.orderMinMax();
		return Views.interval(view, new FinalInterval(bb.corner1, bb.corner2));
	}

	public static void main(String[] args) {
		final long[] dims = {1, 2, 3};
		final Random rng = new Random(100L);
		final ArrayImg<LongType, LongArray> img = ArrayImgs.longs(dims);
		img.forEach(pix -> pix.setInteger(rng.nextInt(1024)));

		final int[] permutation = {2, 0, 1};
		final RandomAccessibleInterval<LongType> permuted = MixedTransformComponentMappingTestJava.permute(img, permutation);

		LOG.info("data:             {}", img.update(null).getCurrentStorageArray());
		LOG.info("permutation:      {}", permutation);
		LOG.info("img min max:      {} {}", Intervals.minAsLongArray(img), Intervals.maxAsLongArray(img));
		LOG.info("permuted min max: {} {}", Intervals.minAsLongArray(permuted), Intervals.maxAsLongArray(permuted));

		final TLongHashSet expectedValues = new TLongHashSet(img.update(null).getCurrentStorageArray());
		final TLongHashSet actualValues = new TLongHashSet();
		Views.flatIterable(permuted).forEach(pix -> actualValues.add(pix.getIntegerLong()));

		LOG.info("values expected={}", expectedValues);
		LOG.info("values actual  ={}", actualValues);

	}

}
