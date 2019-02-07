package net.imglib2.algorithm.morphology.watershed.flat;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.type.NativeType;

public class FlatViews
{
	public static < T > AbstractFlatView< T > flatten( final RandomAccessibleInterval< T > source )
	{
		if ( AbstractFlatView.class.isInstance( source ) )
		{
			return ( AbstractFlatView ) source;
		}
		else
		{
			if ( NativeType.class.isInstance( source.randomAccess().get() ) )
			{
				if ( ArrayImg.class.isInstance( source ) )
				{
					return new FlatViewOnArrayImg<>( ( ArrayImg ) source );
				}
				else if ( PlanarImg.class.isInstance( source ) )
				{
					return new FlatViewOnPlanarImg<>( ( PlanarImg ) source );
				}
				else
				{
					return new FlatViewOnRandomAccessibleInterval<>( source );
				}
			}
			else
			{
				return new FlatViewOnRandomAccessibleInterval<>( source );
			}
		}
	}
}
