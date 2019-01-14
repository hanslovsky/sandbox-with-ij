package net.imglib2.algorithm.morphology.watershed.flat;

import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.Positionable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPositionable;
import net.imglib2.util.Intervals;

public abstract class AbstractFlatView< T > implements RandomAccessibleInterval< T >
{

	private final long size;

	private final long max;

	public AbstractFlatView( final Dimensions source )
	{
		super();
		this.size = Intervals.numElements( source );
		this.max = this.size - 1;
	}

	@Override
	public RandomAccess< T > randomAccess( final Interval interval )
	{
		return randomAccess();
	}

	@Override
	public int numDimensions()
	{
		return 1;
	}

	@Override
	public long min( final int d )
	{
		return 0;
	}

	@Override
	public void min( final long[] min )
	{
		min[ 0 ] = 0;
		// or throw Exception?
	}

	@Override
	public void min( final Positionable min )
	{
		min.setPosition( 0l, 0 );
	}

	@Override
	public long max( final int d )
	{
		return max;
	}

	@Override
	public void max( final long[] max )
	{
		max[ 0 ] = this.max;
	}

	@Override
	public void max( final Positionable max )
	{
		max.setPosition( this.max, 0 );
	}

	@Override
	public double realMin( final int d )
	{
		return 0;
	}

	@Override
	public void realMin( final double[] min )
	{
		min[ 0 ] = 0.0d;
	}

	@Override
	public void realMin( final RealPositionable min )
	{
		min.setPosition( 0.0d, 0 );
	}

	@Override
	public double realMax( final int d )
	{
		return max;
	}

	@Override
	public void realMax( final double[] max )
	{
		max[ 0 ] = this.max;
	}

	@Override
	public void realMax( final RealPositionable max )
	{
		max.setPosition( this.max, 0 );
	}

	@Override
	public void dimensions( final long[] dimensions )
	{
		dimensions[ 0 ] = size;
	}

	@Override
	public long dimension( final int d )
	{
		return size;
	}

}
