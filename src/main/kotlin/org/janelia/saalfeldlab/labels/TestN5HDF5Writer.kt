package org.janelia.saalfeldlab.labels

import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer

fun main(args: Array<String>) {
	val f = "/home/hanslovskyp/Downloads/sample_A_padded_20160501.hdf";
	val writer = N5HDF5Writer(f)
	val writer2 = N5HDF5Writer(f)
}
