package net.imglib2.algorithm.morphology.watershed.flat;

import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.Sampler;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.img.planar.PlanarImg.PlanarContainerSampler;
import net.imglib2.type.NativeType;

public class FlatViewOnPlanarImg< T extends NativeType< T > > extends AbstractFlatView< T >
{

	private final PlanarImg< T, ? > source;

	public FlatViewOnPlanarImg( final PlanarImg< T, ? > source )
	{
		super( source );
		this.source = source;
	}

	@Override
	public PlanarImgFlatAccess< T > randomAccess()
	{
		return new PlanarImgFlatAccess<>( source );
	}

	public static class PlanarImgFlatAccess< T extends NativeType< T > > implements RandomAccess< T >, PlanarContainerSampler
	{

		private final T type;

		private final int sliceSize;

		private final int lastIndex;

		private final int lastSliceIndex;

		private int index;

		private int sliceIndex;

		private long position;

		public PlanarImgFlatAccess( final PlanarImg< T, ? > source )
		{
			super();
			this.type = source.createLinkedType();

			this.sliceSize = ( int ) ( ( source.numDimensions() > 1 ? source.dimension( 1 ) : 1 ) * source.dimension( 0 ) );
			this.lastIndex = this.sliceSize - 1;
			this.lastSliceIndex = source.numSlices() - 1;

			this.index = 0;
			this.sliceIndex = 0;

			this.type.updateContainer( this );
			this.type.updateIndex( this.index );
		}

		public PlanarImgFlatAccess( final PlanarImgFlatAccess< T > access )
		{
			super();
			this.type = access.type.duplicateTypeOnSameNativeImg();

			this.sliceSize = access.sliceSize;
			this.lastIndex = access.sliceIndex;
			this.lastSliceIndex = access.lastSliceIndex;

			this.index = access.index;
			this.sliceIndex = access.sliceIndex;

			this.type.updateContainer( this );
			this.type.updateIndex( this.index );
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
			++this.index;
			if ( index > this.lastIndex )
			{
				this.index = 0;
				++this.sliceIndex;
			}
		}

		@Override
		public void bck( final int d )
		{
			--this.index;
			if ( index < 0 )
			{
				this.index = this.lastIndex;
				--this.sliceIndex;
			}
		}

		@Override
		public void move( final int distance, final int d )
		{
			move( ( long ) distance, d );
		}

		@Override
		public void move( final long distance, final int d )
		{
			setPosition( position + distance, 0 );
		}

		@Override
		public void move( final Localizable localizable )
		{
			move( localizable.getLongPosition( 0 ), 0 );
		}

		@Override
		public void move( final int[] distance )
		{
			move( distance[ 0 ], 0 );
		}

		@Override
		public void move( final long[] distance )
		{
			move( ( int ) distance[ 0 ], 0 );
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
			this.sliceIndex = ( int ) ( position / sliceSize );
			this.index = ( int ) ( position - sliceSize * this.sliceIndex );
		}

		@Override
		public T get()
		{
			type.updateContainer( this );
			type.updateIndex( this.sliceIndex );
			return type;
		}

		@Override
		public Sampler< T > copy()
		{
			return copyRandomAccess();
		}

		@Override
		public RandomAccess< T > copyRandomAccess()
		{
			return new PlanarImgFlatAccess<>( this );
		}

		@Override
		public int getCurrentSliceIndex()
		{
			return this.sliceIndex;
		}

	}

}
