package org.janelia.saalfeldlab.n5

fun main (args: Array<String>) {
	val HOME = System.getProperty("user.home")
	val directory = "$HOME/local/tmp/n5-long"
	val group = "group"
	val longAttribute = java.lang.Long(123)
	val writer = N5FSWriter(directory)
	writer.createGroup(group)
	writer.setAttribute(group, "longAttribute", longAttribute)
}
