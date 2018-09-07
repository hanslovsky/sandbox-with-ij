package net.imglib2.view

import net.imglib2.Localizable
import net.imglib2.Positionable
import net.imglib2.transform.InvertibleTransform

class PermuteCoordinateAxesTransform (vararg val lookup: Int)
 : InvertibleTransform
{

	val inverseLookup: IntArray
	init {
		inverseLookup = IntArray(lookup.size)
		for (i in lookup.indices)
			this.inverseLookup[lookup[i]] = i
	}

	override fun numTargetDimensions(): Int {
		return lookup.size
	}

	override fun numSourceDimensions(): Int {
		return lookup.size
	}

	override fun apply(source: LongArray?, target: LongArray?) {
		val t = target!!
		val s = source!!
		lookup.indices.forEach { t[it] = s[lookup[it]] }
	}

	override fun apply(source: IntArray?, target: IntArray?) {
		val t = target!!
		val s = source!!
		lookup.indices.forEach { t[it] = s[lookup[it]] }
	}

	override fun apply(source: Localizable?, target: Positionable?) {
		val t = target!!
		val s = source!!
		lookup.indices.forEach { t.setPosition(s.getLongPosition(lookup[it]), it) }
	}

	override fun inverse(): InvertibleTransform {
		return PermuteCoordinateAxesTransform(*inverseLookup)
	}

	override fun applyInverse(source: LongArray?, target: LongArray?) {
		val t = target!!
		val s = source!!
		lookup.indices.forEach { s[it] = t[inverseLookup[it]] }
	}

	override fun applyInverse(source: IntArray?, target: IntArray?) {
		val t = target!!
		val s = source!!
		lookup.indices.forEach { s[it] = t[inverseLookup[it]] }
	}

	override fun applyInverse(source: Positionable?, target: Localizable?) {
		val t = target!!
		val s = source!!
		lookup.indices.forEach { s.setPosition(t.getLongPosition(lookup[it]), it) }
	}

}
