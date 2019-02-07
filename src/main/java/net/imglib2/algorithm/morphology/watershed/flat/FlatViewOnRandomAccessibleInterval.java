package net.imglib2.algorithm.morphology.watershed.flat;

import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Sampler;
import net.imglib2.util.IntervalIndexer;

public class FlatViewOnRandomAccessibleInterval< T > extends AbstractFlatView< T >
{

	private final RandomAccessibleInterval< T > source;

	public FlatViewOnRandomAccessibleInterval( final RandomAccessibleInterval< T > source )
	{
		super( source );
		this.source = source;
	}

	@Override
	public RandomAccessibleIntervalFlatAccess randomAccess()
	{
		return new RandomAccessibleIntervalFlatAccess();
	}

	public class RandomAccessibleIntervalFlatAccess implements RandomAccess< T >
	{

		private final RandomAccess< T > access;

		private long position;

		public RandomAccessibleIntervalFlatAccess()
		{
			this( 0 );
		}

		public RandomAccessibleIntervalFlatAccess( final long position )
		{
			super();
			this.access = source.randomAccess();
			this.position = position;
			IntervalIndexer.indexToPosition( this.position, source, access );
		}

		@Override
		public void localize( final int[] position )
		{
			position[ 0 ] = ( int ) this.position;
		}

		@Override
		public void localize( final long[] position )
		{
			position[ 0 ] = this.position;
		}

		@Override
		public int getIntPosition( final int d )
		{
			return ( int ) this.position;
		}

		@Override
		public long getLongPosition( final int d )
		{
			return this.position;
		}

		@Override
		public void localize( final float[] position )
		{
			position[ 0 ] = this.position;
		}

		@Override
		public void localize( final double[] position )
		{
			position[ 0 ] = this.position;
		}

		@Override
		public float getFloatPosition( final int d )
		{
			return this.position;
		}

		@Override
		public double getDoublePosition( final int d )
		{
			return this.position;
		}

		@Override
		public int numDimensions()
		{
			return 1;
		}

		@Override
		public void fwd( final int d )
		{
			++position;
		}

		@Override
		public void bck( final int d )
		{
			--position;
		}

		@Override
		public void move( final int distance, final int d )
		{
			move( ( long ) distance, d );
		}

		@Override
		public void move( final long distance, final int d )
		{
			position += distance;
		}

		@Override
		public void move( final Localizable localizable )
		{
			move( localizable.getLongPosition( 0 ), 0 );
		}

		@Override
		public void move( final int[] distance )
		{
			move( ( long ) distance[ 0 ], 0 );
		}

		@Override
		public void move( final long[] distance )
		{
			this.position += distance[ 0 ];
		}

		@Override
		public void setPosition( final Localizable localizable )
		{
			setPosition( localizable.getLongPosition( 0 ), 0 );
		}

		@Override
		public void setPosition( final int[] position )
		{
			setPosition( position[ 0 ], 0 );
		}

		@Override
		public void setPosition( final long[] position )
		{
			setPosition( position[ 0 ], 0 );
		}

		@Override
		public void setPosition( final int position, final int d )
		{
			setPosition( ( long ) position, d );
		}

		@Override
		public void setPosition( final long position, final int d )
		{
			this.position = position;
		}

		@Override
		public T get()
		{
			IntervalIndexer.indexToPosition( this.position, source, access );
			return access.get();
		}

		@Override
		public Sampler< T > copy()
		{
			return copyRandomAccess();
		}

		@Override
		public RandomAccessibleIntervalFlatAccess copyRandomAccess()
		{
			return new RandomAccessibleIntervalFlatAccess( this.position );
		}

	}

}
