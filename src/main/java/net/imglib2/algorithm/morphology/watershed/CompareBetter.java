package net.imglib2.algorithm.morphology.watershed;

public interface CompareBetter< T >
{

	boolean isBetter( T t1, T t2 );

}
