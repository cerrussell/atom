package io.appthreat.atom.parsedeps

import better.files.File as ScalaFile
import io.appthreat.atom.dataflows.OssDataFlow
import io.appthreat.dataflowengineoss.language.*
import io.appthreat.dataflowengineoss.queryengine.EngineContext
import io.appthreat.x2cpg.X2Cpg
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Call, CfgNode, Identifier, Literal}
import io.shiftleft.semanticcpg.language.*

import java.io.File as JFile

object PythonDependencyParser extends XDependencyParser:

    implicit val engineContext: EngineContext = EngineContext()

    override def parse(cpg: Cpg): DependencySlice = DependencySlice(
      (parseSetupPy(cpg) ++ parseImports(cpg))
          .groupBy(_.name)
          .map { case (_, slices) => slices.reduce((a, b) => a.merge(b)) }
          .toSeq
          .sortBy(_.name)
    )

    private def parseSetupPy(cpg: Cpg): Set[ModuleWithVersion] =
        val dataFlowEnabled     = cpg.metaData.overlays.contains(OssDataFlow.overlayName)
        val requirementsPattern = """([\[\]/.\w_-]+)[\s]?((=>|<=|==|>=|=<|<|>|!=).*)""".r

        def dataSourcesToRequires = (cpg.literal ++ cpg.identifier)
            .where(_.file.name(".*setup.py"))
            .where(_.argumentName("install_requires"))
            .collectAll[CfgNode]

        def setupCall = cpg.call("setup").where(_.file.name(".*setup.py"))

        def findOriginalDeclaration(xs: Traversal[CfgNode]): Iterable[Literal] =
            xs.flatMap {
                case l: Literal =>
                    Iterable(l)
                case i: Identifier =>
                    findOriginalDeclaration(
                      cpg.assignment.where(_.and(
                        _.file.name(".*setup.py"),
                        _.target.isIdentifier.nameExact(i.name)
                      )).source
                    )
                case c: Call =>
                    findOriginalDeclaration(c.argument)
                case _ => Iterable()
            }.collectAll[Literal]
                .to(Iterable)

        val initialTraversal = if dataFlowEnabled then setupCall.reachableBy(dataSourcesToRequires)
        else dataSourcesToRequires
        findOriginalDeclaration(initialTraversal)
            .map(x => X2Cpg.stripQuotes(x.code))
            .map {
                case requirementsPattern(name, versionSpecifiers, _)
                    if versionSpecifiers.contains("==") =>
                    val versions     = versionSpecifiers.split(',').toSeq
                    val exactVersion = versions.find(_.startsWith("==")).get
                    ModuleWithVersion(
                      name,
                      exactVersion.stripPrefix("=="),
                      (versions diff Seq(exactVersion)).mkString(",")
                    )
                case requirementsPattern(name, versionSpecifiers, _) =>
                    ModuleWithVersion(name, versionSpecifiers = versionSpecifiers)
                case requirementsPattern(name, version) => ModuleWithVersion(name, version)
                case x                                  => ModuleWithVersion(x)
            }
            .toSet
    end parseSetupPy

    private def parseImports(cpg: Cpg): Set[ModuleWithVersion] =
        val root = ScalaFile(cpg.metaData.root.headOption.getOrElse(JFile.separator)).pathAsString
        // Get a set of local modules to exclude from imports
        val localModuleNames = cpg.file.name
            .filterNot(_ == "N/A")
            .map(x => ScalaFile(x))
            .map(_.pathAsString)
            .map(_.stripPrefix(s"$root${JFile.separatorChar}").split(
              JFile.separatorChar
            ).head.replaceFirst("\\.py", ""))
            .toSet
        cpg.imports
            .whereNot(_.call.file.name(".*setup.py"))
            .filterNot {
                _.importedEntity match
                    case Some(x) if x.startsWith(".") => true
                    case Some(x) if x.contains('.') => localModuleNames.contains(x.split('.').head)
                    case Some(x)                    => localModuleNames.contains(x)
                    case _                          => true
            }
            .dedup
            .importedEntity
            .map(x => ModuleWithVersion(name = x.split('.').head, importedSymbols = x))
            .toSet
    end parseImports
end PythonDependencyParser
