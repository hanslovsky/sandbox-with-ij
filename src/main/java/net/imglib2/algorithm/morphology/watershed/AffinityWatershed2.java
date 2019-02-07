package net.imglib2.algorithm.morphology.watershed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;


import gnu.trove.impl.Constants;
import gnu.trove.iterator.TLongDoubleIterator;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.map.hash.TLongDoubleHashMap;
import gnu.trove.set.hash.TLongHashSet;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.morphology.watershed.flat.FlatViews;
import net.imglib2.type.BooleanType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Pair;
import net.imglib2.view.IterableRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.imglib2.view.composite.RealComposite;

public class AffinityWatershed2
{

	public static long[] generateStride( final Interval i )
	{
		final int nDim = i.numDimensions();
		final long[] strides = new long[ nDim ];

		strides[ 0 ] = 1;
		for ( int d = 1; d < nDim; ++d )
			strides[ d ] = strides[ d - 1 ] * i.dimension( d - 1 );

		return strides;
	}

	public static long[] generateSteps( final long[] strides )
	{
		final int nDim = strides.length;
		final long[] steps = new long[ 2 * nDim ];
		for ( int d = 0; d < nDim; ++d )
		{
			steps[ nDim + d ] = strides[ d ];
			steps[ nDim - 1 - d ] = -strides[ d ];
		}
		return steps;
	}

	public static long[] generateDirectionBitmask( final int nDim )
	{
		final long[] bitmask = new long[ nDim * 2 ];
		for ( int d = 0; d < bitmask.length; ++d )
			bitmask[ d ] = 1 << d;
		return bitmask;
	}

	public static long[] generateInverseDirectionBitmask( final long[] bitmask )
	{
		final long[] inverseBitmask = new long[ bitmask.length ];
		for ( int d = 0; d < bitmask.length; ++d )
			inverseBitmask[ d ] = bitmask[ bitmask.length - 1 - d ];
		return inverseBitmask;
	}

	public static < T extends RealType< T >> long[] letItRain(
			final RandomAccessible< RealComposite< T > > source,
			final RandomAccessibleInterval< LongType > labels,
			final CompareBetter< T > compare,
			final T worst,
			final ExecutorService es,
			final int nTasks,
			final Runnable visitor ) throws InterruptedException, ExecutionException
	{

		final long highBit = 1l << 63;
		final long secondHighBit = 1l << 62;

		final int nDim = source.numDimensions();
		final long[] strides = generateStride( labels );
		final long[] steps = generateSteps( strides );
		final long[] bitmask = generateDirectionBitmask( nDim );
		final long[] inverseBitmask = generateInverseDirectionBitmask( bitmask );

		final long t0 = System.nanoTime();
		findParents( source, labels, compare, worst, bitmask, es, nTasks );
		final long t1 = System.nanoTime();
		System.out.println( "findParents: " + ( t1 - t0 ) / 1e6 + "ms" );

		visitor.run();

		final long t2 = System.nanoTime();
		final TLongArrayList plateauCorners = findPlateauCorners( labels, steps, bitmask, inverseBitmask, secondHighBit, es, nTasks );
		final long t3 = System.nanoTime();
		System.out.println( "findPlateauCorners: " + ( t3 - t2 ) / 1e6 + "ms" );

		visitor.run();

		final long t4 = System.nanoTime();
		removePlateaus( plateauCorners, labels, steps, bitmask, inverseBitmask, highBit, secondHighBit );
		final long t5 = System.nanoTime();
		System.out.println( "removePlateaus: " + ( t5 - t4 ) / 1e6 + "ms" );

		visitor.run();

		final long t6 = System.nanoTime();
		final long[] counts = fillFromRoots( labels, steps, bitmask, inverseBitmask, highBit, es, nTasks );
//		final long[] counts = mergeAndCount( labels, highBit, secondHighBit, bitmask, steps );
		final long t7 = System.nanoTime();
		System.out.println( "mergeAndCount: " + ( t7 - t6 ) / 1e6 + "ms" );

		return counts;

	}

	private static < T extends RealType< T > > void findParents(
			final RandomAccessible< RealComposite< T > > source,
			final RandomAccessibleInterval< LongType > labels,
			final CompareBetter< T > compare,
			final T worst,
			final long[] bitMask,
			final ExecutorService es,
			final int nTasks ) throws InterruptedException, ExecutionException
	{

		final int nDim = source.numDimensions();
		final int nEdges = 2 * nDim;

		final long size = FlatViews.flatten( labels ).dimension( 0 );

		final long taskSize = size / nTasks;

		final ArrayList< Callable< Void > > tasks = new ArrayList<>();

		for ( long start = 0; start < size; start += taskSize )
		{
			final Cursor< Pair< RealComposite< T >, LongType > > cursor = Views.flatIterable( Views.interval( Views.pair( source, labels ), labels ) ).cursor();
			cursor.jumpFwd( start );
			final T currentBest = worst.createVariable();

			tasks.add( () -> {

				for ( long count = 0; count < taskSize && cursor.hasNext(); ++count )
				{
					final Pair< RealComposite< T >, LongType > p = cursor.next();
					final RealComposite< T > edgeWeights = p.getA();
					final LongType label = p.getB();
					long labelRaw = label.get();
					currentBest.set( worst );

					for ( long i = 0; i < nEdges; ++i )
					{
						final T currentWeight = edgeWeights.get( i );
						if ( compare.isBetter( currentWeight, currentBest ) )
							currentBest.set( currentWeight );
					}

					if ( !currentBest.valueEquals( worst ) )
						for ( int i = 0; i < nEdges; ++i )
							if ( edgeWeights.get( i ).valueEquals( currentBest ) )
								labelRaw |= bitMask[ i ];

					label.set( labelRaw );
				}
				return null;

			} );
		}

		invokeAllAndWait( es, tasks );
	}

	private static TLongArrayList findPlateauCorners(
			final RandomAccessibleInterval< LongType > labels,
			final long[] steps,
			final long[] bitmask,
			final long[] inverseBitmask,
			final long plateauCornerMask,
			final ExecutorService es,
			final int nTasks ) throws InterruptedException, ExecutionException
	{
		final int nEdges = steps.length;
		final long size = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).dimension( 0 );



		final long taskSize = size / nTasks;

		final ArrayList< Callable< TLongArrayList > > tasks = new ArrayList<>();

		for ( long start = 0; start < size; start += taskSize )
		{
			final RandomAccess< LongType > flatLabels = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();
			final Cursor< LongType > cursor = Views.flatIterable( labels ).cursor();
			cursor.jumpFwd( start );
			final long finalStart = start;

			tasks.add( () -> {
				final TLongArrayList taskPlateauCornerIndices = new TLongArrayList();
				for ( long count = 0, index = finalStart; count < taskSize; ++count, ++index )
				{
					final LongType label = cursor.next();
					final long labelRaw = label.get();
					for ( int i = 0; i < nEdges; ++i )
						if ( ( labelRaw & bitmask[ i ] ) != 0 )
						{
							final long otherIndex = index + steps[ i ];
							if ( otherIndex >= 0 && otherIndex < size && ( get( flatLabels, otherIndex ).get() & inverseBitmask[ i ] ) == 0 )
							{
								label.set( labelRaw | plateauCornerMask );
								taskPlateauCornerIndices.add( index );
								i = nEdges; // break;
							}
						}
				}
				return taskPlateauCornerIndices;
			} );

		}

		final List< Future< TLongArrayList > > futures = es.invokeAll( tasks );

		final TLongArrayList plateauCornerIndices = new TLongArrayList();

		for ( final Future< TLongArrayList > f : futures )
			plateauCornerIndices.addAll( f.get() );

		return plateauCornerIndices;
	}

	private static void removePlateaus(
			final TLongArrayList queue,
			final RandomAccessibleInterval< LongType > labels,
			final long[] steps,
			final long[] bitMask,
			final long[] inverseBitmask,
			final long highBit,
			final long secondHighBit )
	{

		// This is the same as example in paper, if traversal order of queue is
		// reversed.

		// helpers
		final int nEdges = steps.length;

		// accesses
		final RandomAccess< LongType > flatLabels1 = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();
		final RandomAccess< LongType > flatLabels2 = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();

		for( int queueIndex = 0; queueIndex < queue.size(); ++queueIndex ) {
			final long index = queue.get( queueIndex );
			long parent = 0;
			final LongType label = get( flatLabels1, index );
			final long labelRaw = label.get();
			for ( int d = 0; d < nEdges; ++d )
				if ( ( labelRaw & bitMask[ d ] ) != 0 )
				{
					final long otherIndex = index + steps[ d ];
					{
						final LongType otherLabel = get( flatLabels2, otherIndex );
						final long otherLabelRaw = otherLabel.get();
						if ( ( otherLabelRaw & inverseBitmask[ d ] ) != 0 && ( otherLabelRaw & secondHighBit ) == 0 )
						{
							queue.add( otherIndex );
							otherLabel.set( otherLabelRaw | secondHighBit );
						}
						else if ( parent == 0 )
							parent = bitMask[ d ];
					}
				}
			label.set( parent );
		}
	}

	private static TLongArrayList buildTree(
			final RandomAccessibleInterval< LongType > labels,
			final long[] steps,
			final long[] bitmask,
			final long[] inverseBitmask )
	{
		final TLongArrayList roots = new TLongArrayList();

		final long size = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).dimension( 0 );
		final int nEdges = steps.length;

		final RandomAccess< LongType > flatLabels = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();

		final Cursor< LongType > c = Views.flatIterable( labels ).cursor();
		for ( long index = 0; index < size; ++index )
		{
			final LongType label = c.next();
			final long labelRaw = label.get();
			boolean hasEdge = false;
			for ( int d = 0; d < nEdges; ++d )
				if ( (labelRaw & bitmask[d]) != 0 )
				{
					hasEdge = true;
					final long otherIndex = index + steps[d];
					if ( otherIndex >= 0 && otherIndex < size && ( get( flatLabels, otherIndex ).get() & inverseBitmask[d] ) != 0 )
					{
						if ( index < otherIndex )
							roots.add( index );
						else
							label.set( bitmask[ d ] );
					}
					else
						label.set( bitmask[ d ] );
					d = nEdges;
				}
			if ( !hasEdge )
				roots.add( index );
		}

		for ( final TLongIterator r = roots.iterator(); r.hasNext(); )
			get( flatLabels, r.next() ).set( 0l );

		c.reset();

		for ( long index = 0; c.hasNext(); ++index )
		{
			final LongType label = c.next();
			final long labelRaw = label.get();
			if ( labelRaw == 0 )
				label.set( index );
			else
				for ( int d = 0; d < bitmask.length; ++d )
					if ( ( labelRaw & bitmask[ d ] ) != 0 )
					{
						label.set( index + steps[d] );
						d = bitmask.length;
					}
		}

		return roots;
	}

	private static < L extends IntegerType< L > > long[] fillFromRoots(
			final RandomAccessibleInterval< L > labels,
			final long[] steps,
			final long[] bitmask,
			final long[] inverseBitmask,
			final long visitedMask,
			final ExecutorService es,
			final int nTasks ) throws InterruptedException, ExecutionException
	{

		final long size = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).dimension( 0 );
		final int nEdges = steps.length;


		final AtomicLong backgroundCount = new AtomicLong( 0 );

		final ArrayList< Callable< TLongArrayList > > rootLocatingTasks = new ArrayList<>();

		final long taskSize = size / nTasks;

		for ( long start = 0; start < size; start += taskSize )
		{
			final long finalStart = start;

			rootLocatingTasks.add( () -> {
				final Cursor< L > cursor = Views.flatIterable( labels ).cursor();
				cursor.jumpFwd( finalStart );
				final TLongArrayList roots = new TLongArrayList();
				final RandomAccess< L > flatLabels = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();
				for ( long count = 0, index = finalStart; count < taskSize && cursor.hasNext(); ++count, ++index )
				{
					boolean isChild = false;
					boolean hasChild = false;

					final long label = cursor.next().getIntegerLong();

					for ( int i = 0; i < nEdges && !isChild && !hasChild; ++i )
						if ( ( label & bitmask[ i ] ) != 0 )
						{
							isChild = true;
							final long otherIndex = index + steps[ i ];
							if ( otherIndex >= 0 && otherIndex < size &&
									( get( flatLabels, otherIndex ).getIntegerLong() & inverseBitmask[ i ] ) != 0 &&
									index < otherIndex )
								hasChild = true;

						}

//							isChild = true;
//						else
//						{
//							final long otherIndex = index + steps[ i ];
//							if ( otherIndex >= 0 && otherIndex < size && ( get( flatLabels, otherIndex ).getIntegerLong() & inverseBitmask[ i ] ) != 0 )
//								hasChild = true;
//						}

					if ( hasChild )
						roots.add( index );
					else if ( !isChild )
						backgroundCount.incrementAndGet();
				}
				return roots;
			} );
		}

		final TLongArrayList roots = new TLongArrayList();

		{

			final long t0 = System.nanoTime();
			final List< Future< TLongArrayList > > rootsFutures = es.invokeAll( rootLocatingTasks );
			for ( final Future< TLongArrayList > f : rootsFutures )
				roots.addAll( f.get() );
			final long t1 = System.nanoTime();
			System.out.println( "\tFinding roots " + ( t1 - t0 ) / 1e6 + "ms " + backgroundCount.get() + " " + roots.size() );
		}

		rootLocatingTasks.clear();

//		for ( final TLongIterator r = roots.iterator(); r.hasNext(); )
//			System.out.println( "rtt " + r.next() );

		final long[] counts = new long[ roots.size() + 1 ];
		counts[ 0 ] = backgroundCount.get();

		final ArrayList< Callable< Void > > tasks = new ArrayList<>();

		final int fillTaskSize = Math.max( ( counts.length - 1 ) / nTasks, 1 );

		for ( int start = 1; start < counts.length; start += fillTaskSize )
		{
			final int finalStart = start;
			final int stop = Math.min( start + fillTaskSize, counts.length );
			tasks.add( () -> {
				final RandomAccess< L > flatLabels = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();
				final TLongArrayList queue = new TLongArrayList();
				for ( int i = finalStart; i < stop; ++i )
				{
					queue.add( roots.get( i - 1 ) );
					final long regionLabel = i | visitedMask;
					for ( int startIndex = 0; startIndex < queue.size(); ++startIndex )
					{
						final long index = queue.get( startIndex );
						for ( int d = 0; d < nEdges; ++d )
						{
							final long otherIndex = index + steps[ d ];
							if ( otherIndex >= 0 && otherIndex < size )
							{
								final long otherLabel = get( flatLabels, otherIndex ).getIntegerLong();
								if ( ( otherLabel & visitedMask ) == 0 && ( otherLabel & inverseBitmask[ d ] ) != 0 )
									queue.add( otherIndex );
							}
						}
						get( flatLabels, index ).setInteger( regionLabel );
						++counts[ i ];
					}
					queue.clear();
//					System.out.println( "Region " + i + " has size " + counts[ i ] );
				}
				return null;
			} );
		}

		{
			final long t0 = System.nanoTime();
			invokeAllAndWait( es, tasks );
			final long t1 = System.nanoTime();
			System.out.println( "\tFlood fill " + ( t1 - t0 ) / 1e6 + "ms" );
		}

		tasks.clear();

		// should this happen outside?
		final long activeBits = ~visitedMask;
		for ( long start = 0; start < size; start += taskSize )
		{
			final long finalStart = start;
			tasks.add( () -> {
				final Cursor< L > cursor = Views.flatIterable( labels ).cursor();
				cursor.jumpFwd( finalStart );
				for ( long count = 0; count < taskSize && cursor.hasNext(); ++count )
				{
					final L l = cursor.next();
					l.setInteger( l.getIntegerLong() & activeBits );
				}
				return null;
			} );
		}
		{
			final long t0 = System.nanoTime();
			invokeAllAndWait( es, tasks );
			final long t1 = System.nanoTime();
			System.out.println( "\tRemoving mask " + ( t1 - t0 ) / 1e6 + "ms" );
		}

		return counts;
	}

	private static long[] mergeAndCount(
			final RandomAccessibleInterval< LongType > labels,
			final long highBit,
			final long secondHighBit,
			final long[] bitmask,
			final long[] steps )
	{
		long[] counts = new long[ 1 ];
		int countsSize = 1;

		counts[ 0 ] = 0;
		long nextId = 1l;

		System.out.println( Arrays.toString( steps ) );
		for ( int d = 0; d < bitmask.length; ++d )
			System.out.print( Long.toBinaryString( bitmask[d] ) + ", " );
		System.out.println();

		final TLongArrayList queue = new TLongArrayList();

		final RandomAccess< LongType > flatLabels2 = FlatViews.flatten( new IterableRandomAccessibleInterval<>( labels ) ).randomAccess();

		final Cursor< LongType > c = Views.flatIterable( labels ).cursor();
		for ( long index = 0; c.hasNext(); ++index )
		{
			final LongType label = c.next();
			final long labelRaw = label.get();

			if ( labelRaw == 0 )
			{
				label.set( labelRaw | highBit );
				++counts[ 0 ];
			}

			if ( ( labelRaw & highBit ) == 0 && labelRaw != 0 )
			{

				queue.add( index );
				label.set( labelRaw | secondHighBit );

				for ( int queueIndex = 0; queueIndex < queue.size(); ++queueIndex )
				{
					final long nextIndex = queue.get( queueIndex );
					final long nextLabel = get( flatLabels2, nextIndex ).get();

					for ( int d = 0; d < bitmask.length; ++d )
						if ( ( nextLabel & bitmask[ d ] ) != 0 )
						{
							final long otherIndex = nextIndex + steps[ d ];
							if ( otherIndex < 0 || otherIndex >= FlatViews.flatten( labels ).dimension( 0 ) )
							{
								final Point p = new Point( labels.numDimensions() );
								IntervalIndexer.indexToPosition( nextIndex, labels, p );
								System.out.println( index + " " + nextIndex + " " + otherIndex +
										" |" + String.format( "%64s", Long.toBinaryString( nextLabel ) ) + " " + d + " " + p );
							}
							final long otherLabel = get( flatLabels2, otherIndex ).get();
							if ( ( otherLabel & highBit ) != 0 )
							{
								counts[ ( int ) ( otherLabel & ~highBit ) ] += queue.size();
								for ( final TLongIterator q = queue.iterator(); q.hasNext(); )
									get( flatLabels2, q.next() ).set( otherLabel );

								queue.clear();
								d = bitmask.length;
							}
							else if ( ( otherLabel & secondHighBit ) == 0 )
							{
								get( flatLabels2, otherIndex ).set( otherLabel | secondHighBit );
								queue.add( otherIndex );
							}
						}
				}

				if ( queue.size() > 0 )
				{
					if ( countsSize == counts.length )
					{
						final long[] countsTmp = new long[ 2 * counts.length ];
						System.arraycopy( counts, 0, countsTmp, 0, counts.length );
						counts = countsTmp;
					}
					counts[ countsSize ] = queue.size();

					for ( final TLongIterator q = queue.iterator(); q.hasNext(); )
					{
						final long ix = q.next();
						get( flatLabels2, ix ).set( highBit | nextId );
					}

					++countsSize;
					++nextId;
					queue.clear();
				}

			}
		}

		final long[] countsTmp = new long[ countsSize ];
		System.arraycopy( counts, 0, countsTmp, 0, countsSize );
		counts = countsTmp;
		return counts;
	}

	public static < T extends RealType< T >, L extends IntegerType< L > > TLongDoubleHashMap generateRegionGraph(
			final RandomAccessible< RealComposite< T > > source,
			final RandomAccessibleInterval< L > labels,
			final long[] steps,
			final CompareBetter< T > compare,
			final T worstValue,
			final long highBit,
			final long secondHighBit,
			final long nLabels )
	{
		final long validBits = ~( highBit | secondHighBit );
		final RandomAccess< RealComposite< T > > flatSource = FlatViews.flatten( Views.interval( source, labels ) ).randomAccess();
		final RandomAccess< L > flatLabels = FlatViews.flatten( labels ).randomAccess();

		final long size = FlatViews.flatten( labels ).dimension( 0 );
		final int nEdges = steps.length;

		final L l = labels.randomAccess().get().createVariable();
		final T t = source.randomAccess().get().get( 0 ).createVariable();

		final T compType = t.createVariable();

		final TLongDoubleHashMap graph = new TLongDoubleHashMap( Constants.DEFAULT_CAPACITY, Constants.DEFAULT_LOAD_FACTOR, -1, Double.NaN );

		final long[] dim = new long[] { nLabels, nLabels };
		final long[] fromTo = new long[ 2 ];

		for ( long index = 0; index < size; ++index )
		{
			final long label = get( flatLabels, index ).getIntegerLong() & validBits;
			final RealComposite< T > edges = get( flatSource, index );
			for ( int d = 0; d < nEdges; ++d )
			{
				final long otherIndex = index + steps[ d ];
				if ( otherIndex < 0 || otherIndex >= size )
					continue;
				final long otherLabel = get( flatLabels, otherIndex ).getIntegerLong() & validBits;
				final long from, to;
				if ( label == otherLabel )
					continue;
				else if ( label < otherLabel )
				{
					from = label;
					to = otherLabel;
				}
				else
				{
					from = otherLabel;
					to = label;
				}

				fromTo[ 0 ] = from;
				fromTo[ 1 ] = to;

				final long currentIndex = IntervalIndexer.positionToIndex( fromTo, dim );
				final T edge = edges.get( d );
				final double edgeDouble = edge.getRealDouble();

				if ( Double.isNaN( edgeDouble ) )
					continue;

				final double comp = graph.get( currentIndex );
				compType.setReal( comp );

				if ( Double.isNaN( comp ) || compare.isBetter( edge, compType ) )
					graph.put( currentIndex, edgeDouble );

			}
		}

		return graph;
	}

	public static interface Predicate
	{
		public boolean compare( double v1, double v2 );
	}

	public static interface Function
	{
		public double apply( double v );
	}

	public static < T > T get( final RandomAccess< T > access, final long index )
	{
		access.setPosition( index, 0 );
		return access.get();
	}

	public static < T > List< Future< T > > invokeAllAndWait( final ExecutorService es, final ArrayList< Callable< T > > tasks ) throws InterruptedException, ExecutionException
	{
		final List< Future< T > > futures = es.invokeAll( tasks );
		for ( final Future< T > f : futures )
			f.get();
		return futures;
	}

}
