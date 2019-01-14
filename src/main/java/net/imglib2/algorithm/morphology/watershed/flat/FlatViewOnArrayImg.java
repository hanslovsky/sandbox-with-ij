package net.imglib2.algorithm.morphology.watershed.flat;

import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.Sampler;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.NativeType;

public class FlatViewOnArrayImg< T extends NativeType< T > > extends AbstractFlatView< T >
{

	private final ArrayImg< T, ? > source;

	public FlatViewOnArrayImg( final ArrayImg< T, ? > source )
	{
		super( source );
		this.source = source;
	}

	@Override
	public ArrayImgFlatAccess< T > randomAccess()
	{
		return new ArrayImgFlatAccess<>( source );
	}

	public static class ArrayImgFlatAccess< T extends NativeType< T > > implements RandomAccess< T >
	{

		private final T type;

		public ArrayImgFlatAccess( final ArrayImg< T, ? > source )
		{
			this.type = source.createLinkedType();

			this.type.updateContainer( this );
			this.type.updateIndex( 0 );
		}

		public ArrayImgFlatAccess( final T type )
		{
			super();
			this.type = type.duplicateTypeOnSameNativeImg();

			this.type.updateContainer( this );
			this.type.updateIndex( type.getIndex() );
		}

		@Override
		public void localize( final int[] position )
		{
			position[ 0 ] = type.getIndex();
		}

		@Override
		public void localize( final long[] position )
		{
			position[ 0 ] = type.getIndex();
		}

		@Override
		public int getIntPosition( final int d )
		{
			return type.getIndex();
		}

		@Override
		public long getLongPosition( final int d )
		{
			return type.getIndex();
		}

		@Override
		public void localize( final float[] position )
		{
			position[ 0 ] = type.getIndex();
		}

		@Override
		public void localize( final double[] position )
		{
			position[ 0 ] = type.getIndex();
		}

		@Override
		public float getFloatPosition( final int d )
		{
			return type.getIndex();
		}

		@Override
		public double getDoublePosition( final int d )
		{
			return type.getIndex();
		}

		@Override
		public int numDimensions()
		{
			return 1;
		}

		@Override
		public void fwd( final int d )
		{
			type.incIndex();
		}

		@Override
		public void bck( final int d )
		{
			type.decIndex();
		}

		@Override
		public void move( final int distance, final int d )
		{
			type.incIndex( distance );
		}

		@Override
		public void move( final long distance, final int d )
		{
			move( ( int ) distance, d );
		}

		@Override
		public void move( final Localizable localizable )
		{
			move( localizable.getIntPosition( 0 ), 0 );
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
			setPosition( localizable.getIntPosition( 0 ), 0 );
		}

		@Override
		public void setPosition( final int[] position )
		{
			setPosition( position[ 0 ], 0 );
		}

		@Override
		public void setPosition( final long[] position )
		{
			setPosition( ( int ) position[ 0 ], 0 );
		}

		@Override
		public void setPosition( final int position, final int d )
		{
			type.updateIndex( position );
		}

		@Override
		public void setPosition( final long position, final int d )
		{
			setPosition( ( int ) position, d );
		}

		@Override
		public T get()
		{
			return type;
		}

		@Override
		public Sampler< T > copy()
		{
			return copyRandomAccess();
		}

		@Override
		public ArrayImgFlatAccess< T > copyRandomAccess()
		{
			return new ArrayImgFlatAccess<>( this.type );
		}

	}

}
