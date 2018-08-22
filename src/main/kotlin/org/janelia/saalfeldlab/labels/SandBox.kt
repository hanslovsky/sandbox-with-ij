package org.janelia.saalfeldlab.labels

import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.lang.invoke.MethodHandles
import java.util.stream.Stream

private class SandBox
{
	companion object {
	    val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
	}
}

@CommandLine.Command(name = "sandbox", mixinStandardHelpOptions = true)
private class CmdLineParameters {

	@CommandLine.Option(names = arrayOf("--commands", "-c"), help = true)
	var listCommands: Boolean = false

	@CommandLine.Parameters(index = "0", arity = "1", paramLabel = "COMMAND", converter = arrayOf(EndpointConverter::class))
	var command: Endpoint? = null

	@CommandLine.Parameters(index = "1", arity = "0..*", paramLabel = "COMMAND_ARGS")
	var commandArgs: List<String> = mutableListOf()

}

private enum class Endpoint(val run: (Array<String>) -> Unit, val commandName: String) {
	COMBINE_AS_HDF(CombineAsHDF.Companion::run, "combine-as-hdf");
}

private class EndpointConverter: CommandLine.ITypeConverter<Endpoint>
{
	override fun convert(arg: String?): Endpoint {
		val lowerCase = (arg ?: "").toLowerCase().replace("_", "-")
		return Stream
				.of(*Endpoint.values())
				.filter { it.commandName.equals(lowerCase) }
				.findFirst().get()
	}

}

fun main(args: Array<String>) {
	val param = CmdLineParameters()
	val cl = CommandLine(param)
	cl.setStopAtPositional(true)
	cl.parse(*args)
	if (cl.isUsageHelpRequested)
	{
		cl.usage(System.err)
		return
	}

	SandBox.LOG.warn("list commands? {}", param.listCommands)
	SandBox.LOG.warn("command        {}", param.command?.commandName)
	SandBox.LOG.warn("command args   {}", param.commandArgs)


	if (param.listCommands)
	{
		printAvailableCommands()
		return;
	}

	(param.command ?: throw RuntimeException("No command specified!")).run(param.commandArgs.toTypedArray())
}

private fun printAvailableCommands() {
	System.err.println("Available commands:")
	Stream.of(*Endpoint.values()).forEach { System.err.println("\t${it.commandName}") }
}
